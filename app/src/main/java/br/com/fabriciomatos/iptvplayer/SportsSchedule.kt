package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val SPORTS_LOOKAHEAD_MS = 7 * 86400000L

internal fun sportsWindowContains(p: Programme, now: Long): Boolean =
    p.start > 0 && p.end > p.start && p.end-p.start<=48*3600000L && p.end > now - FOOTBALL_RETENTION_MS &&
        p.start < now + SPORTS_LOOKAHEAD_MS

/** Resolve once per guide, using the same conservative ID/alias rules as live TV. */
class SportsChannelIndex(guide: Guide, channels: List<Channel>) {
    private val linked = channels.asSequence().filter { it.kind == MediaKind.LIVE && !it.adult }
        .mapNotNull { channel -> guide.channelId(channel)?.let { it to channel } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, values) ->
            values.distinctBy { it.url }.sortedWith(
                compareByDescending<Channel> { footballChannelQuality(it.name) }.thenBy { it.name })
        }
    operator fun get(id: String): List<Channel> = linked[id].orEmpty()
}

internal data class SportsSchedule(val football: List<FootballMatch>, val other: List<SportsEvent>)

internal fun buildSportsSchedule(guide: Guide, channels: List<Channel>, now: Long): SportsSchedule {
    val index = SportsChannelIndex(guide, channels)
    val linked = guide.copy(matches = guide.matches.filter {
        sportsWindowContains(it.programme, now) && index[it.channelId].isNotEmpty()
    }.sortedBy { it.programme.start })
    return SportsSchedule(footballMatches(linked, channels, now, index), sportsEvents(linked, channels, now, index))
}

enum class SportsBroadcastState { LIVE, UPCOMING, REPLAY, ENDED, SCHEDULED, POSTPONED, CANCELLED }

/** A result is complementary evidence. It cannot move, extend or create an EPG slot. */
fun sportsBroadcastState(p: Programme, now: Long, score: FootballScore? = null): SportsBroadcastState = when {
    footballExplicitReplay(p) -> SportsBroadcastState.REPLAY
    now >= p.end -> SportsBroadcastState.ENDED
    score?.state == MatchState.POSTPONED -> SportsBroadcastState.POSTPONED
    score?.state == MatchState.CANCELLED -> SportsBroadcastState.CANCELLED
    now < p.start -> SportsBroadcastState.UPCOMING
    transmissionIsLive(p, score, now) -> SportsBroadcastState.LIVE
    else -> SportsBroadcastState.SCHEDULED
}

fun SportsEvent.programmeFor(channel: Channel): Programme = channelProgrammes[channel.url] ?: programme
val SportsEvent.broadcastEnd: Long get() = channelProgrammes.values.maxOfOrNull { it.end } ?: programme.end
fun sportsLiveChannels(event: SportsEvent, now: Long): List<Channel> =
    event.channels.filter { sportsBroadcastState(event.programmeFor(it), now) == SportsBroadcastState.LIVE }
fun SportsEvent.displayProgramme(now:Long):Programme =
    sportsLiveChannels(this,now).firstOrNull()?.let(::programmeFor)
        ?: channelProgrammes.values.firstOrNull {now>=it.start && now<it.end}
        ?: channelProgrammes.values.filter {it.start>now}.minByOrNull {it.start}
        ?: channelProgrammes.values.maxByOrNull {it.end} ?: programme

/** Indexed presentation data is built on Default, not while composing or scrolling. */
data class SportsHubEvent(val football: FootballMatch? = null, val other: SportsEvent? = null) {
    init { require((football == null) != (other == null)) }
    val id = football?.let { "football:${it.id}" } ?: "sport:${other!!.id}"
    val sport = if (football != null) SportKind.FOOTBALL else other!!.sport
    val sportName = if (football != null) SportKind.FOOTBALL.displayName else other!!.disciplineName
    val sportId = if (sport == SportKind.OTHER) "other:${normalized(sportName)}" else sport.name
    val competitionId = football?.let { footballCompetitionIdentity(it.details).ifBlank { "football:other" } }
        ?: other!!.competitionId
    val competition = football?.let {
        val base = if(isCompetitionValue(it.details.competition))footballCompetitionTitle(it.details)
            else "Campeonato não informado"
        val gender = if (it.details.gender == FootballGender.WOMEN && footballGender(base) != FootballGender.WOMEN)
            " · Feminino" else if (it.details.gender == FootballGender.MEN && footballGender(base) != FootballGender.MEN)
            " · Masculino" else ""
        base + gender + it.details.ageGroup.takeIf { age -> age.isNotBlank() && !normalized(base).contains(normalized(age)) }
            ?.let { age -> " · $age" }.orEmpty()
    } ?: other!!.competition
    val programme = football?.programme ?: other!!.programme
    val end = football?.broadcastEnd ?: other!!.broadcastEnd
    val channels = football?.channels ?: other!!.channels
    val name = football?.let { "${it.details.home} × ${it.details.away}" } ?: other!!.name
    val stage = other?.takeIf { it.sport == SportKind.MOTORSPORT }?.let {
        motorsportCardDetails(it.programme, it.competition)
    }
    val searchKey = normalized(listOf(sportName, competition, name, stage?.stage.orEmpty(), stage?.session.orEmpty(),
        channels.joinToString(" ") { it.name }).joinToString(" "))
    fun live(now: Long, score: FootballScore? = null) = football?.let {
        footballHasLiveTransmission(it, score, now)
    } ?: sportsLiveChannels(other!!, now).isNotEmpty()
}

data class SportsHubCompetition(val id: String, val title: String, val events: List<SportsHubEvent>,val liveCount:Int=0)
data class SportsHubDiscipline(val id: String, val sport: SportKind, val title: String,
    val competitions: List<SportsHubCompetition>) {
    val count: Int = competitions.sumOf { it.events.size }
    val liveCount: Int = competitions.sumOf {it.liveCount}
}
data class SportsHubCatalog(val disciplines: List<SportsHubDiscipline> = emptyList())

sealed interface SportsHubRow {
    val key:String
    data class Day(override val key:String,val start:Long):SportsHubRow
    data class Stage(override val key:String,val title:String):SportsHubRow
    data class Event(val event:SportsHubEvent):SportsHubRow { override val key=event.id }
}

/** One row index, with only visible rows composed by LazyColumn. */
fun sportsHubRows(events:List<SportsHubEvent>,zone:TimeZone):List<SportsHubRow> {
    val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply {timeZone=zone}
    var previousDay="";var previousStage:String?=null
    return buildList {
        events.forEachIndexed { index,event ->
            val day=format.format(Date(event.programme.start))
            if(day!=previousDay) {
                add(SportsHubRow.Day("day:$day",event.programme.start));previousStage=null;previousDay=day
            }
            event.stage?.let { stage ->
                if(stage.stageId!=previousStage)add(SportsHubRow.Stage("stage:$day:$index",stage.stage))
                previousStage=stage.stageId
            }
            add(SportsHubRow.Event(event))
        }
    }
}

fun sportsHubCatalog(football: List<FootballMatch>, other: List<SportsEvent>, now: Long): SportsHubCatalog {
    val events = (football.asSequence().map { SportsHubEvent(football = it) } +
        other.asSequence().map { SportsHubEvent(other = it) })
        .filter { it.channels.isNotEmpty() && sportsWindowContains(it.programme, now) && it.end > now - FOOTBALL_RETENTION_MS }
        .sortedWith(compareBy<SportsHubEvent> { it.programme.start }.thenBy { it.name }).toList()
    return SportsHubCatalog(events.groupBy { it.sportId }.map { (id, items) ->
        SportsHubDiscipline(id, items.first().sport, items.first().sportName,
            items.groupBy { it.competitionId }.map { (competitionId, entries) ->
                SportsHubCompetition(competitionId, entries.first().competition, entries)
            }.sortedWith(compareBy<SportsHubCompetition> {
                if (items.first().sport == SportKind.FOOTBALL)
                    footballCategoryPriority(it.events.first().football!!.details).let { p -> p.group * 10000 + p.priority }
                else sportsCompetitionRules.firstOrNull { rule -> rule.id == it.id.substringBefore(':') }?.priority ?: 1000
            }.thenBy { normalized(it.title) }))
    }.sortedWith(compareBy<SportsHubDiscipline> { it.sport.ordinal }.thenBy { it.title }))
}

enum class SportsHubFilter { ALL, LIVE, UPCOMING }
fun sportsHubEvents(events: List<SportsHubEvent>, search: String, filter: SportsHubFilter,
    now: Long, scores: Map<String, MatchedScore> = emptyMap()): List<SportsHubEvent> {
    val words = normalized(search).split(Regex("\\s+")).filter(String::isNotBlank)
    return events.filter { event ->
        event.end > now - FOOTBALL_RETENTION_MS && words.all(event.searchKey::contains) && when (filter) {
            SportsHubFilter.ALL -> true
            SportsHubFilter.LIVE -> event.live(now, event.football?.let { scores[it.id]?.event })
            SportsHubFilter.UPCOMING -> event.programme.start > now
        }
    }
}

fun sportsHubProjection(catalog:SportsHubCatalog,search:String,filter:SportsHubFilter,now:Long,
    scores:Map<String,MatchedScore>,disciplineId:String?=null,competitionId:String?=null):SportsHubCatalog =
    SportsHubCatalog(catalog.disciplines.filter {disciplineId==null || it.id==disciplineId}.mapNotNull { sport ->
    val competitions=sport.competitions.filter {competitionId==null || it.id==competitionId}.mapNotNull { competition ->
        val events=sportsHubEvents(competition.events,search,filter,now,scores)
        if(events.isEmpty())null else competition.copy(events=events,
            liveCount=events.count {it.live(now,it.football?.let {game->scores[game.id]?.event})})
    }
    if(competitions.isEmpty())null else sport.copy(competitions=competitions)
})

fun sportsDateKey(value: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(value))

fun sportsDayHeading(value: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat(if (locale.language == "pt") "EEEE, d 'de' MMMM 'de' yyyy" else "EEEE, d MMMM yyyy", locale)
        .apply { timeZone = zone }.format(Date(value)).uppercase(locale)

fun sportsDisplayZone(value:String):TimeZone =
    if(value=="device")TimeZone.getDefault() else TimeZone.getTimeZone(value)

internal fun sportsStateResource(state:SportsBroadcastState):Int=when(state) {
    SportsBroadcastState.LIVE->R.string.live_score
    SportsBroadcastState.UPCOMING->R.string.sports_upcoming
    SportsBroadcastState.REPLAY->R.string.replay
    SportsBroadcastState.ENDED->R.string.sports_ended
    SportsBroadcastState.SCHEDULED->R.string.sports_on_schedule
    SportsBroadcastState.POSTPONED->R.string.postponed
    SportsBroadcastState.CANCELLED->R.string.match_cancelled
}
