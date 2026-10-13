package br.com.fabriciomatos.iptvplayer

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** On-demand details from the playlist's own provider. No account data goes to another service. */
class SynopsisRepository(private val repository: PlaylistRepository, private val readJson: (String)->JSONObject = ::readSynopsisJson) {
    private val indexLock=Mutex()
    suspend fun load(source: String, channel: Channel? = null, series: SeriesEntry? = null, force: Boolean = false): Synopsis = withContext(Dispatchers.IO) {
        val movie=channel?.kind==MediaKind.MOVIES
        if(!movie && channel?.kind!=MediaKind.SERIES && series==null)return@withContext Synopsis()
        if(ProviderApi.endpoint(source,"get_vod_info")==null)return@withContext Synopsis()
        try {
            val title=series?.title ?: channel?.let {seriesIdentity(it).title}.orEmpty()
            val id=if(movie)playbackContentId(channel!!.url) ?: repository.synopsisMovieId(source,channel.name) ?: indexLock.withLock {
                repository.refreshVodIds(source);repository.synopsisMovieId(source,channel.name)
            } else channel?.seriesId?.takeIf {it.matches(Regex("[0-9]+"))}
                ?: series?.id?.removePrefix("id:")?.takeIf {it.matches(Regex("[0-9]+"))}
                ?: repository.synopsisSeriesId(source,title)
                ?: cachedSeriesId(source,title)
            if(id==null)return@withContext Synopsis()
            val action=if(movie)"get_vod_info"else"get_series_info"
            val key=synopsisCacheKey(source,movie,id)
            val cached=repository.cachedArtwork(key,0)?.let {runCatching {decodeContentDetails(JSONObject(it))}.getOrNull()}
            val fresh=repository.cachedArtwork(key)!=null
            val details=if(!force && cached!=null && fresh)cached else try {
                val address=ProviderApi.endpoint(source,action)+"&"+(if(movie)"vod_id"else"series_id")+"="+id
                val json=readJson(address)
                coroutineContext.ensureActive()
                check(json.optJSONObject("info")!=null)
                parseContentDetails(json).also {repository.saveArtwork(key,encodeContentDetails(it).toString())}
            } catch(e: CancellationException) {throw e} catch(_: Exception) {
                cached ?: return@withContext Synopsis(failed=true)
            }
            Synopsis(details.plot,if(!movie && channel!=null)selectEpisodeSynopsis(details,channel)else null)
        } catch(e: CancellationException) {throw e} catch(_: Exception) {Synopsis(failed=true)}
    }
    private suspend fun cachedSeriesId(source: String, title: String): String? {
        val key="synopsis-series-id:"+PlaylistRepository.digest(source)+":"+normalized(title)
        repository.cachedArtwork(key)?.let {return JSONObject(it).optString("id").takeIf(String::isNotBlank)}
        val id=ProviderApi.findSeriesId(source,title)
        repository.saveArtwork(key,JSONObject().put("id",id.orEmpty()).toString(),if(id==null)3600000L else 30*86400000L)
        return id
    }
}
fun synopsisCacheKey(source: String, movie: Boolean, id: String) = "synopsis:"+PlaylistRepository.digest(source)+":"+(if(movie)"movie"else"series")+":"+id
private fun synopsisText(info: JSONObject?): String {
    if(info==null)return ""
    for(field in listOf("plot","description","overview","synopsis")) {
        val raw=info.opt(field) as? String ?: continue
        if(raw.isBlank() || raw.trim().lowercase() in setOf("null","n/a","none","-"))continue
        val text=HtmlCompat.fromHtml(raw.take(32000),HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim().take(16000)
        if(text.isNotBlank())return text
    }
    return ""
}
fun parseContentDetails(json: JSONObject): ContentDetails {
    val episodes=json.optJSONObject("episodes")
    val rows=buildList {
        episodes?.keys()?.forEach {season->
            val list=episodes.optJSONArray(season) ?: return@forEach
            for(i in 0 until list.length()) {
                val row=list.optJSONObject(i) ?: continue
                val number=row.optInt("episode_num");val s=row.optInt("season",season.toIntOrNull() ?: 0)
                if(number>0 && s>=0)add(EpisodeSynopsis(row.optString("id"),s,number,row.optString("title"),synopsisText(row.optJSONObject("info")).ifBlank {synopsisText(row)}))
            }
        }
    }
    return ContentDetails(synopsisText(json.optJSONObject("info")),rows)
}
fun encodeContentDetails(details: ContentDetails): JSONObject = JSONObject().put("plot",details.plot).put("episodes",JSONArray(details.episodes.map {
    JSONObject().put("id",it.id).put("season",it.season).put("number",it.number).put("title",it.title).put("plot",it.plot)
}))
fun decodeContentDetails(json: JSONObject): ContentDetails {
    val episodes=json.getJSONArray("episodes")
    return ContentDetails(json.getString("plot"),(0 until episodes.length()).map {i->episodes.getJSONObject(i).let {
        EpisodeSynopsis(it.getString("id"),it.getInt("season"),it.getInt("number"),it.getString("title"),it.getString("plot"))
    }})
}
private fun readSynopsisJson(address: String): JSONObject {
    val connection=(URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout=10000;readTimeout=15000;setRequestProperty("User-Agent","AuroraIPTV/1.8 Android");setRequestProperty("Accept","application/json")
    }
    try {
        check(connection.responseCode in 200..299)
        return connection.inputStream.use {input->
            val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=input.read(buffer);if(count<0)break;check(result.size()+count<=8*1024*1024);result.write(buffer,0,count)}
            JSONObject(result.toString("UTF-8"))
        }
    } finally {connection.disconnect()}
}
