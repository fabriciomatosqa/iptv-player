package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Locale
import java.util.TimeZone

enum class BroadcastHint { UNKNOWN, LIVE, REPLAY }
fun programmeBroadcastHint(title: String, category: String): BroadcastHint {
    val text=normalized("$title $category")
    return when {
        Regex("\\b(reprise|replay|repeticao|reexibicao)\\b").containsMatchIn(text)->BroadcastHint.REPLAY
        Regex("\\b(ao vivo|live)\\b").containsMatchIn(text)->BroadcastHint.LIVE
        else->BroadcastHint.UNKNOWN
    }
}

data class Programme(val title: String, val start: Long, val end: Long, val image: String = "", val category: String = "", val broadcast: BroadcastHint = BroadcastHint.UNKNOWN)
data class GuideMatch(val channelId: String, val programme: Programme)
data class FootballMatch(val id: String, val programme: Programme, val channels: List<Channel>, val details: FootballDetails)
fun footballMatches(guide: Guide, channels: List<Channel>, now: Long = System.currentTimeMillis()): List<FootballMatch> {
    val live = channels.filter { it.kind == MediaKind.LIVE && !it.adult }
    val byId = live.filter { it.tvgId.isNotBlank() }.groupBy { it.tvgId }
    val byName = live.groupBy { guideChannelName(it.name) }
    val epgNames = guide.names.entries.groupBy({ it.value }, { guideChannelName(it.key) })
    val result = linkedMapOf<String, FootballMatch>()
    val mostRecentByTeams = mutableMapOf<String, String>()
    // The same fixture may appear with slightly different EPG start times across providers.
    // Sort once and retain prior broadcasts only to identify genuine reruns.
    val history = mutableMapOf<String, Programme>()
    guide.matches.asSequence().sortedBy { it.programme.start }.forEach { entry ->
        val details = footballDetails(entry.programme.title, entry.programme.category) ?: return@forEach
        val available = (byId[entry.channelId].orEmpty() + epgNames[entry.channelId].orEmpty().flatMap { byName[it].orEmpty() })
            .distinctBy { it.url }.sortedByDescending { if (it.name.contains("FHD", true)) 3 else if (it.name.contains("HD", true)) 2 else 1 }
        val teams = listOf(footballScoreTeamKey(details.home), footballScoreTeamKey(details.away)).sorted()
        val competition = if (footballTabKey(details) == FOOTBALL_LIBERTADORES_WOMEN)
            FOOTBALL_LIBERTADORES_WOMEN else footballLeagueSlug(details.competition) ?: normalized(details.competition)
        val fixtureKey = listOf(competition, teams[0], teams[1]).joinToString(":")
        val previousAiring = history[fixtureKey]
        val priorFinished = previousAiring != null && previousAiring.end < now &&
            entry.programme.start - previousAiring.end in 2*3600000L..36*3600000L
        // A replay label needs explicit EPG metadata or evidence of a previous airing.
        val broadcast = if (entry.programme.broadcast == BroadcastHint.UNKNOWN && priorFinished)
            BroadcastHint.REPLAY else entry.programme.broadcast
        val programme = entry.programme.copy(broadcast=broadcast)
        if (previousAiring == null || entry.programme.start - previousAiring.start > 90*60000L)
            history[fixtureKey] = entry.programme
        if (entry.programme.end <= now-FOOTBALL_RETENTION_MS) return@forEach
        if (available.isNotEmpty()) {
            val category = if (programme.broadcast == BroadcastHint.REPLAY) "replay" else "game"
            val groupKey = listOf(fixtureKey, category).joinToString(":")
            val previous = mostRecentByTeams[groupKey]?.let(result::get)
                ?.takeIf { abs(it.programme.start - entry.programme.start) <= 90 * 60000L }
            val id = previous?.id ?: groupKey + ":" + entry.programme.start
            result[id] = FootballMatch(id, previous?.programme ?: programme,
                (previous?.channels.orEmpty() + available).distinctBy { it.url }
                    .sortedWith(compareByDescending<Channel> { footballChannelQuality(it.name) }.thenBy { it.name }), details)
            mostRecentByTeams[groupKey] = id
        }
    }
    return result.values.sortedWith(compareBy<FootballMatch> { it.programme.start }.thenBy { it.programme.title })
}
/** Prefer HD/FHD variants of different broadcasters as the two main buttons.
 * Every underlying stream remains available through the overflow menu. */
fun footballChannelQuality(name: String): Int = when {
    Regex("(?i)\\b(?:4k|uhd|2160p)\\b").containsMatchIn(name) -> 4
    Regex("(?i)\\b(?:fhd|full\\s*hd|1080p)\\b").containsMatchIn(name) -> 3
    Regex("(?i)\\b(?:hd|720p)\\b").containsMatchIn(name) -> 2
    else -> 1
}
private fun footballBroadcasterKey(name: String): String = normalized(
    name.replace(Regex("(?i)\\b(?:full\\s*hd|fhd|uhd|hd|sd|4k|2160p|1080p|720p|480p|h265|h264|hevc|alt)\\b|[\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ").trim()
)
fun footballFeaturedChannels(channels: List<Channel>): List<Channel> =
    channels.distinctBy { it.url }
        .sortedWith(compareByDescending<Channel> { footballChannelQuality(it.name) }.thenBy { it.name })
        .distinctBy { footballBroadcasterKey(it.name) }.take(2)
fun guideChannelName(name: String) = normalized(name.replace(Regex("(?i)\\b(?:fhd|uhd|hd|sd|4k|h265|h264|alt)\\b|[\\[\\]]"), " "))
data class Guide(val programmes: Map<String, List<Programme>> = emptyMap(), val names: Map<String, String> = emptyMap(), val matches: List<GuideMatch> = emptyList()) {
    fun forChannel(channel: Channel): List<Programme> = if (channel.kind != MediaKind.LIVE) emptyList() else programmes[channel.tvgId]
        ?: programmes[names[normalized(channel.name)]] ?: programmes[names[guideChannelName(channel.name)]] ?: emptyList()
}

fun xmltvTime(value: String): Long = runCatching {
    val parts = value.trim().split(Regex("\\s+"))
    val digits = parts.first()
    val pattern = if (digits.length == 12) "yyyyMMddHHmm" else "yyyyMMddHHmmss"
    val zone = parts.getOrNull(1) ?: "+0000"
    SimpleDateFormat("$pattern Z", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        .parse("$digits $zone")?.time ?: 0L
}.getOrDefault(0L)

