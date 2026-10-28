package br.com.fabriciomatos.iptvplayer

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Metadata-only public fallback. Never passes the IPTV playlist URL, provider
 * credentials, content stream address or account identifiers to third parties.
 * Only the cleaned title and optional release year are searched, on demand.
 */
class PublicSynopsisLookup(
    private val request: (String) -> JSONObject = ::readSynopsisJson
) {
    suspend fun find(title: String, kind: MediaKind, suppliedYear: String = ""): ContentDetails? =
        withContext(Dispatchers.IO) {
            val name = cleanSynopsisTitle(title)
            if (name.length < 3 || name.length > 130 || kind == MediaKind.LIVE || kind == MediaKind.OTHER)
                return@withContext null
            val year = yearInTitle(title) ?: suppliedYear.take(4).toIntOrNull()
            val wikipedia = try { findWikipedia(name, kind, year) }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { null }
            if(wikipedia != null) return@withContext wikipedia
            if(kind != MediaKind.SERIES) return@withContext null
            try { findTvMaze(name,year) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { null }
        }

    private fun findWikipedia(name: String, kind: MediaKind, year: Int?): ContentDetails? {
        val qualifier = if (kind == MediaKind.MOVIES) "filme" else "série de televisão"
        // The title is the only user data leaving the device (no playlist URL).
        val query = urlEncode("$name $qualifier")
        val search = request("https://pt.wikipedia.org/w/rest.php/v1/search/page?q=$query&limit=6")
        val pages = search.optJSONArray("pages") ?: return null
        val choices = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }
            .filter { candidate ->
                val article = candidate.optString("title")
                val description = candidate.optString("description")
                isTrustedMediaMatch(name, article, description, kind, year)
            }
        if (choices.size != 1) return null // Never guess between ambiguous works.
        val title = choices.single().optString("title")
        val summary = request("https://pt.wikipedia.org/api/rest_v1/page/summary/${urlEncode(title.replace(' ', '_'))}")
        if (summary.optString("type") == "disambiguation") return null
        if (!isTrustedMediaMatch(name, summary.optString("title").ifBlank {title},
                summary.optString("description").ifBlank {choices.single().optString("description")},kind,year))
            return null
        val extract = summary.optString("extract").trim()
        if (extract.length < 45) return null
        return ContentDetails(
            plot = extract.take(1800),
            year = year?.toString().orEmpty(),
            backdropUrl = summary.optJSONObject("originalimage")?.optString("source")
                ?.takeIf(::isPublicImageUrl).orEmpty(),
            metadataSource = "Wikipédia",
            metadataUrl = summary.optJSONObject("content_urls")?.optJSONObject("desktop")
                ?.optString("page").orEmpty()
        )
    }

    private fun findTvMaze(name: String, year: Int?): ContentDetails? {
        val results = requestArray("https://api.tvmaze.com/search/shows?q=${urlEncode(name)}")
        val matching = (0 until minOf(results.length(), 10)).mapNotNull { i ->
            results.optJSONObject(i)?.optJSONObject("show")
        }.filter { show ->
            if (normalized(show.optString("name")) != normalized(name)) false
            else {
                val premiered = show.optString("premiered").take(4).toIntOrNull()
                year == null || premiered == null || premiered == year
            }
        }
        if (matching.size != 1) return null
        val show = matching.single()
        val html = show.optString("summary")
        val plot = HtmlCompat.fromHtml(html,HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString().trim().take(1800)
        if (plot.length < 45) return null
        val url = show.optString("url").takeIf {it.startsWith("https://www.tvmaze.com/")}
            .orEmpty()
        return ContentDetails(
            plot = plot,
            year = show.optString("premiered").take(4),
            genre = show.optJSONArray("genres")?.let { g ->
                (0 until g.length()).joinToString(", ") {g.optString(it)}
            }.orEmpty(),
            duration = show.optInt("runtime").takeIf { it > 0 }?.let { "$it min" }.orEmpty(),
            backdropUrl = show.optJSONObject("image")?.optString("original")
                ?.takeIf(::isPublicImageUrl).orEmpty(),
            metadataSource = "TVmaze",
            metadataUrl = url
        )
    }

    private fun requestArray(url: String): JSONArray {
        val wrapper = request(url)
        // The shared network reader returns JSONObject; the alternate list
        // reader wraps a JSON array in an "items" property.
        return wrapper.optJSONArray("items") ?: JSONArray()
    }
}

internal fun cleanSynopsisTitle(value: String): String {
    return value.replace(Regex("""(?i)\b(?:4K|UHD|FHD|HD|SD|1080p|720p|2160p|DUBLADO|LEGENDADO|DUAL AUDIO)\b""")," ")
        .replace(Regex("""\s*[\[(](?:19|20)\d{2}[\])]\s*""")," ")
        .replace(Regex("""\[\s*\]|\(\s*\)""")," ")
        .replace(Regex("""(?iu)^\s*(?:filmes?|cinema|lançamentos?)\s*[|:–-]\s*"""),"")
        .replace(Regex("""\s+""")," ").trim(' ','-','|',':','.')
}

internal fun yearInTitle(value: String): Int? =
    Regex("""(?:\(|\[|\b)((?:19|20)\d{2})(?:\)|\]|\b)""")
        .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun isTrustedMediaMatch(
    requested: String, article: String, description: String, kind: MediaKind, year: Int?
): Boolean {
    val title = article.replace(Regex("""\s*\([^)]*\)\s*$"""),"").trim()
    if (normalized(title) != normalized(requested)) return false
    val text = normalized("$article $description")
    val expected = if(kind == MediaKind.MOVIES)
        listOf("filme","cinema","movie","film","longa metragem")
    else listOf("serie","televisao","television","tv series","telenovela")
    if (expected.none(text::contains)) return false
    val explicit = yearInTitle(article)
    return year == null || explicit == null || explicit == year
}

private fun isPublicImageUrl(url: String) =
    url.startsWith("https://upload.wikimedia.org/") ||
        url.startsWith("https://static.tvmaze.com/")

private fun urlEncode(value: String) = URLEncoder.encode(value,"UTF-8")
