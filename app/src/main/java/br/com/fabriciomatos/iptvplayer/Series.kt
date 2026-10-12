package br.com.fabriciomatos.iptvplayer

import java.util.Locale

private val seasonEpisodePatterns = listOf(
    Regex("""(?iu)\bS\s*(\d{1,3})\s*[.\- ]*E\s*(\d{1,4})\b"""),
    Regex("""(?iu)\b(\d{1,3})\s*x\s*(\d{1,4})\b"""),
    Regex("""(?iu)\b(?:temporada|season|temp\.?|t)\s*(\d{1,3})\s*[.\-: ]*(?:epis[oó]dio|episode|ep\.?|cap[ií]tulo|cap\.?|e)\s*(\d{1,4})\b""")
)
private val episodeOnly = Regex("""(?iu)\b(?:epis[oó]dio|episode|ep\.?|cap[ií]tulo|cap\.?)\s*(\d{1,4})\b""")
private val qualityTag = Regex("""(?iu)[\[(]\s*(?:br|pt|hd|fhd|sd|uhd|4k|720p|1080p|2160p|dublado|dub|legendado|leg|dual audio)\s*[\])]""")
private val trailingQuality = Regex("""(?iu)\s*[-|]?\s*(?:hd|fhd|sd|uhd|4k|720p|1080p|2160p|dublado|dub|legendado|leg|dual audio)\s*$""")
private val spaces = Regex("\\s+")

fun hasEpisodeMarker(name: String) = seasonEpisodePatterns.any { it.containsMatchIn(name) } || episodeOnly.containsMatchIn(name)

data class Episode(val channel: Channel, val season: Int, val number: Int, val title: String) {
    val label: String get() = if (number > 0) "T${season.toString().padStart(2, '0')} • E${number.toString().padStart(2, '0')}" else "Temporada $season"
}

data class SeriesEntry(val id: String, val title: String, val imageUrl: String, val groups: Set<String>, val episodes: List<Episode>,
    val episodeCount: Int = episodes.size, val seasons: List<Int> = episodes.map { it.season }.distinct().sorted(), val adult: Boolean = episodes.any { it.channel.adult },
    val anime: Boolean = groups.any(::isAnimeGroup)) {
    val searchText = normalized("$title ${groups.joinToString(" ")}")
}

data class SeriesIdentity(val id: String, val title: String, val episode: Episode)

private fun cleanTitle(value: String): String {
    var result = qualityTag.replace(value, " ").trim(' ', '-', '|', '.', ':')
    result = result.trimEnd('(', '[').trim()
    while (trailingQuality.containsMatchIn(result)) result = trailingQuality.replace(result, "").trim(' ', '-', '|')
    return spaces.replace(result, " ").trim()
}

fun seriesIdentity(channel: Channel): SeriesIdentity {
    val match = seasonEpisodePatterns.firstNotNullOfOrNull { it.find(channel.name) }
    val lone = if (match == null) episodeOnly.find(channel.name) else null
    val marker = match ?: lone
    val base = marker?.let { channel.name.substring(0, it.range.first) }.orEmpty()
    val name = cleanTitle(channel.seriesTitle.ifBlank { base.ifBlank { if (marker == null) channel.name else "" } })
    // Do not collapse unrelated unnamed episodes into a generic "Séries" group.
    val title = name.ifBlank { channel.seriesId.takeIf { it.isNotBlank() }?.let { "Série $it" } ?: channel.name }
    val id = if (channel.seriesId.isNotBlank()) "id:${channel.seriesId}" else "title:${normalized(title)}"
    val season = channel.seasonNumber.takeIf { it > 0 } ?: match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    val number = channel.episodeNumber.takeIf { it > 0 } ?: match?.groupValues?.get(2)?.toIntOrNull() ?: lone?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val suffix = marker?.let { channel.name.substring(it.range.last + 1).trim(' ', '-', '|', '.', ':', ')', ']') }.orEmpty()
    return SeriesIdentity(id, title, Episode(channel, season, number, suffix.ifBlank { channel.name }))
}

fun groupSeries(channels: List<Channel>): List<SeriesEntry> {
    val entries = channels.map(::seriesIdentity).groupBy { it.id }
    return entries.map { (id, items) ->
        val cover = items.firstNotNullOfOrNull { it.episode.channel.seriesImageUrl.takeIf(String::isNotBlank) }
            ?: items.firstNotNullOfOrNull { it.episode.channel.imageUrl.takeIf(String::isNotBlank) }.orEmpty()
        SeriesEntry(id, items.first().title, cover, items.map { it.episode.channel.group }.toSet(),
            items.map { it.episode }.sortedWith(compareBy<Episode> { it.season }.thenBy { if (it.number > 0) it.number else Int.MAX_VALUE }.thenBy { it.channel.name.lowercase(Locale.ROOT) }))
    }.sortedBy { normalized(it.title) }
}

fun filterSeries(catalog: Catalog, group: String?, query: String): List<SeriesEntry> {
    val words = normalized(query).split(' ').filter(String::isNotBlank)
    return if (group == null && words.isEmpty()) catalog.series else catalog.series.filter {
        (group == null || group in it.groups) && words.all(it.searchText::contains)
    }
}
