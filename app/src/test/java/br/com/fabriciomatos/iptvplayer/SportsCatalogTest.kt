package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class SportsCatalogTest {
    private val now=1800000000000L
    private fun channel(name:String,id:String)=Channel(name,"https://example.test/live/"+id,"Esportes",tvgId=id)
    private fun programme(title:String,competition:String="",description:String="",sport:String="",start:Long=now,
        end:Long=now+2*3600000L)=Programme(title,start,end,category="Esportes",
        metadata=FootballMetadata(competition=competition,description=description,sport=sport))

    @Test fun longPremierLeagueDescriptionIsNotALeagueAndMergesWithOfficialAlias() {
        val verbose="Premier League, a divisão de elite do futebol masculino de clubes na Inglaterra"
        val a=FootballDetails(verbose,"Everton","Chelsea")
        val b=FootballDetails("Premier League","Everton","Chelsea")
        assertEquals("eng.1",footballTabKey(a))
        assertEquals(footballTabKey(b),footballTabKey(a))
        assertEquals("Premier League — Inglaterra",footballCompetitionTitle(a))
        assertEquals("Premier League — Inglaterra",footballTabs(listOf(
            FootballMatch("a",programme("Everton x Chelsea",verbose),emptyList(),a),
            FootballMatch("b",programme("Everton x Chelsea","Premier League"),emptyList(),b)
        )).last().title)
    }

    @Test fun baseballEditorialDescriptionBecomesMlbAndNotAFakeFootballLeague() {
        val description="Liga Nacional, os Phillies recebem os D-backs no Citizens Bank Park, na Filadélfia, EUA"
        val p=programme("Philadelphia Phillies x Arizona Diamondbacks - MLB",description=description)
        assertEquals(SportKind.BASEBALL,programmeSport(p))
        assertEquals("baseball.mlb",sportCompetition(p,SportKind.BASEBALL).id)
        assertNull(footballDetails(p.title,p.category,p.metadata))
        assertFalse(footballCompetitionRule(description)!=null)
    }

    @Test fun nationalBaseballLeagueIsShortAndDoesNotIncludeCommentary() {
        val p=programme("Philadelphia Phillies x Arizona Diamondbacks",
            description="Liga Nacional, os Phillies recebem os D-backs no Citizens Bank Park")
        assertEquals(SportKind.BASEBALL,programmeSport(p))
        assertEquals(SportLeague("baseball.mlb.nl","MLB — Liga Nacional",11),
            sportCompetition(p,SportKind.BASEBALL))
    }

    @Test fun americanFootballIsNeverSoccerEvenThoughItContainsWordFutebol() {
        val p=programme("Philadelphia Eagles x Carolina Panthers",
            description="liga profissional de futebol americano dos EUA")
        assertEquals(SportKind.AMERICAN_FOOTBALL,programmeSport(p))
        assertEquals("NFL",sportCompetition(p,SportKind.AMERICAN_FOOTBALL).title)
        assertNull(footballDetails(p.title,p.category,p.metadata))
        val explicit=programme("New England Patriots x Carolina Panthers",sport="Futebol americano")
        assertEquals(SportKind.AMERICAN_FOOTBALL,programmeSport(explicit))
    }

    @Test fun nbaNbbEuroleagueHockeyAndMotorsportsAreSeparate() {
        val samples=listOf(
            Triple("Los Angeles Lakers x Boston Celtics - NBA",SportKind.BASKETBALL,"basketball.nba"),
            Triple("Franca x Flamengo - NBB",SportKind.BASKETBALL,"basketball.nbb"),
            Triple("Real Madrid x Olympiacos - EuroLeague",SportKind.BASKETBALL,"basketball.euro"),
            Triple("New York Rangers x Toronto Maple Leafs - NHL",SportKind.HOCKEY,"hockey.nhl"),
            Triple("Fórmula 1 Grande Prêmio do Brasil",SportKind.MOTORSPORT,"motor.f1"),
            Triple("Fórmula 2 - Grande Prêmio",SportKind.MOTORSPORT,"motor.f2"),
            Triple("Fórmula 3 - Grande Prêmio",SportKind.MOTORSPORT,"motor.f3"),
            Triple("Campeonato Mundial de Rally",SportKind.MOTORSPORT,"motor.wrc"),
            Triple("Stock Car - Etapa de Interlagos",SportKind.MOTORSPORT,"motor.stock")
        )
        for((name,kind,league) in samples) {
            val p=programme(name)
            assertEquals(name,kind,programmeSport(p))
            assertEquals(name,league,sportCompetition(p,kind).id)
            assertTrue(name,isSportsProgramme(p))
        }
    }

    @Test fun motorsportWithoutVersusStillAppearsAndNeverGetsAFakeScore() {
        val p=programme("Fórmula 1 - GP de São Paulo")
        val guide=Guide(matches=listOf(GuideMatch("f1",p)))
        val channels=listOf(channel("SPORTV 4K","f1"))
        val event=sportsEvents(guide,channels,now).single()
        assertEquals(SportKind.MOTORSPORT,event.sport)
        assertEquals("",event.home)
        assertEquals("",event.away)
        assertEquals("Fórmula 1",event.league.title)
        assertTrue(footballMatches(guide,channels,now).isEmpty())
    }

    @Test fun soccerWomensGamesStayWithinFootballAndAreNotDuplicatedInOtherSports() {
        val p=programme("Libertadores Feminina - Belgrano x Palmeiras")
        val guide=Guide(matches=listOf(GuideMatch("f",p)))
        val channels=listOf(channel("CANAL UOL HD","f"))
        val games=footballMatches(guide,channels,now)
        assertEquals(1,games.size)
        assertEquals(FOOTBALL_WOMEN,footballTabKey(games.single().details))
        assertTrue(sportsEvents(guide,channels,now).isEmpty())
    }

    @Test fun deduplicateDifferentBroadcastChannelsWithoutLosingAnyVariants() {
        val a=programme("Los Angeles Lakers x Boston Celtics - NBA")
        val b=a.copy(start=now+5*60000L,end=now+2*3600000L+5*60000L)
        val channels=listOf(channel("ESPN 1 FHD","a"),channel("ESPN 1 HD","b"))
        val events=sportsEvents(Guide(matches=listOf(GuideMatch("a",a),GuideMatch("b",b))),channels,now)
        assertEquals(1,events.size)
        assertEquals(2,events.single().channels.size)
        assertEquals("basketball.nba",events.single().league.id)
    }

    @Test fun twoLevelNavigationFiltersSportThenLeagueAndKeepsStableOrder() {
        val programs=listOf(
            "Los Angeles Lakers x Boston Celtics - NBA" to "a",
            "Franca x Flamengo - NBB" to "b",
            "Philadelphia Eagles x Carolina Panthers - NFL" to "c",
            "Toronto Maple Leafs x Boston Bruins - NHL" to "d")
        val guide=Guide(matches=programs.map { (name,id)->GuideMatch(id,programme(name)) })
        val channels=programs.map { (_,id)->channel("ESPN "+id,id) }
        val events=sportsEvents(guide,channels,now)
        assertEquals(4,events.size)
        val basketball=sportLeagues(events,SportKind.BASKETBALL)
        assertEquals(listOf(SPORTS_ALL,"basketball.nba","basketball.nbb"),basketball.map {it.id})
        assertEquals(1,filterSportEvents(events,SportKind.BASKETBALL,"basketball.nba","Lakers",now).size)
        assertEquals(1,filterSportEvents(events,SportKind.AMERICAN_FOOTBALL,SPORTS_ALL,"Eagles",now).size)
        assertEquals(basketball,sportLeagues(events.reversed(),SportKind.BASKETBALL))
        assertTrue(filterSportEvents(events,SportKind.HOCKEY,"basketball.nba","",now).isEmpty())
    }

    @Test fun unknownCompetitionFallsBackToShortLeagueAndRejectsLongProse() {
        val p=programme("Aurora x Norte",competition="Campeonato Lituano de Basquete",sport="Basquete")
        assertEquals("Campeonato Lituano de Basquete",sportCompetition(p,SportKind.BASKETBALL).title)
        val messy=p.copy(metadata=p.metadata.copy(competition="Liga Nacional, os atletas recebem as visitas na Arena"))
        assertEquals("Liga Nacional",sportCompetition(messy,SportKind.BASKETBALL).title)
        val misleading=p.copy(metadata=p.metadata.copy(competition="Os atletas recebem as visitas em um estádio"))
        assertEquals("Outras competições",sportCompetition(misleading,SportKind.BASKETBALL).title)
    }

    @Test fun noEventWithoutSpecificSportOrRealTeamsAndNoPollutionFromEditorial() {
        val unknown=programme("Conversa com esportistas sobre a temporada")
        assertNull(programmeSport(unknown))
        assertFalse(isSportsProgramme(unknown))
        val news=programme("Melhores momentos da NBA")
        assertFalse(isSportsProgramme(news))
        val fixtures=Guide(matches=listOf(GuideMatch("sports",unknown),GuideMatch("news",news)))
        assertTrue(sportsEvents(fixtures,listOf(channel("ESPN HD","sports"),channel("ESPN 2","news")),now).isEmpty())
    }
}
