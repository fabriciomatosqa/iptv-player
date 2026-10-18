package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

const val FOOTBALL_LIVE = "__live__"
const val FOOTBALL_GAMES = "__games__"
const val FOOTBALL_FIFA = "__fifa__"
const val FOOTBALL_BRAZIL = "__brazil__"
const val FOOTBALL_LIBERTADORES = "__libertadores__"
const val FOOTBALL_WOMEN = "__women__"
// Retain the source constant for callers upgrading from the former competition-specific tab.
const val FOOTBALL_LIBERTADORES_WOMEN = FOOTBALL_WOMEN
fun canonicalFootballTab(key: String) = if(key == "__libertadores_women__") FOOTBALL_WOMEN else key
const val FOOTBALL_SUDAMERICANA = "__sudamericana__"
const val FOOTBALL_REFRESH_MS = 120000L
const val FOOTBALL_RETENTION_MS = 3600000L

enum class MatchState { SCHEDULED, LIVE, FINISHED, HALFTIME, POSTPONED, CANCELLED, UNKNOWN }
enum class BroadcastMode { LIVE, REPLAY, UNCONFIRMED }
data class ScoreTeam(val id: String, val names: List<String>, val badge: String = "")
data class FootballScore(val id: String, val league: String, val competition: String, val kickoff: Long,
    val home: ScoreTeam, val away: ScoreTeam, val homeGoals: Int?, val awayGoals: Int?, val state: MatchState,
    val clock: String = "", val checked: Long = 0L, val badge: String = "") {
    // Derived once when decoding the source, rather than for every card/score candidate.
    val gender: FootballGender = footballGender(competition)
}
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
    if(details.gender == FootballGender.CONFLICT || details.ageGroup.isNotBlank()) return emptyList()
    if(nationalMatch(details)!=null || Regex("\\b(amistoso internacional|eliminatorias|copa do mundo|world cup|eurocopa|copa america)\\b").containsMatchIn(name)) {
        // Youth events must never be matched to the senior men's scoreboard.
        if(Regex("\\b(?:sub[- ]?|u|under[- ]?)\\d{2}\\b").containsMatchIn(normalized("${details.competition} ${details.home} ${details.away}")))return emptyList()
        val women=details.gender == FootballGender.WOMEN
        return when {
            name.contains("eliminatorias") -> if(women)emptyList() else qualifierLeague(details)?.let(::listOf) ?: emptyList()
            name.contains("copa do mundo") || name.contains("world cup") -> listOf(if(women)"fifa.wwc"else"fifa.world")
            name.contains("amistoso") || name.contains("friendly") -> listOf(if(women)"fifa.friendly.w"else"fifa.friendly")
            name.contains("eurocopa") || name.contains("euro 20") -> listOf(if(women)"uefa.weuro"else"uefa.euro")
            name.contains("copa america") -> listOf(if(women)"conmebol.america.femenina"else"conmebol.america")
            else -> emptyList()
        }
    }
    if(details.gender == FootballGender.WOMEN)return emptyList()
    return footballCompetitionRule(details)?.scoreLeague?.let(::listOf) ?: emptyList()
}
fun footballTabKey(details: FootballDetails): String {
    if(details.gender == FootballGender.WOMEN) return FOOTBALL_WOMEN
    if(nationalMatch(details)!=null || Regex("\\b(fifa|amistoso internacional|eliminatorias|copa do mundo|world cup|eurocopa|copa america)\\b").containsMatchIn(normalized(details.competition))) return FOOTBALL_FIFA
    if(details.ageGroup.isNotBlank()) return footballCompetitionIdentity(details)
    return when(footballCompetitionRule(details)?.id) {
        "bra.1" -> FOOTBALL_BRAZIL
        "conmebol.libertadores" -> FOOTBALL_LIBERTADORES
        "conmebol.sudamericana" -> FOOTBALL_SUDAMERICANA
        else -> footballCompetitionIdentity(details)
    }
}
fun footballTabs(matches: List<FootballMatch>, priorities: Map<String, FootballCategoryPriority> = emptyMap()): List<FootballTab> {
    val categories = matches.filter { isCompetitionValue(it.details.competition) || it.details.gender == FootballGender.WOMEN || nationalMatch(it.details)!=null }
        .groupBy { footballTabKey(it.details) }.map { (key,games) ->
            val details = games.map { it.details }.minBy { normalized(footballCompetitionTitle(it)) }
            val title = when(key) {
                FOOTBALL_WOMEN -> "Jogos Femininos"
                FOOTBALL_FIFA -> "FIFA / Seleções"
                else -> footballCompetitionTitle(details)
            }
            val priority = priorities[key] ?: when(key) {
                FOOTBALL_WOMEN -> FootballCategoryPriority(2,10)
                FOOTBALL_FIFA -> FootballCategoryPriority(2,20)
                else -> footballCategoryPriority(details)
            }
            Triple(FootballTab(key,title),priority,normalized(title))
        }.sortedWith(compareBy<Triple<FootballTab,FootballCategoryPriority,String>> { it.second.group }
            .thenBy { it.second.priority }.thenBy { it.third }.thenBy { it.first.key }).map { it.first }
    return listOf(FootballTab(FOOTBALL_LIVE),FootballTab(FOOTBALL_GAMES)) + categories
}
fun filterFootball(matches: List<FootballMatch>, tab: String, search: String, now: Long, feed: FootballFeed = FootballFeed()): List<FootballMatch> {
    val words=normalized(search).split(Regex("\\s+")).filter(String::isNotBlank)
    return matches.filter { match ->
        // Cheap filters first: most games do not need a scoreboard lookup during scrolling.
        if (tab != FOOTBALL_GAMES && tab != FOOTBALL_LIVE && footballTabKey(match.details) != canonicalFootballTab(tab)) return@filter false
        if (words.isNotEmpty()) {
            val teams = normalized("${match.details.home} ${match.details.away}")
            if (!words.all(teams::contains)) return@filter false
        }
        if (tab != FOOTBALL_LIVE && match.broadcastEnd > now - FOOTBALL_RETENTION_MS) return@filter true
        val live = footballHasLiveTransmission(match, matchFootballScore(match,feed)?.event, now)
        (match.broadcastEnd > now - FOOTBALL_RETENTION_MS || live) &&
            (tab != FOOTBALL_LIVE || live)
    }.map { match ->
        if(tab!=FOOTBALL_LIVE || match.channels.isEmpty())match else {
            val channels=footballLiveChannels(match,matchFootballScore(match,feed)?.event,now)
            if(channels.size==match.channels.size)match else match.copy(
                programme=match.programmeFor(channels.first()),channels=channels,
                channelProgrammes=match.channelProgrammes.filterKeys { url -> channels.any { it.url==url } })
        }
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
        // Providers (and older caches) may assign a men's league ID to a women's title.
        // Original sports metadata remains authoritative even when that ID looks compatible.
        val gender=event.gender
        if(gender == FootballGender.CONFLICT || gender == FootballGender.WOMEN && match.details.gender != FootballGender.WOMEN ||
            gender == FootballGender.MEN && match.details.gender == FootballGender.WOMEN) return@filter false
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
/** Finished fixtures and repeats stay in the catalog but use a reduced card until expanded. */
fun compactFootballMatch(match: FootballMatch, score: FootballScore?, now: Long): Boolean {
    if(broadcastMode(match.programme,score)==BroadcastMode.REPLAY)return true
    return match.broadcastEnd<=now || score?.state==MatchState.FINISHED && match.programme.start<=now
}
fun broadcastMode(programme: Programme, score: FootballScore?): BroadcastMode {
    if(programme.broadcast==BroadcastHint.REPLAY)return BroadcastMode.REPLAY
    if(programme.broadcast==BroadcastHint.LIVE)return BroadcastMode.LIVE
    if(score!=null && score.state in listOf(MatchState.LIVE,MatchState.HALFTIME) &&
        score.kickoff in (programme.start-15*60000L)..minOf(programme.end-1,programme.start+90*60000L))return BroadcastMode.LIVE
    return BroadcastMode.UNCONFIRMED
}
fun scoreIsFresh(score: FootballScore, now: Long) = score.state !in listOf(MatchState.LIVE,MatchState.HALFTIME) ||
    (score.checked>0L && now-score.checked in 0..2*FOOTBALL_REFRESH_MS)
fun footballRefreshDue(checked: Long, now: Long) = checked==0L || now-checked>=FOOTBALL_REFRESH_MS || now<checked
