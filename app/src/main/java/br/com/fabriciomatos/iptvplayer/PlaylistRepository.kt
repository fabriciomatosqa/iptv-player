package br.com.fabriciomatos.iptvplayer

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class Download(val playlist: Playlist, val etag: String, val modified: String)

class PlaylistRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aurora_iptv_preferences", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(app.filesDir, "aurora_playlist_cache.jsonl"))
    fun savedUrl() = prefs.getString("playlist_url", "").orEmpty()
    fun savedName() = prefs.getString("playlist_name", "Minha playlist").orEmpty()
    fun favorites() = prefs.getStringSet("favorite_urls", emptySet()).orEmpty().toSet()
    fun saveFavorites(urls: Set<String>) { prefs.edit().putStringSet("favorite_urls", urls).apply() }
    fun lastUrl() = prefs.getString("last_channel_url", "").orEmpty()
    fun saveLast(channel: Channel) { prefs.edit().putString("last_channel_url", channel.url).apply() }
    fun lastUpdated() = prefs.getLong("playlist_updated", 0L)

    fun saveSource(url: String, name: String) {
        // Bind the legacy cache before replacing the address, so it cannot appear under a new list.
        val editor = prefs.edit().putString("playlist_url", url).putString("playlist_name", name.ifBlank { "Minha playlist" })
        if (!prefs.contains("cache_source_hash")) editor.putString("cache_source_hash", digest(savedUrl()))
        if (!editor.commit()) throw IOException("Não consegui salvar o endereço neste aparelho.")
    }

    fun readCache(source: String): Playlist {
        val result = ArrayList<Channel>()
        var epg = ""
        return try {
            if (prefs.getString("cache_source_hash", digest(source)) != digest(source)) return Playlist(emptyList())
            cache.openRead().bufferedReader().useLines { lines ->
                val seen = HashSet<String>()
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        if (o.has("schema")) {
                            if (o.optString("sourceHash") != digest(source)) return Playlist(emptyList())
                            epg = o.optString("epgUrl")
                        } else {
                            val url = o.optString("url")
                            if (url.isNotBlank() && seen.add(url)) result.add(Channel(
                                o.optString("name", "Canal"), url, o.optString("group", "Canais"), o.optString("imageUrl"),
                                o.optString("tvgId"), o.optString("userAgent"), o.optString("referrer"), o.optString("mimeType")
                            ))
                        }
                    }
                }
            }
            Playlist(result, epg)
        } catch (_: IOException) { Playlist(emptyList()) }
    }

    /** Atomic replacement protects the last good list if a write is interrupted. */
    fun saveCache(source: String, download: Download) {
        val output = cache.startWrite()
        try {
            val writer = output.bufferedWriter()
            writer.append(JSONObject().put("schema", 2).put("sourceHash", digest(source)).put("epgUrl", download.playlist.epgUrl).toString())
            writer.newLine()
            download.playlist.channels.forEach { c ->
                writer.append(JSONObject().put("name", c.name).put("url", c.url).put("group", c.group)
                    .put("imageUrl", c.imageUrl).put("tvgId", c.tvgId).put("userAgent", c.userAgent)
                    .put("referrer", c.referrer).put("mimeType", c.mimeType).toString())
                writer.newLine()
            }
            writer.flush()
            cache.finishWrite(output)
            prefs.edit().putString("cache_source_hash", digest(source)).putString("etag", download.etag)
                .putString("last_modified", download.modified).putLong("playlist_updated", System.currentTimeMillis())
                .remove("playlist_cache").commit()
        } catch (e: Exception) { cache.failWrite(output); throw e }
    }

    suspend fun download(source: String, conditional: Boolean): Download? {
        val coroutine = currentCoroutineContext()
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AuroraIPTV/1.1 Android")
            setRequestProperty("Accept-Encoding", "gzip")
            if (conditional && prefs.getString("cache_source_hash", "") == digest(source)) {
                prefs.getString("etag", "")?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-None-Match", it) }
                prefs.getString("last_modified", "")?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Modified-Since", it) }
            }
        }
        try {
            coroutine.ensureActive()
            if (connection.responseCode == 304) return null
            if (connection.responseCode !in 200..299) throw IOException("O servidor respondeu HTTP ${connection.responseCode}. Confira o endereço da lista.")
            val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
            val playlist = stream.bufferedReader().use { M3uParser.parse(it, connection.url.toString()) { coroutine.ensureActive() } }
            if (playlist.channels.isEmpty()) throw IOException("Não encontrei canais nesta M3U. Confira se o endereço é de uma lista, e não de um vídeo.")
            return Download(playlist, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty())
        } finally { connection.disconnect() }
    }

    companion object {
        fun validUrl(value: String): Boolean = runCatching { val u = URL(value); (u.protocol == "http" || u.protocol == "https") && u.host.isNotBlank() }.getOrDefault(false)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
