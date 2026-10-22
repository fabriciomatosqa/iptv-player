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

/**
 * Source precedence: saved/provider IPTV metadata -> on-demand public search.
 * External queries carry only the cleaned title/year, NEVER IPTV URLs or credentials.
 * Old synopsis caches remain valid and provider data is never overwritten.
 */
class SynopsisRepository(
    private val repository: PlaylistRepository,
    private val readJson: (String)->JSONObject = ::readSynopsisJson,
    private val externalLookup: (String,Boolean)->ContentDetails? = ExternalSynopsisLookup()::lookup
) {
    private val indexLock = Mutex()
    private val externalLock = Mutex()

    suspend fun load(source: String, channel: Channel? = null, series: SeriesEntry? = null,
                     force: Boolean = false): Synopsis = withContext(Dispatchers.IO) {
        val movie = channel?.kind == MediaKind.MOVIES && series == null
        if (!movie && channel?.kind != MediaKind.SERIES && series == null) return@withContext Synopsis()
        val title = if (movie) channel!!.name else series?.title
            ?: channel?.let { seriesIdentity(it).title }.orEmpty()
        val lookup = synopsisSearchTitle(title)
        val provider = readProvider(source, channel, series, title, movie, force)
        val fromProvider = provider.first ?: ContentDetails()
        val online = if (fromProvider.plot.isBlank() && lookup != null)
            fetchExternal(source, lookup, movie, force)
        else null
        val details = mergeSynopsisDetails(fromProvider, online)
        Synopsis(
            plot = details.plot,
            episode = if (!movie && channel != null) selectEpisodeSynopsis(details, channel) else null,
            failed = provider.second && details.plot.isBlank(),
            backdropUrl = details.backdropUrl,
            cast = details.cast,
            year = details.year,
            duration = details.duration,
            genre = details.genre,
            rating = details.rating,
            director = details.director,
            sourceName = details.sourceName,
            sourceUrl = details.sourceUrl
        )
    }

    private suspend fun readProvider(
        source: String, channel: Channel?, series: SeriesEntry?, title: String,
        movie: Boolean, force: Boolean
    ): Pair<ContentDetails?,Boolean> {
        val endpoint = ProviderApi.endpoint(source, if (movie) "get_vod_info" else "get_series_info")
            ?: return null to false
        return try {
            val id = if (movie) {
                playbackContentId(channel!!.url)
                    ?: repository.synopsisMovieId(source, channel.name)
                    ?: indexLock.withLock {
                        // A huge VOD index must not block the details page indefinitely.
                        kotlinx.coroutines.withTimeoutOrNull(9000L) {
                            repository.refreshVodIds(source)
                            repository.synopsisMovieId(source, channel.name)
                        }
                    }
            } else {
                channel?.seriesId?.takeIf { it.matches(Regex("[0-9]+")) }
                    ?: series?.id?.removePrefix("id:")?.takeIf { it.matches(Regex("[0-9]+")) }
                    ?: repository.synopsisSeriesId(source, title)
                    ?: cachedSeriesId(source, title)
            }
            if (id == null) return null to false
            val key = synopsisCacheKey(source, movie, id)
            val cached = repository.cachedArtwork(key, 0)
                ?.let { runCatching { decodeContentDetails(JSONObject(it)) }.getOrNull() }
            val fresh = repository.cachedArtwork(key) != null
            if (!force && cached != null && fresh) return cached to false
            val details = try {
                val address = endpoint + "&" + (if (movie) "vod_id" else "series_id") + "=" + id
                val json = readJson(address)
                coroutineContext.ensureActive()
                // Some providers put the plot at the response root or movie_data,
                // without an info object. Don't discard those valid payloads.
                check(json.has("info") || json.has("movie_data") || json.has("plot") ||
                      json.has("description") || json.has("episodes"))
                parseContentDetails(json).also {
                    repository.saveArtwork(key, encodeContentDetails(it).toString())
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { return cached to (cached == null) }
            details to false
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { null to true }
    }

    private suspend fun fetchExternal(
        source: String, lookup: SynopsisSearchTitle, movie: Boolean, force: Boolean
    ): ContentDetails? = externalLock.withLock {
        val key = "synopsis-online:" + PlaylistRepository.digest(source) + ":" +
            (if (movie) "movie:" else "series:") + lookup.normalizedTitle +
            ":" + lookup.year.orEmpty()
        val stored = repository.cachedArtwork(key)
        if (!force && stored != null) {
            val json = runCatching { JSONObject(stored) }.getOrNull()
            if (json?.optBoolean("no_match") == true) return@withLock null
            if (json != null) return@withLock runCatching { decodeContentDetails(json) }.getOrNull()
        }
        val stale = repository.cachedArtwork(key, 0)?.let {
            runCatching { decodeContentDetails(JSONObject(it)) }.getOrNull()
        }
        try {
            coroutineContext.ensureActive()
            val details = externalLookup(
                lookup.title + (lookup.year?.let { " ($it)" } ?: ""), movie
            )
            coroutineContext.ensureActive()
            if (details == null) {
                repository.saveArtwork(key, JSONObject().put("no_match", true).toString(),
                    6L * 60 * 60 * 1000)
                return@withLock stale
            }
            repository.saveArtwork(key, encodeContentDetails(details).toString(), 21L * 86400000)
            details
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { stale }
    }

    private suspend fun cachedSeriesId(source: String, title: String): String? {
        val key = "synopsis-series-id:" + PlaylistRepository.digest(source) +
            ":" + normalized(title)
        repository.cachedArtwork(key)?.let {
            return JSONObject(it).optString("id").takeIf(String::isNotBlank)
        }
        val id = ProviderApi.findSeriesId(source, title)
        repository.saveArtwork(key, JSONObject().put("id", id.orEmpty()).toString(),
            if (id == null) 3600000L else 30L * 86400000)
        return id
    }
}

/** Prefer IPTV fields and fill missing fields without replacing verified provider metadata. */
internal fun mergeSynopsisDetails(provider: ContentDetails, online: ContentDetails?): ContentDetails {
    if (online == null) return provider
    val fills = provider.plot.isBlank() && online.plot.isNotBlank() ||
        provider.cast.isEmpty() && online.cast.isNotEmpty() ||
        provider.backdropUrl.isBlank() && online.backdropUrl.isNotBlank() ||
        provider.year.isBlank() && online.year.isNotBlank() ||
        provider.genre.isBlank() && online.genre.isNotBlank()
    return provider.copy(
        plot = provider.plot.ifBlank { online.plot },
        backdropUrl = provider.backdropUrl.ifBlank { online.backdropUrl },
        cast = provider.cast.ifEmpty { online.cast },
        year = provider.year.ifBlank { online.year },
        duration = provider.duration.ifBlank { online.duration },
        genre = provider.genre.ifBlank { online.genre },
        rating = provider.rating.ifBlank { online.rating },
        director = provider.director.ifBlank { online.director },
        sourceName = if (fills) online.sourceName else provider.sourceName,
        sourceUrl = if (fills) online.sourceUrl else provider.sourceUrl
    )
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
        plot=synopsisText(info).ifBlank { synopsisText(movieData) }
            .ifBlank { synopsisText(json) },
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
    .put("source_name",details.sourceName)
    .put("source_url",details.sourceUrl)

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
        director=json.optString("director"),
        sourceName=json.optString("source_name"),
        sourceUrl=json.optString("source_url")
    )
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
