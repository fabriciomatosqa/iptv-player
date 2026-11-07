package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

/** A scoreboard fixture is public information, not an IPTV broadcast. */
data class FootballListing(val broadcast: FootballMatch? = null, val fixture: FootballScore? = null) {
    val id: String get() = broadcast?.let { "epg:" + it.id } ?: "fixture:" + fixture!!.league + ":" + fixture.id
    val kickoff: Long get() = broadcast?.programme?.start ?: fixture!!.kickoff
}

private fun sameSerieATeams(first: ScoreTeam, second: ScoreTeam): Boolean {
    val names = first.names.map(::footballScoreTeamKey).filter(String::isNotBlank).toSet()
    return second.names.any { footballScoreTeamKey(it) in names }
}

private fun sameSerieAFixture(a: FootballScore, b: FootballScore): Boolean =
    abs(a.kickoff - b.kickoff) <= 90 * 60000L &&
        ((sameSerieATeams(a.home, b.home) && sameSerieATeams(a.away, b.away)) ||
            (sameSerieATeams(a.home, b.away) && sameSerieATeams(a.away, b.home)))

private fun scoreRank(event: FootballScore) = when(event.state) {
    MatchState.LIVE, MatchState.HALFTIME -> 5
    MatchState.FINISHED -> 4
    MatchState.POSTPONED, MatchState.CANCELLED -> 3
    MatchState.SCHEDULED -> 2
    MatchState.UNKNOWN -> 0
}

/**
 * ESPN provides a season-wide fixture list; FotMob updates current-day scores.
 * The two providers have different event IDs. Merge only verified same-team
 * fixtures at a compatible kickoff, preferring recent/confirmed state while
 * retaining a league/team crest if one source has no artwork.
 */
fun brazilianSerieAFixtures(feed: FootballFeed): List<FootballScore> {
    val events=feed.events.filter {
        it.league=="bra.1" && it.kickoff>0 &&
            (footballCompetitionRule(it.competition)?.id in listOf(null,"bra.1")) &&
            it.gender !in listOf(FootballGender.WOMEN,FootballGender.CONFLICT) &&
            it.home.names.isNotEmpty() && it.away.names.isNotEmpty() &&
            it.home.names.any { name -> footballScoreTeamKey(name).isNotBlank() } &&
            it.away.names.any { name -> footballScoreTeamKey(name).isNotBlank() }
    }
    val distinct=ArrayList<FootballScore>()
    for(event in events.sortedWith(compareBy<FootballScore> { it.kickoff }.thenBy { it.id })) {
        val index=distinct.indexOfFirst { sameSerieAFixture(it,event) }
        if(index<0) {
            distinct.add(event)
            continue
        }
        val previous=distinct[index]
        val preferred=listOf(previous,event).maxWith(
            compareBy<FootballScore> { scoreRank(it) }
                .thenBy { it.checked }
                .thenBy { if(it.homeGoals!=null && it.awayGoals!=null) 1 else 0 })
        val other=if(preferred===previous)event else previous
        // The opponent order in public score feeds can differ; never swap crests.
        val sameOrder=sameSerieATeams(preferred.home,other.home)
        val homeBackup=if(sameOrder)other.home.badge else other.away.badge
        val awayBackup=if(sameOrder)other.away.badge else other.home.badge
        distinct[index]=preferred.copy(
            home=preferred.home.copy(badge=preferred.home.badge.ifBlank { homeBackup }),
            away=preferred.away.copy(badge=preferred.away.badge.ifBlank { awayBackup }),
            badge=preferred.badge.ifBlank { other.badge })
    }
    return distinct
}

/** A verified season fixture can share an EPG card ONLY when the real times and both teams match. */
private fun linkedSerieAFixture(broadcast: FootballMatch, fixture: FootballScore): Boolean {
    if(broadcast.channels.isEmpty() ||
        footballTabKey(broadcast.details)!=FOOTBALL_BRAZIL ||
        footballExplicitReplay(broadcast.programme) ||
        broadcast.programme.broadcast==BroadcastHint.REPLAY ||
        abs(broadcast.programme.start-fixture.kickoff)>90*60000L)return false
    return matchFootballScore(broadcast,FootballFeed(listOf(fixture),fixture.checked))!=null
}

/**
 * Keep every original EPG match unchanged. Add any Serie A calendar fixture
 * that has no corresponding provider-linked card, without inventing streams.
 * Both "Jogos" and the dedicated Serie A tab can render these results.
 */
fun footballListings(
    broadcasts: List<FootballMatch>, feed: FootballFeed, tab: String,
    search: String, now: Long
): List<FootballListing> {
    val originals=filterFootball(broadcasts,tab,search,now,feed)
    val listings=originals.map { FootballListing(broadcast=it) }
    if(tab!=FOOTBALL_BRAZIL && tab!=FOOTBALL_GAMES)return listings
    val words=normalized(search).split(Regex("\\s+")).filter(String::isNotBlank)
    val added=brazilianSerieAFixtures(feed).asSequence().filter { fixture ->
        words.all { word -> normalized(fixture.home.names.joinToString(" ")+" "+
            fixture.away.names.joinToString(" ")).contains(word) } &&
            originals.none { linkedSerieAFixture(it,fixture) }
    }.map { FootballListing(fixture=it) }.toList()
    // Next fixtures first; older results remain accessible, newest to oldest.
    return (listings+added).sortedWith(compareBy<FootballListing> {
        if(it.kickoff>=now-2*3600000L) 0 else 1
    }.thenBy { if(it.kickoff>=now-2*3600000L)it.kickoff else -it.kickoff }.thenBy { it.id })
}

fun footballVisibleTabs(broadcasts: List<FootballMatch>): List<FootballTab> {
    val existing=footballTabs(broadcasts)
    val league=FootballTab(FOOTBALL_BRAZIL,"Brasileirão — Série A")
    return if(existing.any { it.key==FOOTBALL_BRAZIL })
        existing.map { if(it.key==FOOTBALL_BRAZIL) league else it }
    else existing.take(2)+league+existing.drop(2)
}
