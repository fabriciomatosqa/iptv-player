package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Public, no-API-key metadata lookup, only after the playlist provider has no plot.
 * NEVER transmits the playlist URL, user/password, stream URL or playback IDs:
 * only a cleaned title (and the public media kind) leaves the device.
 *
 * Cinemeta is a best-effort public Stremio catalog, not an IPTV provider.
 * Exact title and year matching prevents plausible but incorrect descriptions.
 */
class PublicSynopsisFallback(
    private val cache: PlaylistRepository,
    private val fetchJson: (String) -> JSONObject = ::readPublicSynopsisJson
) {
    fun lookup(source: String, rawTitle: String, movie: Boolean, force: Boolean = false): ContentDetails? {
        val request = cleanPublicTitle(rawTitle) ?: return null
        val key = "public-synopsis-v1:" + PlaylistRepository.digest(source) +
            ":" + (if (movie) "movie:" else "series:") + normalized(request.title) + ":" + request.year.orEmpty()
        if (!force) {
            cache.cachedArtwork(key)?.let { cached ->
                return runCatching {
                    val json = JSONObject(cached)
                    if (json.optBoolean("found", false)) decodeContentDetails(json.getJSONObject("details")) else null
                }.getOrNull()
            }
        }

        val result = try {
            val type = if (movie) "movie" else "series"
            val query = URLEncoder.encode(request.title, "UTF-8").replace("+", "%20")
            val listing = fetchJson("https://v3-cinemeta.strem.io/catalog/$type/top/search=$query.json")
            val array = listing.optJSONArray("metas") ?: JSONArray()
            val candidates = (0 until minOf(array.length(), 30)).mapNotNull(array::optJSONObject)
                .filter { normalized(it.optString("name")) == normalized(request.title) }
                .filter { request.year == null || candidateYear(it) == request.year }
                .distinctBy { it.optString("id") }
            // Exact title alone is not sufficient if several remakes share it.
            val picked = candidates.singleOrNull()
            if (picked == null) null else {
                val id = picked.optString("id")
                val metadata = if (id.matches(Regex("tt[0-9]{5,12}"))) {
                    fetchJson("https://v3-cinemeta.strem.io/meta/$type/$id.json")
                        .optJSONObject("meta") ?: picked
                } else picked
                if (normalized(metadata.optString("name")) != normalized(request.title) ||
                    (request.year != null && candidateYear(metadata) != request.year)) null
                else parsePublicContentDetails(metadata).takeIf { it.plot.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network errors are short-lived; retry during the next visit.
            cache.saveArtwork(key, JSONObject().put("found", false).toString(), 15 * 60 * 1000L)
            return null
        }
        cache.saveArtwork(
            key,
            JSONObject().put("found", result != null)
                .apply { result?.let { put("details", encodeContentDetails(it)) } }.toString(),
            if (result == null) 3 * 60 * 60 * 1000L else 30L * 86400000L
        )
        return result
    }
}

internal data class PublicTitle(val title: String, val year: String?)

internal fun cleanPublicTitle(raw: String): PublicTitle? {
    val year = Regex("""(?:\(|\[|\s)(19\d{2}|20\d{2})(?:\)|\]|\s|$)""")
        .find(raw)?.groupValues?.get(1)
    val name = raw
        .replace(Regex("""\[(?:4K|UHD|HD|FHD|SD|LEG|DUB|DUBLADO|LEGENDADO|DUAL AUDIO)\]""",
            RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\((?:4K|UHD|HD|FHD|SD|LEG|DUB|DUBLADO|LEGENDADO|DUAL AUDIO)\)""",
            RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s*\(?\[?(?:19|20)\d{2}\]?\)?\s*$"""), " ")
        .trim(' ', '-', '|', '.', ':')
        .replace(Regex("\\s+"), " ")
    if (name.length !in 3..130 || normalized(name).length < 3) return null
    return PublicTitle(name, year)
}

private fun candidateYear(meta: JSONObject): String? {
    val raw = listOf("releaseInfo", "year", "released", "releaseDate")
        .firstNotNullOfOrNull { k -> meta.optString(k).takeIf { it.isNotBlank() && it != "null" } }
        ?: return null
    return Regex("""(?:19|20)\d{2}""").find(raw)?.value
}

internal fun parsePublicContentDetails(meta: JSONObject): ContentDetails {
    val cast = meta.optJSONArray("cast") ?: JSONArray()
    val actors = (0 until minOf(cast.length(), 20)).mapNotNull { i ->
        when (val item = cast.opt(i)) {
            is String -> item.trim().takeIf(String::isNotBlank)?.let { CastMember(it) }
            is JSONObject -> item.optString("name").trim().takeIf(String::isNotBlank)
                ?.let { CastMember(it, providerImageUrl(item.optString("profile_path"), "w185")) }
            else -> null
        }
    }.distinctBy { normalized(it.name) }
    val genres = meta.optJSONArray("genres")
    val genre = if (genres != null) (0 until minOf(4, genres.length()))
        .mapNotNull { genres.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
    else meta.optString("genre")
    val director = meta.optJSONArray("director")?.let { values ->
        (0 until minOf(3, values.length())).map { values.optString(it) }.joinToString(", ")
    } ?: meta.optString("director")
    return ContentDetails(
        plot = meta.optString("description").trim().take(12000),
        backdropUrl = providerImageUrl(meta.optString("background")),
        cast = actors,
        year = candidateYear(meta).orEmpty(),
        duration = meta.optString("runtime").take(40),
        genre = genre.take(160),
        rating = meta.optString("imdbRating").take(16),
        director = director.take(160)
    )
}

private fun readPublicSynopsisJson(address: String): JSONObject {
    require(address.startsWith("https://v3-cinemeta.strem.io/"))
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4500
        readTimeout = 6500
        setRequestProperty("User-Agent", "AuroraIPTV/1.12 Android")
        setRequestProperty("Accept", "application/json")
    }
    try {
        check(connection.responseCode in 200..299)
        val output = ByteArrayOutputStream()
        connection.inputStream.use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val len = stream.read(buf)
                if (len < 0) break
                check(output.size() + len <= 768 * 1024)
                output.write(buf, 0, len)
            }
        }
        return JSONObject(output.toString("UTF-8"))
    } finally {
        connection.disconnect()
    }
}
