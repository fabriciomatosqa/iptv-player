package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
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
    guide.matches.filter { it.programme.end > now-FOOTBALL_RETENTION_MS }.forEach { entry ->
        val details = footballDetails(entry.programme.title, entry.programme.category) ?: return@forEach
        val available = (byId[entry.channelId].orEmpty() + epgNames[entry.channelId].orEmpty().flatMap { byName[it].orEmpty() })
            .distinctBy { it.url }.sortedByDescending { if (it.name.contains("FHD", true)) 3 else if (it.name.contains("HD", true)) 2 else 1 }
        if (available.isNotEmpty()) {
            val id = normalized("${details.competition}:${details.home}:${details.away}") + ":" + entry.programme.start
            val previous = result[id]
            result[id] = FootballMatch(id, entry.programme, (previous?.channels.orEmpty() + available).distinctBy { it.url }, details)
        }
    }
    return result.values.sortedWith(compareBy<FootballMatch> { it.programme.start }.thenBy { it.programme.title })
}
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

