package br.com.fabriciomatos.iptvplayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun footballDay(value: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String {
    fun formatted(pattern: String) = SimpleDateFormat(pattern,locale).apply {timeZone=zone}.format(Date(value))
    val weekday=formatted("EEEE").split('-').joinToString("-") {it.replaceFirstChar {c->c.titlecase(locale)}}
    val date=if(locale.language=="pt")formatted("d")+" de "+formatted("MMMM").replaceFirstChar {it.titlecase(locale)}
        else formatted("d MMMM")
    return "$weekday - $date"
}
fun footballTime(value: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()) =
    SimpleDateFormat("HH:mm",locale).apply {timeZone=zone}.format(Date(value))

/** A match clock is evidence only inside this channel's original, compatible EPG interval. */
fun transmissionIsLive(programme: Programme, score: FootballScore?, now: Long): Boolean {
    if(now < programme.start || now >= programme.end || programme.broadcast==BroadcastHint.REPLAY)return false
    if(score==null)return programme.broadcast==BroadcastHint.LIVE
    if(score.state !in listOf(MatchState.LIVE,MatchState.HALFTIME) || !scoreIsFresh(score,now))return false
    return score.kickoff in (programme.start-15*60000L)..minOf(programme.end-1,programme.start+90*60000L)
}
fun footballLiveChannels(match: FootballMatch, score: FootballScore?, now: Long): List<Channel> =
    match.channels.filter { transmissionIsLive(match.programmeFor(it),score,now) }
fun footballHasLiveTransmission(match: FootballMatch, score: FootballScore?, now: Long): Boolean =
    if(match.channels.isEmpty())transmissionIsLive(match.programme,score,now)
    else footballLiveChannels(match,score,now).isNotEmpty()
