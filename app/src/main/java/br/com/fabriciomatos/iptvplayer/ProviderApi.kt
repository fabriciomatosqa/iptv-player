package br.com.fabriciomatos.iptvplayer

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class SeriesPoster(val title: String, val image: String, val providerId: String = "", val anime: Boolean = false)
data class ProviderMetadata(val categories: Map<String, MediaKind> = emptyMap(),
    val posters: Map<String, SeriesPoster> = emptyMap())

/** Optional metadata from the same server the user supplied, never a third-party search. */
object ProviderApi {
    fun endpoint(source: String, action: String): String? = runCatching {
        val uri = URI(source)
        if (!uri.path.endsWith("/get.php")) return null
        val args = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val key = it.substringBefore('=')
            if (key == "username" || key == "password") key to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") else null
        }.toMap()
        if (args["username"].isNullOrBlank() || args["password"].isNullOrBlank()) return null
        val query = args.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" } +
            "&action=" + URLEncoder.encode(action, "UTF-8")
        URI(uri.scheme, uri.rawAuthority, uri.path.substringBeforeLast('/') + "/player_api.php", null, null).toString() + "?" + query
    }.getOrNull()

    fun epgEndpoint(source: String): String? = endpoint(source, "")?.let {
        it.substringBefore('?').replace("player_api.php", "xmltv.php") + "?" + it.substringAfter('?').substringBefore("&action=")
    }
    suspend fun load(source: String): ProviderMetadata {
        val context = currentCoroutineContext()
        if (endpoint(source, "get_live_categories") == null) return ProviderMetadata()
        val categories = HashMap<String, MediaKind>()
        val posters = HashMap<String, SeriesPoster>()
        listOf("get_live_categories" to MediaKind.LIVE, "get_vod_categories" to MediaKind.MOVIES,
            "get_series_categories" to MediaKind.SERIES).forEach { (action, kind) ->
            try { read(source, action) { row -> row["category_name"]?.takeIf(String::isNotBlank)?.let { categories[it.trim()] = kind } } }
            catch (e: CancellationException) { throw e }
            catch (_: IOException) { /* Cached categories remain usable when the API is unavailable. */ }
            context.ensureActive()
        }
        try {
            read(source, "get_series") { row ->
                val title = row["name"].orEmpty()
                val image = M3uParser.resolve(source, row["cover"].orEmpty())
                if (title.isNotBlank() && image.isNotBlank()) posters[normalized(title)] = SeriesPoster(title, image, row["series_id"].orEmpty(), isAnimationGenre(row["genre"].orEmpty()))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { }
        val aliases = posters.values.groupBy { posterAlias(it.title) }
        aliases.forEach { (alias, items) ->
            // Only reuse a year-less title if there is one unambiguous provider record.
            if (items.size == 1 && alias !in posters) posters[alias] = items.single()
        }
        return ProviderMetadata(categories, posters)
    }

    private suspend fun read(source: String, action: String, accept: (Map<String, String>) -> Unit) {
        val context = currentCoroutineContext()
        val address = endpoint(source, action) ?: return
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 25000
            setRequestProperty("User-Agent", "AuroraIPTV/1.6 Android")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("Metadados indisponíveis.")
            val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
            JsonReader(input.bufferedReader()).use { json ->
                if (json.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Resposta de metadados inválida.")
                json.beginArray()
                var count = 0
                val wanted = setOf("category_name", "name", "cover", "series_id", "genre")
                while (json.hasNext()) {
                    if (count++ % 128 == 0) context.ensureActive()
                    val row = HashMap<String, String>()
                    json.beginObject()
                    while (json.hasNext()) {
                        val field = json.nextName()
                        if (field in wanted && (json.peek() == JsonToken.STRING || json.peek() == JsonToken.NUMBER))
                            row[field] = json.nextString()
                        else json.skipValue()
                    }
                    json.endObject()
                    accept(row)
                }
                json.endArray()
            }
        } finally { connection.disconnect() }
    }
}
