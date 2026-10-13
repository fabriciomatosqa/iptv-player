package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

const val FOOTBALL_GAMES = "__games__"
const val FOOTBALL_FIFA = "__fifa__"
const val FOOTBALL_BRAZIL = "__brazil__"
const val FOOTBALL_LIBERTADORES = "__libertadores__"
const val FOOTBALL_SUDAMERICANA = "__sudamericana__"
const val FOOTBALL_REFRESH_MS = 60000L
const val FOOTBALL_RETENTION_MS = 3600000L

enum class MatchState { SCHEDULED, LIVE, FINISHED, HALFTIME, POSTPONED, CANCELLED, UNKNOWN }
enum class BroadcastMode { LIVE, REPLAY, UNCONFIRMED }
data class ScoreTeam(val id: String, val names: List<String>, val badge: String = "")
data class FootballScore(val id: String, val league: String, val competition: String, val kickoff: Long,
    val home: ScoreTeam, val away: ScoreTeam, val homeGoals: Int?, val awayGoals: Int?, val state: MatchState,
    val clock: String = "", val checked: Long = 0L, val badge: String = "")
data class FootballFeed(val events: List<FootballScore> = emptyList(), val checked: Long = 0L, val failed: Boolean = false)
data class MatchedScore(val event: FootballScore, val home: ScoreTeam, val away: ScoreTeam, val homeGoals: Int?, val awayGoals: Int?)
data class FootballTab(val key: String, val title: String = "")

private fun qualifierLeague(details: FootballDetails): String? {
    val name=normalized(details.competition)
    val country=nationalMatch(details)?.first?.country
    return when {
        name.contains("conmebol") || name.contains("sul american") || country in setOf("Brazil","Argentina","Uruguay","Colombia","Chile","Paraguay","Ecuador","Peru","Bolivia","Venezuela")->"fifa.worldq.conmebol"
        name.contains("concacaf") || country in setOf("USA","Canada","Mexico","Costa Rica","Panama","Jamaica","Honduras")->"fifa.worldq.concacaf"
        name.contains("afc") || country in setOf("Australia","Japan","South Korea","China","Iran","Saudi Arabia","Qatar","United Arab Emirates","Iraq","Jordan","Uzbekistan","Indonesia","Thailand","Vietnam","Malaysia")->"fifa.worldq.afc"
        name.contains("caf") || country in setOf("Morocco","Egypt","Senegal","Ghana","Nigeria","Cameroon","South Africa","Ivory Coast","Algeria","Tunisia","Angola","Cape Verde")->"fifa.worldq.caf"
        name.contains("ofc") || country=="New Zealand"->"fifa.worldq.ofc"
        name.contains("uefa") || name.contains("europe") || country!=null->"fifa.worldq.uefa"
        else->null
    }
}

fun footballScoreLeagues(details: FootballDetails): List<String> {
    val name=normalized(details.competition)
    val teams=normalized("${details.competition} ${details.home} ${details.away}")
    if(nationalMatch(details)!=null || Regex("\\b(amistoso internacional|eliminatorias|copa do mundo|eurocopa|copa america)\\b").containsMatchIn(name)) {
        // Youth events must never be matched to the senior men's scoreboard.
        if(Regex("\\b(?:sub[- ]?|u|under[- ]?)\\d{2}\\b").containsMatchIn(normalized("${details.competition} ${details.home} ${details.away}")))return emptyList()
        val women=teams.contains("feminin") || teams.contains("women")
        return when {
            name.contains("eliminatorias") -> if(women)emptyList() else qualifierLeague(details)?.let(::listOf) ?: emptyList()
            name.contains("copa do mundo") -> listOf(if(women)"fifa.wwc"else"fifa.world")
            name.contains("amistoso") || name.contains("friendly") -> listOf(if(women)"fifa.friendly.w"else"fifa.friendly")
            name.contains("eurocopa") || name.contains("euro 20") -> listOf(if(women)"uefa.weuro"else"uefa.euro")
            name.contains("copa america") -> listOf(if(women)"conmebol.america.femenina"else"conmebol.america")
            else -> emptyList()
        }
    }
    if(name.contains("feminin") || name.contains("women"))return emptyList()
    return footballLeagueSlug(details.competition)?.let(::listOf) ?: emptyList()
}
fun footballTabKey(details: FootballDetails): String {
    val league=footballLeagueSlug(details.competition)
    if(nationalMatch(details)!=null || Regex("\\b(fifa|amistoso internacional|eliminatorias|copa do mundo|eurocopa|copa america)\\b").containsMatchIn(normalized(details.competition)))return FOOTBALL_FIFA
    return when(league) {
        "bra.1","bra.2" -> FOOTBALL_BRAZIL
        "conmebol.libertadores" -> FOOTBALL_LIBERTADORES
        "conmebol.sudamericana" -> FOOTBALL_SUDAMERICANA
        else -> league ?: normalized(details.competition).ifBlank {"other"}
    }
}
fun footballTabs(matches: List<FootballMatch>): List<FootballTab> {
    val leading=listOf(FOOTBALL_GAMES,FOOTBALL_FIFA,FOOTBALL_BRAZIL,FOOTBALL_LIBERTADORES,FOOTBALL_SUDAMERICANA)
    return leading.map(::FootballTab)+matches.map {FootballTab(footballTabKey(it.details),it.details.competition)}
        .filterNot {it.key in leading}.distinctBy {it.key}.sortedBy {normalized(it.title)}
}
fun filterFootball(matches: List<FootballMatch>, tab: String, search: String, now: Long, feed: FootballFeed = FootballFeed()): List<FootballMatch> {
    val words=normalized(search).split(Regex("\\s+")).filter(String::isNotBlank)
    return matches.filter {match ->
        val teams=normalized("${match.details.home} ${match.details.away}")
        (match.programme.end>now-FOOTBALL_RETENTION_MS || transmissionIsLive(match.programme,matchFootballScore(match,feed)?.event,now)) &&
            (tab==FOOTBALL_GAMES || footballTabKey(match.details)==tab) && words.all {teams.contains(it)}
    }
}
fun footballScoreTeamKey(name: String): String {
    nationalTeam(name)?.let {return "country:"+it.country.lowercase()}
    val key=teamKey(name).replace(Regex("^(?:fk|cf|ec|cr|ac)\\s+|\\s+(?:fk|cf|ec|cr|ac)$"),"")
        .replace(Regex("^(?:clube de regatas|clube atletico)\\s+"),"").replace(" ","").trim()
    return when(key) {
        "neftchifargona"->"neftchifergana"
        "sportingcp","sportinglisbon","sportingclubeportugal","sportingclubedeportugal"->"sporting"
        else->key
    }
}
private fun matchesScoreTeam(name: String, team: ScoreTeam): Boolean = team.names.any {footballScoreTeamKey(it)==footballScoreTeamKey(name)}

/** Exact teams, a compatible competition and a bounded date window; never guess from a partial name. */
fun matchFootballScore(match: FootballMatch, feed: FootballFeed): MatchedScore? {
    val leagues=footballScoreLeagues(match.details)
    val compatible=feed.events.filter {event ->
        event.league in leagues && event.kickoff in (match.programme.start-7*86400000L)..(match.programme.start+90*60000L) &&
            ((matchesScoreTeam(match.details.home,event.home) && matchesScoreTeam(match.details.away,event.away)) ||
                (matchesScoreTeam(match.details.home,event.away) && matchesScoreTeam(match.details.away,event.home))) &&
            (match.programme.broadcast!=BroadcastHint.REPLAY || event.state==MatchState.FINISHED && event.kickoff<=match.programme.start)
    }.distinctBy {it.league+":"+it.id}
    // Prefer the new source without treating two providers' IDs as two different matches.
    val preferred=compatible.filter {it.id.startsWith("fotmob:") && scoreIsFresh(it,feed.checked)}
    val candidates=(preferred.ifEmpty {compatible.filterNot {it.id.startsWith("fotmob:")}.ifEmpty {compatible}})
        .sortedBy {abs(it.kickoff-match.programme.start)}
    val chosen=candidates.firstOrNull() ?: return null
    // Two different games at nearly the same time require better metadata, not an arbitrary score.
    if(candidates.getOrNull(1)?.let {abs(abs(it.kickoff-match.programme.start)-abs(chosen.kickoff-match.programme.start))<30*60000L}==true)return null
    return if(matchesScoreTeam(match.details.home,chosen.home))MatchedScore(chosen,chosen.home,chosen.away,chosen.homeGoals,chosen.awayGoals)
        else MatchedScore(chosen,chosen.away,chosen.home,chosen.awayGoals,chosen.homeGoals)
}
fun broadcastMode(programme: Programme, score: FootballScore?): BroadcastMode {
    if(programme.broadcast==BroadcastHint.REPLAY)return BroadcastMode.REPLAY
    if(score?.state==MatchState.FINISHED && programme.start-score.kickoff>45*60000L)return BroadcastMode.REPLAY
    if(programme.broadcast==BroadcastHint.LIVE)return BroadcastMode.LIVE
    if(score!=null && score.state !in listOf(MatchState.CANCELLED,MatchState.POSTPONED,MatchState.UNKNOWN) &&
        score.kickoff in (programme.start-15*60000L)..(programme.start+90*60000L))return BroadcastMode.LIVE
    return BroadcastMode.UNCONFIRMED
}
fun scoreIsFresh(score: FootballScore, now: Long) = score.state !in listOf(MatchState.LIVE,MatchState.HALFTIME) ||
    (score.checked>0L && now-score.checked in 0..2*FOOTBALL_REFRESH_MS)
fun footballRefreshDue(checked: Long, now: Long) = checked==0L || now-checked>=FOOTBALL_REFRESH_MS || now<checked
