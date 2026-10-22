package br.com.fabriciomatos.iptvplayer

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Anonymous on-demand public metadata. Never send playlist addresses or credentials.
 * Only an unambiguous normalized title (and public release year) may be searched.
 */
internal class ExternalSynopsis(private val readJson: (String) -> JSONObject) {
    suspend fun find(title: String, year: String, series: Boolean): Synopsis? = withContext(Dispatchers.IO) {
        val clean = cleanSynopsisTitle(title)
        if (clean.length < 3 || clean.length > 110 || clean.none(Char::isLetter)) return@withContext null
        val released = year.take(4).takeIf {it.matches(Regex("(19|20)\\d{2}"))}
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value
        if(series) try {
            tvMaze(clean,released)?.let {return@withContext it}
        } catch(e: CancellationException) {throw e} catch(_: Exception) { }
        // Wikipedia's editorial article summaries are a public, key-free fallback
        // for films, and for shows not found in TVmaze.
        for(lang in listOf("pt","en")) {
            try { wikipedia(clean,released,series,lang)?.let {return@withContext it} }
            catch(e: CancellationException) {throw e} catch(_: Exception) { }
        }
        null
    }

    private fun tvMaze(title: String, year: String?): Synopsis? {
        val url="https://api.tvmaze.com/singlesearch/shows?q="+encodeSynopsisQuery(title)
        val show=readJson(url)
        val found=show.optString("name")
        if(normalized(found)!=normalized(title))return null
        val premiered=show.optString("premiered").take(4)
        if(year!=null && year!=premiered)return null
        val plot=plainSynopsis(show.optString("summary"))
        if(plot.length<50)return null
        val image=show.optJSONObject("image")?.optString("original").orEmpty()
        val genres=show.optJSONArray("genres")
        return Synopsis(
            plot=plot, year=premiered,
            duration=show.optInt("runtime").takeIf {it>0}?.let {"$it min"}.orEmpty(),
            genre=(0 until (genres?.length()?:0)).mapNotNull {genres?.optString(it)}
                .joinToString(", ").take(160),
            rating=show.optJSONObject("rating")?.optString("average").orEmpty().takeIf {it!="null"}.orEmpty(),
            backdropUrl=providerImageUrl(image),
            sourceName="TVmaze",sourceUrl=show.optString("url").takeIf {it.startsWith("https://www.tvmaze.com/")}.orEmpty()
        )
    }

    private fun wikipedia(title: String, year: String?, series: Boolean, language: String): Synopsis? {
        val query=title+" "+(if(series)"série televisão" else "filme")+(year?.let {" $it"} ?: "")
        val base="https://$language.wikipedia.org"
        val search=readJson("$base/w/rest.php/v1/search/page?q="+encodeSynopsisQuery(query)+"&limit=8")
        val pages=search.optJSONArray("pages")?:return null
        for(i in 0 until minOf(8,pages.length())) {
            val page=pages.optJSONObject(i)?:continue
            val name=page.optString("title")
            if(!wikipediaMatches(title,year,series,name,page.optString("description")))continue
            val article=readJson("$base/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&redirects=1&titles="+encodeSynopsisQuery(name))
            val results=article.optJSONObject("query")?.optJSONObject("pages")?:continue
            val keys=results.keys()
            while(keys.hasNext()) {
                val data=results.optJSONObject(keys.next())?:continue
                val plot=plainSynopsis(data.optString("extract"))
                if(plot.length<65)continue
                val link="$base/wiki/"+name.replace(' ','_').split('/').joinToString("/") { encodeSynopsisQuery(it).replace("+","%20") }
                return Synopsis(plot=plot,year=year.orEmpty(),
                    sourceName=if(language=="pt")"Wikipédia" else "Wikipedia",
                    sourceUrl=link)
            }
        }
        return null
    }
}

internal fun encodeSynopsisQuery(value: String): String = URLEncoder.encode(value,"UTF-8")

/** Strip only playlist quality/language labels, never the title's meaningful words. */
internal fun cleanSynopsisTitle(title: String): String {
    val noTags=title.replace(Regex("""(?i)\s*[\[(](?:hd|fhd|uhd|4k|sd|720p|1080p|2160p|dub|dublado|leg|legendado|dual audio|l)[\])]""")," ")
    return noTags.replace(Regex("""\s*[\[(](?:19|20)\d{2}[\])]\s*$"""),"")
        .replace(Regex("""\s+""")," ").trim()
}

internal fun plainSynopsis(input: String): String =
    HtmlCompat.fromHtml(input.take(12000),HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString().replace(Regex("""\s+""")," ").trim().take(3200)

/** Exact title + media classification, plus explicit year when present.
 * Do not attach arbitrary first search hits to a playlist entry.
 */
internal fun wikipediaMatches(target: String, year: String?, series: Boolean,
    article: String, description: String): Boolean {
    val base=cleanSynopsisTitle(target)
    val suffix=Regex("""(?i)\s*\((?:filme|film|série|series|television series|tv series)(?:[^)]*)\)\s*$""")
    val articleBase=suffix.replace(article,"").replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""),"").trim()
    if(normalized(base)!=normalized(articleBase))return false
    val classification=normalized(description+" "+article.substringAfter('(',"").substringBefore(')',""))
    val accepts=if(series) Regex("""\b(serie|series|televisao|television|tv show)\b""")
        else Regex("""\b(film|filme|movie|cinema|longa metragem)\b""")
    if(!accepts.containsMatchIn(classification))return false
    if(year!=null) {
        val mentioned=Regex("""\b(?:19|20)\d{2}\b""")
            .findAll(article+" "+description).map{it.value}.toSet()
        if(year !in mentioned)return false
    }
    return true
}
