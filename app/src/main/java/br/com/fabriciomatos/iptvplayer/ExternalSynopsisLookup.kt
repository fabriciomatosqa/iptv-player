package br.com.fabriciomatos.iptvplayer

import androidx.core.text.HtmlCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Only public title/year are sent outside the IPTV provider, NEVER playlist URL or credentials. */
internal data class SynopsisSearchTitle(val title: String, val year: String?) {
    val normalizedTitle = normalized(title)
}

internal fun synopsisSearchTitle(raw: String): SynopsisSearchTitle? {
    val text = raw.trim().take(180)
    val year = Regex("""(?<!\d)(?:19\d{2}|20\d{2})(?!\d)""").find(text)?.value
    val cleaned = text
        .replace(Regex("""(?iu)\s*[\[(](?:19\d{2}|20\d{2})[\])]"""), " ")
        .replace(Regex("""(?iu)\s*[\[(](?:HD|FHD|UHD|4K|1080P|720P|DUBLADO|LEGENDADO|DUAL(?: AUDIO)?)[\])]"""), " ")
        .replace(Regex("""(?iu)\s+(?:HD|FHD|UHD|4K|1080P|720P|DUBLADO|LEGENDADO|DUAL AUDIO)\s*$"""), "")
        .trim(' ', '|', '-', '♦', '★', ':')
        .replace(Regex("""\s+"""), " ")
    if (normalized(cleaned).length !in 3..120) return null
    return SynopsisSearchTitle(cleaned, year)
}

internal fun sameSynopsisTitle(expected: SynopsisSearchTitle, candidate: String, year: String? = null): Boolean =
    normalized(candidate) == expected.normalizedTitle &&
        (expected.year == null || year == null || expected.year == year)

private fun wikiFilmBase(value: String): Pair<String, String?> {
    val base = value.replace(Regex("""(?iu)\s*\((?:filme|film|movie|telefilme|longa-metragem)[^)]*\)\s*$"""), "").trim()
    val year = Regex("""(?<!\d)(?:19\d{2}|20\d{2})(?!\d)""").find(value)?.value
    return base to year
}

/**
 * Public, keyless fallback: TVmaze for shows and Wikipedia for films.
 * Results with ambiguous titles or incompatible release years are discarded.
 * Injectable HTTP reader makes every matching/caching test deterministic and offline.
 */
internal class ExternalSynopsisLookup(private val fetch: (String) -> String = ::readExternalSynopsis) {
    fun lookup(rawTitle: String, movie: Boolean): ContentDetails? {
        val title = synopsisSearchTitle(rawTitle) ?: return null
        return if (movie) movie(title) else series(title)
    }

    private fun series(title: SynopsisSearchTitle): ContentDetails? {
        val results = JSONArray(fetch("https://api.tvmaze.com/search/shows?q=" + urlEncode(title.title)))
        val matching = (0 until minOf(results.length(), 15)).mapNotNull { i ->
            results.optJSONObject(i)?.optJSONObject("show")
        }.filter { show ->
            val premiered = show.optString("premiered").take(4).takeIf { it.matches(Regex("""\d{4}""")) }
            sameSynopsisTitle(title, show.optString("name"), premiered)
        }
        // Never attach a description to the wrong remake when its title is ambiguous.
        if (matching.size != 1) return null
        val show = matching.single()
        val id = show.optInt("id").takeIf { it > 0 } ?: return null
        val plot = HtmlCompat.fromHtml(show.optString("summary").take(6000),
            HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim().take(2800)
        val cast = runCatching {
            val extra = JSONObject(fetch("https://api.tvmaze.com/shows/$id?embed=cast"))
            val members = extra.optJSONObject("_embedded")?.optJSONArray("cast") ?: JSONArray()
            (0 until minOf(members.length(), 16)).mapNotNull { i ->
                val actor = members.optJSONObject(i)?.optJSONObject("person") ?: return@mapNotNull null
                actor.optString("name").trim().takeIf(String::isNotBlank)?.let { name ->
                    val photo = actor.optJSONObject("image")?.optString("medium").orEmpty()
                    CastMember(name, photo.takeIf(::isTvMazeImage).orEmpty())
                }
            }.distinctBy { normalized(it.name) }
        }.getOrDefault(emptyList())
        val image = show.optJSONObject("image")?.optString("original").orEmpty()
        val genres = show.optJSONArray("genres")
        val genre = (0 until (genres?.length() ?: 0)).mapNotNull { i ->
            genres?.optString(i)?.takeIf(String::isNotBlank)
        }.joinToString(", ").take(160)
        return ContentDetails(
            plot = plot,
            backdropUrl = image.takeIf(::isTvMazeImage).orEmpty(),
            cast = cast,
            year = show.optString("premiered").take(4),
            duration = show.optInt("averageRuntime").takeIf { it > 0 }?.let { "$it min" }.orEmpty(),
            genre = genre,
            rating = show.optJSONObject("rating")?.optString("average").orEmpty().takeUnless { it == "null" }.orEmpty(),
            sourceName = "TVmaze (CC BY-SA)",
            sourceUrl = "https://www.tvmaze.com/shows/$id"
        ).takeIf { it.plot.isNotBlank() || it.cast.isNotEmpty() }
    }

    private fun movie(title: SynopsisSearchTitle): ContentDetails? {
        for ((language, descriptor) in listOf("pt" to "filme", "en" to "film")) {
            val query = "\"" + title.title + "\" " + descriptor + (title.year?.let { " $it" } ?: "")
            val endpoint = "https://$language.wikipedia.org/w/api.php" +
                "?action=query&format=json&formatversion=2&generator=search" +
                "&gsrnamespace=0&gsrlimit=8&gsrsearch=" + urlEncode(query) +
                "&prop=extracts&exintro=1&explaintext=1&exchars=1100&exlimit=8"
            val pages = JSONObject(fetch(endpoint)).optJSONObject("query")?.optJSONArray("pages") ?: continue
            val matching = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.filter { page ->
                val heading = page.optString("title")
                val (base, pageYear) = wikiFilmBase(heading)
                val plot = page.optString("extract").trim()
                val film = heading.contains(Regex("""(?iu)\b(filme|film|movie)\b""")) ||
                    Regex("""(?iu)\b(filme|cinematogr[aá]fic[oa]|longa.metragem|motion picture|feature film)\b""")
                        .containsMatchIn(plot.take(270))
                sameSynopsisTitle(title, base, pageYear) && film && plot.length >= 45 &&
                    (title.year == null || pageYear == title.year ||
                        (pageYear == null && plot.take(400).contains(title.year)))
            }
            if (matching.size != 1) continue
            val page = matching.single()
            val id = page.optLong("pageid").takeIf { it > 0 } ?: continue
            return ContentDetails(
                plot = page.optString("extract").trim().take(1150),
                year = title.year.orEmpty(),
                sourceName = "Wikipedia (CC BY-SA)",
                sourceUrl = "https://$language.wikipedia.org/?curid=$id"
            )
        }
        return null
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun isTvMazeImage(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme.equals("https", true) && uri.host.orEmpty()
        .let { it == "static.tvmaze.com" || it.endsWith(".tvmaze.com") }
}.getOrDefault(false)

/** Bounded HTTPS fetch. Only constant public API hosts supplied above are queried. */
private fun readExternalSynopsis(address: String): String {
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 7000
        setRequestProperty("User-Agent",
            "AuroraIPTV/1.12.25 (https://github.com/fabriciomatosqa/iptv-player) Android")
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) return "{}"
        connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 1024 * 1024) { "Metadata too large" }
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
