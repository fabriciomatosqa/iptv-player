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

    @Test fun genericEpgClubFixturesRecoverOfficialBrazilianLeagueOnlyWithExactScoreEvidence() {
        val start=now+3600000L
        val channels=listOf(
            Channel("SPORTV 4K","https://provider.example.test/live/sportv-4k","Esportes",tvgId="sportv"),
            Channel("SPORTV FHD","https://provider.example.test/live/sportv-fhd","Esportes",tvgId="sportv"))
        fun match(home:String,away:String,id:String)=FootballMatch(
            id,Programme("$home x $away",start,start+3600000L,category="Futebol"),
            channels,FootballDetails("",home,away))
        val serieA=match("Flamengo","Grêmio","a")
        val serieB=match("Vila Nova","Novorizontino","b")
        val outside=match("Flamengo","Palmeiras","c")
        fun score(id:String,league:String,home:String,away:String)=FootballScore(
            id,league,if(league=="bra.1")"Brasileirão" else "Brasileirão Série B",
            start,ScoreTeam("1",listOf(home)),ScoreTeam("2",listOf(away)),
            null,null,MatchState.SCHEDULED,checked=now)
        val feed=FootballFeed(listOf(
            score("a","bra.1","Grêmio","Flamengo"),
            score("b","bra.2","Vila Nova","Novorizontino")),now)
        val result=enrichBrazilianFootballCompetitions(listOf(serieA,serieB,outside),feed)
        assertEquals("Brasileirão",result[0].details.competition)
        assertEquals("Brasileirão",enrichBrazilianFootballCompetitions(
            listOf(serieA.copy(details=serieA.details.copy(country="Brasil"))),feed
        ).single().details.competition)
        assertEquals(FOOTBALL_BRAZIL,footballTabKey(result[0].details))
        assertEquals(listOf("bra.1"),footballScoreLeagues(result[0].details))
        assertEquals(2,result[0].channels.size)
        assertEquals(serieA.programme,result[0].programme)
        assertEquals("Brasileirão — Série B",result[1].details.competition)
        assertEquals("bra.2",footballTabKey(result[1].details))
        assertEquals("",result[2].details.competition)
        assertEquals(2,footballTabs(result).count {it.key=="__games__" || it.key=="__live__"})
        assertTrue(footballTabs(result).any {it.key==FOOTBALL_BRAZIL})
        assertTrue(footballTabs(result).any {it.key=="bra.2"})
        assertNotNull(matchFootballScore(result[0],feed))
        assertEquals(listOf(serieA,serieB,outside),enrichBrazilianFootballCompetitions(listOf(serieA,serieB,outside),FootballFeed()))
    }

    @Test fun scoreLeagueInferenceRejectsContradictionsWrongDatesAndReplays() {
        val start=now+3600000L
        val channel=Channel("ESPN 4 HD","https://provider.test/live/4","Esportes",tvgId="espn4")
        fun match(details:FootballDetails=FootballDetails("","Flamengo","Grêmio"),
            programme:Programme=Programme("Flamengo x Grêmio",start,start+3600000L,category="Futebol")) =
            FootballMatch("fixture",programme,listOf(channel),details)
        fun score(league:String,kickoff:Long=start,home:String="Flamengo",away:String="Grêmio") =
            FootballScore(league,league,"Campeonato Brasileiro",kickoff,
                ScoreTeam("1",listOf(home)),ScoreTeam("2",listOf(away)),null,null,
                MatchState.SCHEDULED,checked=now)
        val regular=score("bra.1")
        val original=match()
        fun inferred(m:FootballMatch, feed:FootballFeed) =
            enrichBrazilianFootballCompetitions(listOf(m),feed).single()
        assertEquals("",inferred(original,FootballFeed(listOf(score("bra.1",start+2*3600000L)),now)).details.competition)
        assertEquals("",inferred(original,FootballFeed(listOf(score("bra.1",home="Flamengo",away="Botafogo")),now)).details.competition)
        assertEquals("",inferred(original,FootballFeed(listOf(regular,score("bra.2")),now)).details.competition)
        assertEquals("Copa do Brasil",inferred(match(details=FootballDetails("Copa do Brasil","Flamengo","Grêmio")),
            FootballFeed(listOf(regular),now)).details.competition)
        assertEquals("",inferred(match(programme=original.programme.copy(broadcast=BroadcastHint.REPLAY)),
            FootballFeed(listOf(regular),now)).details.competition)
        assertEquals("",inferred(match(details=FootballDetails("","Flamengo","Grêmio",gender=FootballGender.WOMEN)),
            FootballFeed(listOf(regular),now)).details.competition)
        assertEquals("",inferred(original.copy(channels=emptyList()),FootballFeed(listOf(regular),now)).details.competition)
        assertEquals("",inferred(original,FootballFeed(listOf(regular.copy(state=MatchState.POSTPONED)),now)).details.competition)
    }

    @Test fun confirmedFinalFromHoursEarlierMarksFutureRebroadcast() {
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
        assertTrue(scoreIsFresh(live,now+60000L));assertFalse(scoreIsFresh(live,now+240001L))
        assertFalse(footballRefreshDue(now,now+119999L));assertTrue(footballRefreshDue(now,now+120000L))
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
    @Test fun liveTabOnlyKeepsLiveMatchesAndExcludesReplays() {
        val running=game("Campeonato Brasileiro","Fluminense","Botafogo",now-500000L,BroadcastHint.LIVE)
        val replay=game("Campeonato Brasileiro","Vasco","Flamengo",now-500000L,BroadcastHint.REPLAY)
        val planned=game("Premier League","Chelsea","Arsenal",now+3600000L)
        val feed=FootballFeed(listOf(score("Fluminense","Botafogo",running.programme.start,MatchState.LIVE,"bra.1")),now)
        assertEquals(listOf(running),filterFootball(listOf(running,replay,planned),FOOTBALL_LIVE,"",now,feed))
        assertEquals(listOf(running),filterFootball(listOf(running,replay,planned),FOOTBALL_LIVE,"Fluminense",now,feed))
        assertTrue(filterFootball(listOf(replay,planned),FOOTBALL_LIVE,"",now,feed).isEmpty())
        assertEquals(3,filterFootball(listOf(running,replay,planned),FOOTBALL_GAMES,"",now,feed).size)
    }
    @Test fun replaysAndFinishedGamesAreCompactButActiveGamesRemainFullSize() {
        val live=game("Campeonato Brasileiro","Fluminense","Botafogo",now-40000L,BroadcastHint.LIVE)
        val replay=game("Campeonato Brasileiro","Fluminense","Botafogo",now,BroadcastHint.REPLAY)
        assertTrue(compactFootballMatch(replay,null,now))
        assertTrue(compactFootballMatch(live,score("Fluminense","Botafogo",live.programme.start,MatchState.FINISHED,"bra.1"),now))
        assertFalse(compactFootballMatch(live,score("Fluminense","Botafogo",live.programme.start,MatchState.LIVE,"bra.1"),now))
    }
    @Test fun refreshRunsEveryTwoMinutes() {
        assertEquals(120000L, FOOTBALL_REFRESH_MS)
        assertFalse(footballRefreshDue(now,now+119999L))
        assertTrue(footballRefreshDue(now,now+120000L))
    }
    @Test fun womensLibertadoresHasAnIndependentTabAndNeverMatchesMensLeague() {
        val women=game("Libertadores Feminina","Belgrano","Palmeiras")
        val men=game("Copa Libertadores","Fluminense","River Plate")
        assertEquals(FOOTBALL_LIBERTADORES_WOMEN,footballTabKey(women.details))
        assertEquals(FOOTBALL_LIBERTADORES,footballTabKey(men.details))
        assertEquals(listOf(women),filterFootball(listOf(women,men),FOOTBALL_LIBERTADORES_WOMEN,"",now))
        assertEquals(listOf(men),filterFootball(listOf(women,men),FOOTBALL_LIBERTADORES,"",now))
        assertTrue(footballScoreLeagues(women.details).isEmpty())
    }
    @Test fun epgTeamNamesDoNotIncludeCompetitionPrefixes() {
        assertEquals(FootballDetails("Brasileirão","Fluminense","Santos"),
            footballDetails("Brasileirão - Fluminense x Santos"))
        assertEquals(FootballDetails("Libertadores Feminina","Belgrano","Palmeiras"),
            footballDetails("Libertadores Feminina - Belgrano x Palmeiras"))
        assertEquals(FootballDetails("Campeonato Brasileiro - Série B","Sport","Fortaleza"),
            footballDetails("Sport x Fortaleza - Campeonato Brasileiro - Série B"))
        assertEquals(FootballDetails("Campeonato Brasileiro","Botafogo-SP","Ceará"),
            footballDetails("Botafogo-SP x Ceará - Campeonato Brasileiro"))
        assertNull(footballDetails("Brasileirão x Santos"))
        assertEquals(FootballDetails("Copa Libertadores da América","Fluminense","Palmeiras"),
            footballDetails("Semifinal, Jogo de Ida: Fluminense x Palmeiras - Copa Libertadores da América"))
    }
    @Test fun competitionTabsHaveTheRequestedOrderAndTeamSearchDoesNotMatchOtherGames() {
        val matches=listOf(game("Premier League","Chelsea","Arsenal"),game("Campeonato Brasileiro","Fluminense","Botafogo"),
            game("Copa Libertadores","Fluminense","River Plate"),game("Amistoso Feminino","Brasil","Argentina"),
            game("Copa Sul-Americana","Santos","Independiente"),game("Campeonato Italiano","Milan","Sassuolo"))
        val tabs=footballTabs(matches)
        assertEquals(listOf(FOOTBALL_LIVE,FOOTBALL_GAMES,FOOTBALL_BRAZIL,FOOTBALL_LIBERTADORES,FOOTBALL_SUDAMERICANA,FOOTBALL_WOMEN),tabs.take(6).map {it.key})
        assertEquals(listOf("Premier League — Inglaterra","Campeonato Italiano — Serie A"),tabs.drop(6).map {it.title})
        assertEquals(2,filterFootball(matches,FOOTBALL_GAMES,"fluminense",now).size)
        assertEquals(1,filterFootball(matches,FOOTBALL_BRAZIL,"Fluminénse",now).size)
        assertTrue(filterFootball(matches,FOOTBALL_FIFA,"Fluminense",now).isEmpty())
    }

    @Test fun explicitEpgSubtitleOrVerifiedFinishedScoreMarksReplay() {
        val slot=Programme("Sport x Fortaleza",now,now+2*3600000L,
            metadata=FootballMetadata(subtitle="Reprise da partida"))
        assertEquals(BroadcastMode.REPLAY,broadcastMode(slot,null))
        assertEquals(BroadcastMode.REPLAY,broadcastMode(slot.copy(metadata=FootballMetadata()),score()))
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(slot.copy(start=score().kickoff,metadata=FootballMetadata()),score()))
    }

}
