package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class FootballScoresTest {
    private val now=1802350800000L
    private fun game(comp: String, home: String, away: String, start: Long = now, hint: BroadcastHint = BroadcastHint.UNKNOWN) =
        FootballMatch("$comp:$home:$away:$start",Programme("$home x $away - $comp",start,start+3*3600000L,broadcast=hint),
            listOf(Channel("TV Esportes","https://example.test/live/1","Esportes")),FootballDetails(comp,home,away))
    private fun score(home: String = "Al Nassr", away: String = "Neftchi Fergana", kickoff: Long = now-4*3600000L,
        state: MatchState = MatchState.FINISHED, league: String = "afc.champions") =
        FootballScore("1",league,"AFC Champions League Elite",kickoff,ScoreTeam("1",listOf(home)),ScoreTeam("2",listOf(away)),3,1,state,"83:21",now)

    @Test fun finishedGameBeforeTheTvSlotIsAReplayWithItsFinalResult() {
        val game=game("AFC Champions League Elite","Al Nassr","Neftchi Fergana",now+2*3600000L)
        val result=matchFootballScore(game,FootballFeed(listOf(score())))!!
        assertEquals(3,result.homeGoals);assertEquals(1,result.awayGoals)
        assertEquals(BroadcastMode.REPLAY,broadcastMode(game.programme,result.event))
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(game.programme,null))
    }
    @Test fun liveClockComesFromTheScoreSourceAndStaleScoresLoseLiveStatus() {
        val game=game("Campeonato Brasileiro","Fluminense","Botafogo",now-83*60000L)
        val live=score("Fluminense","Botafogo",game.programme.start,MatchState.LIVE,"bra.1")
        assertEquals("83:21",matchFootballScore(game,FootballFeed(listOf(live)))?.event?.clock)
        assertEquals(BroadcastMode.LIVE,broadcastMode(game.programme,live))
        assertTrue(scoreIsFresh(live,now+60000L));assertFalse(scoreIsFresh(live,now+120001L))
        assertFalse(footballRefreshDue(now,now+59999L));assertTrue(footballRefreshDue(now,now+60000L))
        assertTrue(footballRefreshDue(now,now-1))
    }
    @Test fun resultsRequireExactTeamsCompatibleCompetitionAndAnUnambiguousDate() {
        val game=game("AFC Champions League Elite","Al-Nassr","Neftchi Fergana")
        assertNotNull(matchFootballScore(game,FootballFeed(listOf(score()))))
        assertNull(matchFootballScore(game,FootballFeed(listOf(score(home="Al Hilal")))))
        assertNull(matchFootballScore(game,FootballFeed(listOf(score(league="eng.1")))))
        assertNull(matchFootballScore(game,FootballFeed(listOf(score(kickoff=now-9*86400000L)))))
        assertNull(matchFootballScore(game,FootballFeed(listOf(score(),score().copy(id="2",kickoff=now-4*3600000L+60000L)))))
        val reversed=matchFootballScore(game,FootballFeed(listOf(score(home="Neftchi Fergana",away="Al Nassr"))))!!
        assertEquals(1,reversed.homeGoals);assertEquals(3,reversed.awayGoals)
        assertTrue(footballScoreLeagues(FootballDetails("Campeonato Espanhol Feminino","Barcelona","Real Madrid")).isEmpty())
        assertTrue(footballScoreLeagues(FootballDetails("Amistoso Sub-20","Brasil","Argentina")).isEmpty())
    }
    @Test fun namedReplaysAreKeptButGenericHighlightsAndSportChannelsAreRejected() {
        assertEquals(FootballDetails("Campeonato Brasileiro","Fluminense","Botafogo"),footballDetails("Reprise: Fluminense x Botafogo - Campeonato Brasileiro"))
        assertEquals(BroadcastHint.REPLAY,programmeBroadcastHint("Brasil x Argentina","Futebol Reprise"))
        assertEquals(BroadcastHint.LIVE,programmeBroadcastHint("Brasil x Argentina (Ao vivo)","Futebol"))
        assertNull(footballDetails("Reprise: Futebol 24 horas"))
        assertNull(footballDetails("Melhores momentos: Fluminense x Botafogo - Campeonato Brasileiro"))
    }
    @Test fun competitionTabsHaveTheRequestedOrderAndTeamSearchDoesNotMatchOtherGames() {
        val matches=listOf(game("Premier League","Chelsea","Arsenal"),game("Campeonato Brasileiro","Fluminense","Botafogo"),
            game("Copa Libertadores","Fluminense","River Plate"),game("Amistoso Feminino","Brasil","Argentina"),
            game("Copa Sul-Americana","Santos","Independiente"),game("Campeonato Italiano","Milan","Sassuolo"))
        val tabs=footballTabs(matches)
        assertEquals(listOf(FOOTBALL_GAMES,FOOTBALL_FIFA,FOOTBALL_BRAZIL,FOOTBALL_LIBERTADORES,FOOTBALL_SUDAMERICANA),tabs.take(5).map {it.key})
        assertEquals(listOf("Campeonato Italiano","Premier League"),tabs.drop(5).map {it.title})
        assertEquals(2,filterFootball(matches,FOOTBALL_GAMES,"fluminense",now).size)
        assertEquals(1,filterFootball(matches,FOOTBALL_BRAZIL,"Fluminénse",now).size)
        assertTrue(filterFootball(matches,FOOTBALL_FIFA,"Fluminense",now).isEmpty())
    }
}
