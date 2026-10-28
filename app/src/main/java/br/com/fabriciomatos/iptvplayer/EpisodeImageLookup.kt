package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Searches public series metadata only; stream URLs and provider account data are never sent. */
class EpisodeImageLookup(private val request: (String) -> JSONObject = ::readSynopsisJson) {
    suspend fun find(title: String, suppliedYear: String = ""): List<EpisodeSynopsis> = withContext(Dispatchers.IO) {
        val name = cleanSynopsisTitle(title)
        if(name.length !in 3..130) return@withContext emptyList()
        val year = yearInTitle(title) ?: suppliedYear.take(4).toIntOrNull()
        try {
            val json = request("https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(name,"UTF-8"))
            val show = selectEpisodeImageShow(name,year,json.optJSONArray("items") ?: JSONArray())
                ?: return@withContext emptyList()
            val id = show.optLong("id").takeIf { it > 0 } ?: return@withContext emptyList()
            val page = "https://www.tvmaze.com/shows/$id"
            val episodes = request("https://api.tvmaze.com/shows/$id/episodes?specials=1")
                .optJSONArray("items") ?: return@withContext emptyList()
            parsePublicEpisodeImages(episodes,page)
        } catch(e: CancellationException) { throw e }
          catch(_: Exception) { emptyList() }
    }
}

internal fun selectEpisodeImageShow(name: String, year: Int?, results: JSONArray): JSONObject? {
    val matches = (0 until minOf(results.length(),50)).mapNotNull { results.optJSONObject(it)?.optJSONObject("show") }
        .filter { show ->
            normalized(show.optString("name")) == normalized(name) &&
                (year == null || show.optString("premiered").take(4).toIntOrNull() == year)
        }.distinctBy { it.optLong("id") }
    // Search ranking is fuzzy; neither a top result nor an unspecified remake is proof of identity.
    return matches.singleOrNull()
}

internal fun parsePublicEpisodeImages(rows: JSONArray, sourceUrl: String): List<EpisodeSynopsis> = buildList {
    for(i in 0 until minOf(rows.length(),2000)) {
        val row = rows.optJSONObject(i) ?: continue
        val season = row.optInt("season",-1)
        val number = row.optInt("number",-1)
        if(season < 0 || number <= 0) continue
        val image = row.optJSONObject("image")
        val url = image?.optString("medium").orEmpty().ifBlank {image?.optString("original").orEmpty()}
            .takeIf {it.startsWith("https://static.tvmaze.com/uploads/images/")}.orEmpty()
        if(url.isBlank()) continue
        val minutes = row.optLong("runtime").takeIf {it in 1..2880} ?: 0
        // Public episode IDs live in another namespace; do not match them to IPTV stream IDs.
        add(EpisodeSynopsis("",season,number,row.optString("name").take(500),"",url,minutes*60000,
            "TVmaze",sourceUrl))
    }
}.groupBy {it.season to it.number}.values.mapNotNull { items -> items.distinctBy {it.imageUrl}.singleOrNull() }

/** Fill only absent fields. Provider plots, names and images always take precedence. */
internal fun mergeEpisodeImages(provider: List<EpisodeSynopsis>, extra: List<EpisodeSynopsis>): List<EpisodeSynopsis> {
    val byNumber = extra.groupBy {it.season to it.number}
    val existing = provider.map {it.season to it.number}.toSet()
    return provider.map { row ->
        val image = byNumber[row.season to row.number]?.singleOrNull() ?: return@map row
        row.copy(
            imageUrl=row.imageUrl.ifBlank {image.imageUrl},
            durationMs=row.durationMs.takeIf {it > 0} ?: image.durationMs,
            imageSource=if(row.imageUrl.isBlank())image.imageSource else row.imageSource,
            imageSourceUrl=if(row.imageUrl.isBlank())image.imageSourceUrl else row.imageSourceUrl
        )
    } + extra.filter {it.season to it.number !in existing}
}
