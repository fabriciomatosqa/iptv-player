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

fun transmissionIsLive(programme: Programme, score: FootballScore?, now: Long): Boolean {
    if(programme.start>now || broadcastMode(programme,score)==BroadcastMode.REPLAY)return false
    return if(score!=null)score.state in listOf(MatchState.LIVE,MatchState.HALFTIME) && scoreIsFresh(score,now)
        else programme.broadcast==BroadcastHint.LIVE && programme.end>now
}
