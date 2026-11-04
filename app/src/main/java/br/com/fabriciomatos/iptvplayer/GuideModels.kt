package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Locale
import java.util.TimeZone

enum class BroadcastHint { UNKNOWN, LIVE, REPLAY }
fun programmeBroadcastHint(title: String, category: String): BroadcastHint {
    val text=normalized("$title $category")
    return when {
        Regex("\\b(reprise|replay|repeticao|reexibicao|vt)\\b").containsMatchIn(text)->BroadcastHint.REPLAY
        Regex("\\b(ao vivo|live)\\b").containsMatchIn(text)->BroadcastHint.LIVE
        else->BroadcastHint.UNKNOWN
    }
}

/** Raw provider evidence is persisted separately from the display title and derived classification. */
data class FootballMetadata(val originalTitle: String = "", val subtitle: String = "", val description: String = "",
    val competition: String = "", val gender: String = "", val country: String = "", val division: String = "", val sport: String = "", val retainedEvidence: String = "", val guideSource: String = "")
data class Programme(val title: String, val start: Long, val end: Long, val image: String = "", val category: String = "", val broadcast: BroadcastHint = BroadcastHint.UNKNOWN,
    val metadata: FootballMetadata = FootballMetadata())
data class GuideMatch(val channelId: String, val programme: Programme)
data class FootballMatch(val id: String, val programme: Programme, val channels: List<Channel>, val details: FootballDetails,
    val channelProgrammes: Map<String, Programme> = emptyMap()) {
    fun programmeFor(channel: Channel): Programme = channelProgrammes[channel.url] ?: programme
    val broadcastEnd: Long get() = channelProgrammes.values.maxOfOrNull { it.end } ?: programme.end
}
/** Merge the same fixture across broadcasters, retaining EPG slots and every stream.
 * Generic football labels can borrow a known championship; two named leagues,
 * age groups, genders and explicitly marked replays remain separate. */
fun footballMatches(guide: Guide, channels: List<Channel>, now: Long = System.currentTimeMillis()): List<FootballMatch> {
    val live=channels.filter { it.kind==MediaKind.LIVE && !it.adult }
    val byId=live.filter { it.tvgId.isNotBlank() }.groupBy { guide.channelId(it) }
    val byName=live.filter { it.tvgId.isBlank() }.mapNotNull { channel ->
        guide.channelId(channel)?.let { it to channel }
    }.groupBy({ it.first },{ it.second })
    val result=linkedMapOf<String,FootballMatch>()
    val nearby=mutableMapOf<String,MutableList<String>>()
    guide.matches.asSequence().sortedBy { it.programme.start }.forEach { entry ->
        val available=(byId[entry.channelId].orEmpty()+byName[entry.channelId].orEmpty())
            .distinctBy { it.url }.sortedByDescending { footballChannelQuality(it.name) }
        if(available.isEmpty() || detectOtherSport(entry.programme)!=null) return@forEach
        val details=footballDetails(entry.programme.title,entry.programme.category,entry.programme.metadata,
            available.map { it.name },available.map { it.group }) ?: return@forEach
        val programme=entry.programme
        if(programme.end<=now-FOOTBALL_RETENTION_MS)return@forEach
        val teams=listOf(footballScoreTeamKey(details.home),footballScoreTeamKey(details.away)).sorted()
        val fixture=listOf(teams[0],teams[1],details.gender.name,details.ageGroup,
            if(footballExplicitReplay(programme))"replay" else "game").joinToString(":")
        val candidates=nearby.getOrPut(fixture) { mutableListOf() }
        candidates.removeAll { id -> result[id]?.let { programme.start-it.programme.start>90*60000L } ?: true }
        val old=candidates.asReversed().mapNotNull(result::get).firstOrNull { previous ->
            val compatible=footballCompetitionIdentity(previous.details)==footballCompetitionIdentity(details) ||
                !isCompetitionLabel(previous.details.competition) || !isCompetitionLabel(details.competition)
            compatible && abs(previous.programme.start-programme.start)<=90*60000L &&
                previous.programme.start<programme.end && programme.start<previous.broadcastEnd &&
                available.none { channel -> previous.channelProgrammes[channel.url]?.let { it!=programme }==true }
        }
        val id=old?.id ?: fixture+":"+programme.start
        val retainKnown=old?.let { isCompetitionLabel(it.details.competition) }==true
        result[id]=FootballMatch(id,if(retainKnown)old!!.programme else programme,
            (old?.channels.orEmpty()+available).distinctBy { it.url }
                .sortedWith(compareByDescending<Channel> { footballChannelQuality(it.name) }.thenBy { it.name }),
            if(retainKnown)old!!.details else details,
            old?.channelProgrammes.orEmpty()+available.associate { it.url to programme })
        if(old==null)candidates.add(id)
    }
    return result.values.sortedWith(compareBy<FootballMatch> { it.programme.start }.thenBy { it.programme.title })
}

/** Extra EPG fields count only if they explicitly say replay; a final score alone is insufficient. */
internal fun footballExplicitReplay(p:Programme):Boolean =
    p.broadcast==BroadcastHint.REPLAY ||
        (p.broadcast!=BroadcastHint.LIVE && listOf(p.title,p.category,p.metadata.originalTitle,p.metadata.subtitle)
            .any { programmeBroadcastHint(it,"")==BroadcastHint.REPLAY })

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
    // Blank IDs preserve ambiguous aliases in the existing guide cache format.
    private val aliases = names.entries.groupBy({ guideChannelName(it.key) }, { it.value }).mapValues { (_, ids) ->
        ids.distinct().singleOrNull()?.takeIf(String::isNotBlank)
    }
    private val identifiers = (programmes.keys+matches.map {it.channelId}).groupBy {it.trim().lowercase(Locale.ROOT)}
    fun channelId(channel: Channel): String? = if (channel.tvgId.isNotBlank())
        identifiers[channel.tvgId.trim().lowercase(Locale.ROOT)]?.distinct()?.singleOrNull() ?: channel.tvgId
        else aliases[guideChannelName(channel.name).takeIf(String::isNotBlank)]
    fun forChannel(channel: Channel): List<Programme> = if (channel.kind != MediaKind.LIVE) emptyList()
        else programmes[channelId(channel)].orEmpty()
}

fun xmltvTime(value: String): Long = runCatching {
    val parts = value.trim().split(Regex("\\s+"))
    val digits = parts.first()
    val pattern = if (digits.length == 12) "yyyyMMddHHmm" else "yyyyMMddHHmmss"
    val zone = parts.getOrNull(1) ?: "+0000"
    SimpleDateFormat("$pattern Z", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        .parse("$digits $zone")?.time ?: 0L
}.getOrDefault(0L)

