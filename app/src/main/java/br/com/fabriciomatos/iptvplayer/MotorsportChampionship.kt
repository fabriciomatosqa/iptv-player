package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Category-neutral presentation models. Rules and providers remain specific to each category. */
internal data class ChampionshipStanding(
    val id: String, val position: Int, val name: String, val team: String,
    val points: Double, val wins: Int?, val podiums: Int? = null
)
internal data class ChampionshipSession(val id: String, val name: String, val date: String, val start: Long?)
internal data class ChampionshipRace(
    val round: Int, val name: String, val country: String, val circuit: String,
    val date: String, val start: Long?, val sessions: List<ChampionshipSession>,
    val completed: Boolean = false
)
internal data class ChampionshipSeason(
    val competitionId: String = "f1", val name: String = "FIA Formula One World Championship",
    val year: Int, val races: List<ChampionshipRace> = emptyList(),
    val drivers: List<ChampionshipStanding> = emptyList(),
    val teams: List<ChampionshipStanding> = emptyList(),
    val standingsRound: Int? = null, val checked: Long = 0, val partial: Boolean = false
) {
    fun nextRace(now: Long): ChampionshipRace? = races.firstOrNull {
        !it.completed && (it.start?.let { time -> time >= now } ?: (it.date >= championshipToday(now)))
    }
}

internal fun championshipToday(now: Long): String = SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date(now))

/** A date without a time stays a date; never invent midnight or convert it across time zones. */
internal fun championshipStart(date: String, time: String): Long? {
    if(!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) ||
        !Regex("\\d{2}:\\d{2}:\\d{2}Z").matches(time)) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT).apply {
            isLenient=false; timeZone=TimeZone.getTimeZone("UTC")
        }.parse("${date}T$time")?.time
    }.getOrNull()
}

internal fun championshipGap(row: ChampionshipStanding, leader: ChampionshipStanding?): Double? =
    leader?.takeIf { it.position==1 }?.let { (it.points-row.points).coerceAtLeast(0.0) }

internal fun championshipRaceStatus(race: ChampionshipRace, now: Long): String = when {
    race.completed -> "Finalizada · resultado disponível"
    race.start?.let { it > now } ?: (race.date > championshipToday(now)) -> "Programada"
    else -> "Situação não informada"
}

/** Link only existing, non-replay F1 sessions near the published session time. No channels are created. */
internal fun championshipBroadcasts(race: ChampionshipRace, events: List<SportsEvent>): List<SportsEvent> =
    events.filter { event ->
        event.sport==SportKind.MOTORSPORT && event.competitionId=="f1" &&
            event.programme.broadcast!=BroadcastHint.REPLAY && race.sessions.any { session ->
                session.start?.let { start ->
                    kotlin.math.abs(event.programme.start-start)<=45*60000L &&
                        motorsportCardDetails(event.programme,event.competition).sessionId==session.id
                }==true
            }
    }.distinctBy { it.id }
