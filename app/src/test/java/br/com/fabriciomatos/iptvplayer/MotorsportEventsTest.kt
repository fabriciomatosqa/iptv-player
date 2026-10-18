package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class MotorsportEventsTest {
    private val start=1800000000000L
    private fun programme(title:String,competition:String="")=Programme(title,start,start+3600000L,
        metadata=FootballMetadata(competition=competition,originalTitle=title))

    @Test fun brazilianGrandPrixSeparatesPracticeQualifyingSprintAndRace() {
        val one=motorsportCardDetails(programme("F1 - GP de São Paulo - Treino Livre 1"),"Fórmula 1")
        val two=motorsportCardDetails(programme("F1 - GP de São Paulo - Treino Livre 2"),"Fórmula 1")
        val quali=motorsportCardDetails(programme("F1 - GP de São Paulo - Classificação"),"Fórmula 1")
        val sprint=motorsportCardDetails(programme("F1 - GP de São Paulo - Corrida Sprint"),"Fórmula 1")
        val sprintQ=motorsportCardDetails(programme("F1 - GP de São Paulo - Sprint Qualifying"),"Fórmula 1")
        val race=motorsportCardDetails(programme("F1 - GP de São Paulo - Corrida"),"Fórmula 1")
        assertEquals("GP de São Paulo",one.stage)
        assertEquals("Treino Livre 1",one.session)
        assertEquals("Treino Livre 2",two.session)
        assertEquals("Classificação",quali.session)
        assertEquals("Corrida Sprint",sprint.session)
        assertEquals("Classificação Sprint",sprintQ.session)
        assertEquals("Corrida",race.session)
        assertEquals(6,setOf(one.sessionId,two.sessionId,quali.sessionId,sprint.sessionId,sprintQ.sessionId,race.sessionId).size)
    }

    @Test fun equivalentGrandPrixNamesShareIdentityButSeparateSessions() {
        val a=programme("Fórmula 1 - Grande Prêmio do Brasil - Corrida")
        val b=programme("F1: GP do Brasil - Race")
        val c=programme("F1: GP do Brasil - Classificação")
        assertEquals(motorsportFixtureKey(a,"f1"),motorsportFixtureKey(b,"f1"))
        assertNotEquals(motorsportFixtureKey(a,"f1"),motorsportFixtureKey(c,"f1"))
    }

    @Test fun unknownSessionsDoNotInventRaceResults() {
        val card=motorsportCardDetails(programme("Fórmula 1 - Grande Prêmio do Brasil"),"Fórmula 1")
        assertEquals("GP do Brasil",card.stage)
        assertEquals("Sessão não informada",card.session)
        assertFalse(card.session=="Corrida")
    }

    @Test fun supportsOtherMotorsportLeaguesWithoutFalseTeams() {
        val values=listOf(
            "Fórmula E: GP de Mônaco" to "Fórmula E",
            "WEC - 24 Horas de Le Mans - Corrida" to "WEC — Endurance",
            "NASCAR Cup Series - Corrida" to "NASCAR",
            "Moto2 - GP da Espanha - Classificação" to "Moto2"
        )
        values.forEachIndexed { index,(title,expected) ->
            val programme=programme(title)
            assertEquals(SportKind.MOTORSPORT,detectOtherSport(programme))
            assertTrue(sportsProgrammeCandidate(programme))
            assertEquals(expected,sportsCompetition(programme,SportKind.MOTORSPORT).second)
        }
    }

    @Test fun standalonePracticeNeedsSportContextAndNoHeadToHead() {
        assertFalse(sportsProgrammeCandidate(programme("Treino Livre 2")))
        val timed=programme("Treino Livre 2","Fórmula 1")
        assertTrue(sportsProgrammeCandidate(timed))
        assertEquals("Treino Livre 2",motorsportCardDetails(timed,"Fórmula 1").session)
        val repeat=timed.copy(broadcast=BroadcastHint.REPLAY)
        assertNotEquals(motorsportFixtureKey(timed,"f1"),motorsportFixtureKey(repeat,"f1"))
    }
}
