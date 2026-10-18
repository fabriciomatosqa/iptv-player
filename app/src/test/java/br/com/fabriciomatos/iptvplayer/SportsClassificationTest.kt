package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class SportsClassificationTest {
    private val now=1800000000000L
    private fun match(id:String,title:String,category:String="",sport:String=""):GuideMatch =
        GuideMatch(id,Programme(title,now,now+7200000L,category=category,
            metadata=FootballMetadata(sport=sport)))
    private fun channel(id:String,name:String="ESPN HD")=Channel(name,
        "https://example.test/"+id,"ESPORTES",tvgId=id)
    @Test fun soccerAndOtherSportsNeverCrossCategories() {
        val football=match("f","Everton x Chelsea - Premier League, a divisão de elite do futebol masculino de clubes na Inglaterra")
        val nfl=match("n","Philadelphia Eagles x Carolina Panthers - liga profissional de futebol americano dos EUA")
        val mlb=match("b","Philadelphia Phillies x Arizona Diamondbacks - MLB")
        val basketball=match("a","Lakers x Warriors - NBA")
        val hockey=match("h","Rangers x Penguins - NHL")
        val guide=Guide(matches=listOf(football,nfl,mlb,basketball,hockey))
        val channels=listOf(channel("f"),channel("n"),channel("b"),channel("a"),channel("h"))
        assertEquals("Premier League — Inglaterra",footballCompetitionTitle(
            footballDetails(football.programme.title)!!))
        assertEquals(1,footballMatches(guide,channels,now).size)
        val other=sportsEvents(guide,channels,now)
        assertEquals(4,other.size)
        assertEquals(setOf(SportKind.AMERICAN_FOOTBALL,SportKind.BASEBALL,SportKind.BASKETBALL,SportKind.HOCKEY),
            other.map {it.sport}.toSet())
        assertEquals(setOf("NFL","MLB","NBA","NHL"),other.map {it.competition}.toSet())
        assertTrue(sportsCategories(other,SportKind.FOOTBALL).none {it.title=="MLB"})
    }
    @Test fun articleDescriptionsAndUnknownLabelsNeverBecomeGiantTabs() {
        val nfl=match("n","Eagles x Panthers - liga profissional de futebol americano dos EUA")
        val mlb=match("b","Philadelphia Phillies x Arizona Diamondbacks - MLB",
            "Liga Nacional, os Phillies recebem os D-backs no Citizens Bank Park, na Filadélfia, EUA")
        assertEquals("NFL",sportsCompetition(nfl.programme,SportKind.AMERICAN_FOOTBALL).second)
        assertEquals("MLB",sportsCompetition(mlb.programme,SportKind.BASEBALL).second)
        assertEquals("Premier League — Inglaterra",
            footballCompetitionTitle(FootballDetails(
                "Premier League, a divisão de elite do futebol masculino de clubes na Inglaterra",
                "Everton","Chelsea")))
    }
    @Test fun newLeagueGetsOneStableCategoryAndGamesKeepAllStreams() {
        val a=match("a","Team Alpha x Team Beta - Campeonato Japonês de Basquete",
            "Campeonato Japonês de Basquete")
        val b=match("b","Team Alpha x Team Beta - Campeonato Japonês de Basquete",
            "Campeonato Japonês de Basquete")
        val result=sportsEvents(Guide(matches=listOf(a,b)),
            listOf(channel("a","ESPN FHD"),channel("b","ESPN HD")),now)
        assertEquals(1,result.size)
        assertEquals(2,result.single().channels.size)
        val categories=sportsCategories(result,SportKind.BASKETBALL)
        assertEquals(3,categories.size)
        assertEquals("Campeonato Japonês de Basquete",categories.last().title)
    }
    @Test fun motorsportEventsDoNotRequireFictitiousTeams() {
        val f1=match("f","Fórmula 1 - Grande Prêmio do Brasil",sport="Automobilismo")
        val wrc=match("r","WRC Rally de Portugal",sport="Automobilismo")
        val f2=match("d","F2 GP de Monza",sport="Automobilismo")
        val events=sportsEvents(Guide(matches=listOf(f1,wrc,f2)),
            listOf(channel("f"),channel("r"),channel("d")),now)
        assertEquals(3,events.size)
        assertTrue(events.all {it.home.isEmpty() && it.away.isEmpty()})
        assertEquals(listOf("Fórmula 1","Fórmula 2","Mundial de Rally — WRC"),
            sportsCategories(events,SportKind.MOTORSPORT).drop(2).map{it.title})
    }
    @Test fun sportAndLiveStatusAreIndependentAndNoClubsDetermineGender() {
        val p=match("l","Celtics x Knicks - NBA")
        val event=sportsEvents(Guide(matches=listOf(p)),listOf(channel("l")),now).single()
        assertTrue(sportsFilter(listOf(event),SportKind.BASKETBALL,SPORTS_LIVE,"",now).isEmpty())
        assertEquals(1,sportsFilter(listOf(event),SportKind.BASKETBALL,SPORTS_GAMES,"Celtics",now).size)
        assertTrue(sportsFilter(listOf(event),SportKind.BASEBALL,SPORTS_GAMES,"",now).isEmpty())
        assertEquals(FootballGender.WOMEN,
            footballDetails("Libertadores Feminina - Belgrano x Palmeiras")!!.gender)
        assertEquals(SportKind.AMERICAN_FOOTBALL,
            detectOtherSport(match("f","Eagles x Panthers",sport="Futebol Americano").programme))
    }
    @Test fun unclearOrNonSportTitlesStayOutOfTheCatalog() {
        val movie=match("x","Spy x Family",category="Anime")
        val news=match("b","Debate sobre os destaques da NBA")
        assertFalse(sportsProgrammeCandidate(news.programme))
        assertNull(detectOtherSport(movie.programme))
        assertTrue(sportsEvents(Guide(matches=listOf(movie,news)),
            listOf(channel("x"),channel("b")),now).isEmpty())
    }
}
