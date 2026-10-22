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
        val title=if(movie) channel!!.name else series?.title ?: channel?.let {seriesIdentity(it).title}.orEmpty()
        // Query only when the user opens details; never scan huge catalogs.
        val supplied=try { loadFromProvider(source,channel,series,movie,force) }
        catch(e: CancellationException) {throw e}
        catch(_: Exception) {Synopsis(failed=true)}
        if(supplied.plot.isNotBlank())return@withContext supplied

        val externalKey="synopsis-public:"+PlaylistRepository.digest(source)+
            ":"+(if(movie)"movie"else"series")+":"+normalized(title)
        val cached=repository.cachedArtwork(externalKey)
            ?.let {runCatching {JSONObject(it)}.getOrNull()}
        val external=if(!force && cached!=null) {
            decodePublicSynopsis(cached)
        } else {
            val found=try {
                ExternalSynopsis(readJson).find(title,supplied.year,movie.not())
            } catch(e: CancellationException) {throw e} catch(_: Exception) {null}
            repository.saveArtwork(externalKey,encodePublicSynopsis(found).toString(),
                if(found?.plot.isNullOrBlank())6*3600000L else 14L*86400000)
            found
        }
        // Provider attributes always win; online sources only fill genuine gaps.
        if(external==null || external.plot.isBlank())return@withContext supplied
        supplied.copy(
            plot=external.plot,
            backdropUrl=supplied.backdropUrl.ifBlank {external.backdropUrl},
            cast=supplied.cast.ifEmpty {external.cast},
            year=supplied.year.ifBlank {external.year},
            duration=supplied.duration.ifBlank {external.duration},
            genre=supplied.genre.ifBlank {external.genre},
            rating=supplied.rating.ifBlank {external.rating},
            sourceName=external.sourceName,
            sourceUrl=external.sourceUrl,
            failed=false
        )
    }

    /** Retain existing Xtream caching, IDs and offline operation unchanged. */
    private suspend fun loadFromProvider(
        source: String, channel: Channel?, series: SeriesEntry?,
        movie: Boolean, force: Boolean
    ): Synopsis {
        if(ProviderApi.endpoint(source,"get_vod_info")==null)return Synopsis()
        val title=series?.title ?: channel?.let {seriesIdentity(it).title}.orEmpty()
        val id=if(movie)playbackContentId(channel!!.url) ?: repository.synopsisMovieId(source,channel.name) ?: indexLock.withLock {
            repository.refreshVodIds(source);repository.synopsisMovieId(source,channel.name)
        } else channel?.seriesId?.takeIf {it.matches(Regex("[0-9]+"))}
            ?: series?.id?.removePrefix("id:")?.takeIf {it.matches(Regex("[0-9]+"))}
            ?: repository.synopsisSeriesId(source,title)
            ?: cachedSeriesId(source,title)
        if(id==null)return Synopsis()
        val action=if(movie)"get_vod_info"else"get_series_info"
        val key=synopsisCacheKey(source,movie,id)
        val cached=repository.cachedArtwork(key,0)?.let {runCatching {decodeContentDetails(JSONObject(it))}.getOrNull()}
        val fresh=repository.cachedArtwork(key)!=null
        val details=if(!force && cached!=null && fresh)cached else try {
            val address=ProviderApi.endpoint(source,action)+"&"+(if(movie)"vod_id"else"series_id")+"="+id
            val json=readJson(address)
            coroutineContext.ensureActive()
            check(json.optJSONObject("info")!=null || json.optJSONObject("movie_data")!=null)
            parseContentDetails(json).also {repository.saveArtwork(key,encodeContentDetails(it).toString())}
        } catch(e: CancellationException) {throw e} catch(_: Exception) {
            cached ?: return Synopsis(failed=true)
        }
        return Synopsis(
            plot=details.plot,
            episode=if(!movie && channel!=null)selectEpisodeSynopsis(details,channel)else null,
            backdropUrl=details.backdropUrl,
            cast=details.cast,
            year=details.year,
            duration=details.duration,
            genre=details.genre,
            rating=details.rating,
            director=details.director
        )
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
/** Read only imagery announced by the user's own provider; no metadata credentials leave it. */
internal fun providerImageUrl(value: String, size: String = "w1280"): String {
    val raw = value.trim().trim('"')
    return when {
        raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
        raw.startsWith("/") && !raw.startsWith("//") && raw.length > 2 ->
            "https://image.tmdb.org/t/p/$size$raw"
        else -> ""
    }
}

private fun providerBackdrop(info: JSONObject): String {
    val possible = listOf("backdrop_path", "backdrop", "backdrop_url", "background", "fanart")
    for (field in possible) {
        val raw = info.opt(field)
        val first = when(raw) {
            is JSONArray -> (0 until raw.length()).firstNotNullOfOrNull { raw.optString(it).takeIf(String::isNotBlank) }
            is String -> {
                if (raw.trim().startsWith("[")) runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).firstNotNullOfOrNull { arr.optString(it).takeIf(String::isNotBlank) }
                }.getOrNull() else raw
            }
            else -> null
        }
        first?.let { providerImageUrl(it).takeIf(String::isNotBlank)?.let { url -> return url } }
    }
    return ""
}

private fun providerCast(info: JSONObject): List<CastMember> {
    val raw = info.opt("actors") ?: info.opt("cast") ?: return emptyList()
    val array = when(raw) {
        is JSONArray -> raw
        is String -> if(raw.trim().startsWith("[")) runCatching { JSONArray(raw) }.getOrNull() else null
        else -> null
    }
    if (array != null) return (0 until minOf(array.length(), 24)).mapNotNull { i ->
        when(val item = array.opt(i)) {
            is JSONObject -> {
                val name = item.optString("name").ifBlank {item.optString("actor_name")}.trim()
                val img = listOf("profile_path","image","photo","profile","thumbnail")
                    .firstNotNullOfOrNull { key -> item.optString(key).takeIf(String::isNotBlank) }
                    .orEmpty()
                if(name.isBlank()) null else CastMember(name, providerImageUrl(img,"w185"))
            }
            is String -> item.trim().takeIf(String::isNotBlank)?.let {CastMember(it)}
            else -> null
        }
    }.distinctBy { normalized(it.name) }
    val text = (raw as? String).orEmpty()
    return text.split(',', ';', '|').map(String::trim).filter(String::isNotBlank)
        .distinctBy(::normalized).take(24).map(::CastMember)
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
    val info=json.optJSONObject("info") ?: JSONObject()
    val movieData=json.optJSONObject("movie_data") ?: JSONObject()
    fun field(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        info.optString(key).takeIf { it.isNotBlank() && it != "null" }
            ?: movieData.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty()
    return ContentDetails(
        plot=synopsisText(info).ifBlank {synopsisText(movieData)}.ifBlank {synopsisText(json)},
        episodes=rows,
        backdropUrl=providerBackdrop(info).ifBlank {providerBackdrop(movieData)},
        cast=providerCast(info).ifEmpty {providerCast(movieData)},
        year=field("releasedate","release_date","year").take(16),
        duration=field("duration","runtime").take(40),
        genre=field("genre","genres").take(160),
        rating=field("rating_5based","rating","vote_average").take(16),
        director=field("director").take(160)
    )
}

fun encodeContentDetails(details: ContentDetails): JSONObject = JSONObject()
    .put("plot",details.plot)
    .put("episodes",JSONArray(details.episodes.map {
        JSONObject().put("id",it.id).put("season",it.season).put("number",it.number)
            .put("title",it.title).put("plot",it.plot)
    }))
    .put("backdrop",details.backdropUrl)
    .put("cast",JSONArray(details.cast.map { JSONObject().put("name",it.name).put("image",it.imageUrl) }))
    .put("year",details.year)
    .put("duration",details.duration)
    .put("genre",details.genre)
    .put("rating",details.rating)
    .put("director",details.director)

fun decodeContentDetails(json: JSONObject): ContentDetails {
    val episodes=json.optJSONArray("episodes") ?: JSONArray()
    val cast=json.optJSONArray("cast") ?: JSONArray()
    return ContentDetails(
        plot=json.optString("plot"),
        episodes=(0 until episodes.length()).mapNotNull { i ->
            episodes.optJSONObject(i)?.let {
                EpisodeSynopsis(it.optString("id"),it.optInt("season"),it.optInt("number"),
                    it.optString("title"),it.optString("plot"))
            }
        },
        backdropUrl=json.optString("backdrop"),
        cast=(0 until cast.length()).mapNotNull { i ->
            cast.optJSONObject(i)?.let {CastMember(it.optString("name"),it.optString("image"))}
        },
        year=json.optString("year"),
        duration=json.optString("duration"),
        genre=json.optString("genre"),
        rating=json.optString("rating"),
        director=json.optString("director")
    )
}
internal fun encodePublicSynopsis(value: Synopsis?): JSONObject = JSONObject().apply {
    put("found",value!=null && value.plot.isNotBlank())
    if(value!=null) {
        put("plot",value.plot)
        put("backdrop",value.backdropUrl)
        put("year",value.year)
        put("duration",value.duration)
        put("genre",value.genre)
        put("rating",value.rating)
        put("sourceName",value.sourceName)
        put("sourceUrl",value.sourceUrl)
    }
}

internal fun decodePublicSynopsis(json: JSONObject): Synopsis? {
    if(!json.optBoolean("found",false))return null
    return Synopsis(plot=json.optString("plot"),backdropUrl=json.optString("backdrop"),
        year=json.optString("year"),duration=json.optString("duration"),
        genre=json.optString("genre"),rating=json.optString("rating"),
        sourceName=json.optString("sourceName"),sourceUrl=json.optString("sourceUrl"))
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
