package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class FootballSerieATest {
    private val now=1802350800000L
    private fun score(id:String,home:String,away:String,kickoff:Long,
        league:String="bra.1",competition:String="Brasileirão",state:MatchState=MatchState.SCHEDULED) =
        FootballScore(id,league,competition,kickoff,
            ScoreTeam("home-"+id,listOf(home)),ScoreTeam("away-"+id,listOf(away)),
            if(state==MatchState.FINISHED)2 else null,
            if(state==MatchState.FINISHED)1 else null,state,checked=now)

    @Test fun dedicatedSerieATabExistsWithoutEpgOrAnyChannel() {
        assertEquals(listOf(FOOTBALL_LIVE,FOOTBALL_GAMES,FOOTBALL_BRAZIL),
            footballVisibleTabs(emptyList()).map { it.key })
        val all=FootballFeed(listOf(
            score("one","Flamengo","Grêmio",now+3600000L),
            score("two","Palmeiras","Santos",now+2*3600000L),
            score("three","Corinthians","Botafogo",now-3*86400000L, state=MatchState.FINISHED)),now)
        val series=footballListings(emptyList(),all,FOOTBALL_BRAZIL,"",now)
        assertEquals(3,series.size)
        assertTrue(series.all { it.broadcast==null && it.fixture!=null })
        assertEquals(3,footballListings(emptyList(),all,FOOTBALL_GAMES,"",now).size)
        assertEquals(listOf("one","two","three"),series.map { it.fixture!!.id })
        assertEquals(1,footballListings(emptyList(),all,FOOTBALL_BRAZIL,"Santos",now).size)
        assertEquals(0,footballListings(emptyList(),all,FOOTBALL_BRAZIL,"Barcelona",now).size)
        assertEquals(0,footballListings(emptyList(),all,FOOTBALL_LIVE,"",now).size)
    }

    @Test fun complete380MatchSeasonIsNotLimitedToEpgOrCurrentWeek() {
        val season=FootballFeed((0 until 380).map { index ->
            score("season-"+index,"Home "+index,"Away "+index,
                now + index*86400000L/5)
        },now)
        assertEquals(380,brazilianSerieAFixtures(season).size)
        val matches=footballListings(emptyList(),season,FOOTBALL_BRAZIL,"",now)
        assertEquals(380,matches.size)
        assertTrue(matches.all {it.broadcast==null && it.fixture!=null})
    }

    @Test fun originalBroadcastRetainsEveryChannelAndUnlinkedFixturesStillAppear() {
        val first=score("fixture-a","Flamengo","Grêmio",now+3600000L)
        val next=score("fixture-b","Santos","Palmeiras",now+2*3600000L)
        val channels=listOf(Channel("SPORTV FHD","https://stream.example.test/a","Esportes"),
            Channel("Premiere 4K","https://stream.example.test/b","Esportes"))
        val epg=FootballMatch("live-a",
            Programme("Flamengo x Grêmio - Campeonato Brasileiro",first.kickoff,
                first.kickoff+2*3600000L),channels,
            FootballDetails("Campeonato Brasileiro","Flamengo","Grêmio"))
        val listings=footballListings(listOf(epg),FootballFeed(listOf(first,next),now),
            FOOTBALL_BRAZIL,"",now)
        assertEquals(2,listings.size)
        assertEquals(1,listings.count {it.broadcast!=null})
        assertEquals(1,listings.count {it.fixture!=null})
        assertEquals(channels,listings.first {it.broadcast!=null}.broadcast!!.channels)
        assertEquals("fixture-b",listings.first {it.fixture!=null}.fixture!!.id)
    }

    @Test fun broadcastsDoNotMergeWithReplaysOrOtherLeaguesOrWrongKickoff() {
        val first=score("a","Flamengo","Grêmio",now+3600000L)
        val channel=Channel("SPORTV","https://stream.example.test/a","Esportes")
        fun programme(start:Long,hint:BroadcastHint=BroadcastHint.UNKNOWN) =
            FootballMatch("broadcast",Programme("Flamengo x Grêmio - Campeonato Brasileiro",
                start,start+2*3600000L,broadcast=hint),
                listOf(channel),FootballDetails("Brasileirão","Flamengo","Grêmio"))
        for(variant in listOf(programme(first.kickoff+2*3600000L),programme(first.kickoff,BroadcastHint.REPLAY))) {
            val results=footballListings(listOf(variant),FootballFeed(listOf(first),now),
                FOOTBALL_BRAZIL,"",now)
            assertEquals("An EPG replay/wrong time cannot hide the calendar fixture",2,results.size)
        }
        assertEquals(1,footballListings(listOf(programme(first.kickoff)),FootballFeed(listOf(first),now),
            FOOTBALL_BRAZIL,"",now).size)
    }

    @Test fun onlyActualMensSerieAFixturesAppearAndNothingDisappearsOnFiltering() {
        val fixtures=listOf(
            score("a","Flamengo","Grêmio",now+3600000L),
            score("b","Vila Nova","Criciúma",now+3600000L,"bra.2","Campeonato Brasileiro Série B"),
            score("c","Brasil","Argentina",now+3600000L,"fifa.friendly","Amistoso"),
            score("d","Palmeiras","Santos",now+3600000L,"bra.1","Brasileirão Feminino"),
            score("e","Flamengo","Grêmio",now+3600000L,"bra.1","Copa do Brasil"))
        val feed=FootballFeed(fixtures,now)
        assertEquals(listOf("a"),brazilianSerieAFixtures(feed).map {it.id})
        val original=FootballMatch("other",Programme("Chelsea x Arsenal - Premier League",
            now,now+2*3600000L),listOf(Channel("ESPN","https://stream.example.test/1","Esportes")),
            FootballDetails("Premier League","Chelsea","Arsenal"))
        val listed=footballListings(listOf(original),feed,FOOTBALL_GAMES,"",now)
        assertEquals(2,listed.size)
        assertNotNull(listed.firstOrNull {it.broadcast?.id=="other"})
    }

    @Test fun duplicatePublicSourcesAreOneGameWithRealScoreAndTeamBadges() {
        val kick=now+3600000L
        val espn=score("espn","CR Flamengo","Grêmio",kick,
            state=MatchState.SCHEDULED).copy(
            home=ScoreTeam("one",listOf("CR Flamengo","Flamengo"),"https://a.espncdn.com/escudo-flamengo.png"),
            away=ScoreTeam("two",listOf("Grêmio"),"https://a.espncdn.com/escudo-gremio.png"))
        val fotmob=score("fotmob:27","Flamengo","Grêmio",kick+60000L,
            state=MatchState.LIVE).copy(checked=now+FOOTBALL_REFRESH_MS,homeGoals=2,awayGoals=1)
        val merged=brazilianSerieAFixtures(FootballFeed(listOf(espn,fotmob),now)).single()
        assertEquals("fotmob:27",merged.id)
        assertEquals(MatchState.LIVE,merged.state)
        assertEquals(2,merged.homeGoals)
        assertEquals("https://a.espncdn.com/escudo-flamengo.png",merged.home.badge)
        assertEquals("https://a.espncdn.com/escudo-gremio.png",merged.away.badge)
        assertEquals(2,brazilianSerieAFixtures(FootballFeed(listOf(
            espn,espn.copy(id="later",kickoff=kick+86400000L)),now)).size)
    }
}
