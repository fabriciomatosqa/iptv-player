package br.com.fabriciomatos.iptvplayer

import java.io.Reader
import java.net.URI
import java.text.Normalizer
import java.util.Locale

enum class MediaKind(val title: String) { LIVE("Ao vivo"), MOVIES("Filmes"), SERIES("Séries"), OTHER("Outros") }

private val marks = Regex("\\p{Mn}+")
private val separators = Regex("[^a-z0-9]+")
fun normalized(value: String): String = separators.replace(
    marks.replace(Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""), " "
).trim()

private val episodes = Regex("""\b(?:s\d{1,2}\s*e\d{1,3}|\d{1,2}\s*x\s*\d{1,3})\b""")
private val vodFiles = Regex("""\.(mp4|mkv|avi|mov|m4v|wmv|webm)(\?|#|$)""", RegexOption.IGNORE_CASE)
private val seriesWords = Regex("""\b(series?|seriados|tv shows?|temporadas?|episodios?|animes?|novelas?)\b""")
private val movieWords = Regex("""\b(filmes?|movies?|cinema|lancamentos|film)\b""")

fun classifyContent(name: String, group: String, url: String, typeHint: String = ""): MediaKind {
    when (normalized(typeHint)) {
        "live", "tv", "ao vivo", "channel" -> return MediaKind.LIVE
        "movie", "movies", "filme", "filmes", "vod" -> return MediaKind.MOVIES
        "series", "serie", "episode", "episodio", "tv show" -> return MediaKind.SERIES
    }
    val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
    // Explicit provider paths take precedence over ambiguous groups like "Canais de filmes".
    if (path.contains("/series/") || path.contains("/episode/")) return MediaKind.SERIES
    if (path.contains("/live/")) return MediaKind.LIVE
    val title = normalized(name)
    val category = normalized(group)
    if (episodes.containsMatchIn(title) || hasEpisodeMarker(name)) return MediaKind.SERIES
    if (category.startsWith("canais ") || category.contains("ao vivo")) return MediaKind.LIVE
    if (seriesWords.containsMatchIn(category)) return MediaKind.SERIES
    if (path.contains("/movie/") || path.contains("/movies/") || path.contains("/vod/")) return MediaKind.MOVIES
    if (movieWords.containsMatchIn(category)) return MediaKind.MOVIES
    if (vodFiles.containsMatchIn(url)) return MediaKind.MOVIES
    // M3U television lists frequently group channels by country/language, not by "live".
    return MediaKind.LIVE
}

data class Channel(
    val name: String,
    val url: String,
    val group: String = "Canais",
    val imageUrl: String = "",
    val tvgId: String = "",
    val userAgent: String = "",
    val referrer: String = "",
    val mimeType: String = "",
    val typeHint: String = "",
    val seriesId: String = "",
    val seriesTitle: String = "",
    val seriesImageUrl: String = "",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val kind: MediaKind = if (seriesId.isNotBlank() || seriesTitle.isNotBlank() || episodeNumber > 0) MediaKind.SERIES else classifyContent(name, group, url, typeHint)
) {
    val searchText = normalized("$name $group")
}

data class Playlist(val channels: List<Channel>, val epgUrl: String = "")

object M3uParser {
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private fun attrs(line: String): Map<String, String> = attributes.findAll(line).associate {
        it.groupValues[1].lowercase(Locale.ROOT) to (it.groupValues[2].ifEmpty { it.groupValues[3] })
    }

    /** A comma inside a quoted logo/group is metadata, not the title separator. */
    private fun titleStart(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { i, c ->
            if ((c == '"' || c == '\'') && (quote == null || quote == c)) quote = if (quote == null) c else null
            if (c == ',' && quote == null) return i + 1
        }
        return -1
    }

    fun parse(reader: Reader, source: String, checkCancelled: () -> Unit = {}): Playlist {
        val result = ArrayList<Channel>()
        val urls = HashSet<String>()
        var name = ""
        var metadata: Map<String, String> = emptyMap()
        var group = ""
        var agent = ""
        var referrer = ""
        var mime = ""
        var epg = ""
        var pendingEntry = false
        reader.buffered().useLines { lines ->
            lines.forEachIndexed { index, raw ->
                if (index % 256 == 0) checkCancelled()
                require(raw.length <= 131072) { "Uma entrada da lista é grande demais." }
                val line = raw.trim().removePrefix("\uFEFF")
                when {
                    line.startsWith("#EXT-X-TARGETDURATION", true) || line.startsWith("#EXT-X-STREAM-INF", true) ->
                        throw IllegalArgumentException("Este endereço é de um vídeo HLS. Cadastre a lista de canais M3U do provedor.")
                    line.startsWith("#EXTM3U", true) -> {
                        val header = attrs(line)
                        epg = resolve(source, (header["url-tvg"] ?: header["x-tvg-url"]).orEmpty().substringBefore(','))
                    }
                    line.startsWith("#EXTINF", true) -> {
                        pendingEntry = true
                        metadata = attrs(line)
                        val start = titleStart(line)
                        name = if (start >= 0) line.substring(start).trim() else metadata["tvg-name"].orEmpty()
                        group = metadata["group-title"].orEmpty().trim()
                        agent = metadata["http-user-agent"].orEmpty()
                        referrer = metadata["http-referrer"].orEmpty()
                        mime = ""
                    }
                    line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                    line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> agent = line.substringAfter('=').trim()
                    line.startsWith("#EXTVLCOPT:http-referrer=", true) -> referrer = line.substringAfter('=').trim()
                    line.startsWith("#KODIPROP:mimetype=", true) -> mime = line.substringAfter('=').trim()
                    line.isNotBlank() && !line.startsWith('#') -> {
                        val url = if (pendingEntry || line.startsWith("https://", true) || line.startsWith("http://", true)) resolve(source, line) else ""
                        if (url.isNotEmpty() && urls.add(url)) {
                            result.add(Channel(
                                name.ifBlank { metadata["tvg-name"].orEmpty().ifBlank { "Canal ${result.size + 1}" } },
                                url, group.ifBlank { "Canais" },
                                resolve(source, (metadata["tvg-logo"] ?: metadata["logo"] ?: metadata["poster"] ?: metadata["cover"]).orEmpty()),
                                metadata["tvg-id"].orEmpty(), agent, referrer, mime,
                                metadata["media-type"] ?: metadata["tvg-type"] ?: metadata["content-type"] ?: metadata["type"].orEmpty(),
                                metadata["series-id"].orEmpty(), metadata["series-name"] ?: metadata["series-title"].orEmpty(),
                                resolve(source, metadata["series-poster"] ?: metadata["series-logo"].orEmpty()),
                                (metadata["season-number"] ?: metadata["season"] ?: metadata["tvg-season"])?.toIntOrNull() ?: 0,
                                (metadata["episode-number"] ?: metadata["episode"] ?: metadata["tvg-episode"])?.toIntOrNull() ?: 0
                            ))
                        }
                        name = ""; metadata = emptyMap(); group = ""; agent = ""; referrer = ""; mime = ""
                        pendingEntry = false
                    }
                }
            }
        }
        checkCancelled()
        return Playlist(result, epg)
    }

    fun resolve(source: String, value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val uri = URI(source).resolve(value.trim())
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) uri.toString() else ""
        }.getOrDefault("")
    }
}

/** Daily updates are additive: missing old entries and their objects remain available. */
fun mergePlaylist(previous: List<Channel>, fresh: List<Channel>): List<Channel> {
    val urls = previous.mapTo(HashSet(previous.size + fresh.size)) { it.url }
    val additions = fresh.filter { urls.add(it.url) }
    return if (additions.isEmpty()) previous else previous + additions
}

data class Catalog(
    val channels: List<Channel> = emptyList(),
    val byKind: Map<MediaKind, List<Channel>> = emptyMap(),
    val groups: Map<MediaKind, List<String>> = emptyMap(),
    val series: List<SeriesEntry> = emptyList(),
    val seriesByEpisodeUrl: Map<String, String> = emptyMap()
) {
    companion object {
        fun build(channels: List<Channel>): Catalog {
            val byKind = channels.groupBy { it.kind }
            val series = groupSeries(byKind[MediaKind.SERIES].orEmpty())
            return Catalog(channels, byKind, byKind.mapValues { (_, items) -> items.map { it.group }.distinct().sorted() },
                series, series.flatMap { show -> show.episodes.map { it.channel.url to show.id } }.toMap())
        }
    }
}

fun filterChannels(catalog: Catalog, kind: MediaKind?, favoritesOnly: Boolean, favorites: Set<String>, group: String?, query: String): List<Channel> {
    val source = if (kind == null) catalog.channels else catalog.byKind[kind].orEmpty()
    val terms = normalized(query).split(' ').filter { it.isNotEmpty() }
    if (!favoritesOnly && group == null && terms.isEmpty()) return source
    return source.filter { (!favoritesOnly || it.url in favorites) && (group == null || it.group == group) && terms.all(it.searchText::contains) }
}
