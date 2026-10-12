package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class NationalTeamsTest {
    @Test fun countriesUseFederationsForMenWomenAndYouthWithoutMatchingClubNames() {
        assertEquals("Brazil",nationalTeam("Brasil")?.country)
        assertEquals("Brazil",nationalTeam("Seleção do Brasil Feminina Sub-20")?.country)
        assertEquals("Argentina",nationalTeam("Argentina U23")?.country)
        assertEquals("USA",nationalTeam("Estados Unidos")?.country)
        assertEquals("Spain",nationalTeam("Spain Women")?.country)
        assertNull(nationalTeam("Brasil de Pelotas"))
        assertNull(nationalTeam("Argentina Juniors"))
        assertNull(nationalMatch(FootballDetails("Campeonato Brasileiro","Brasil de Pelotas","Argentina")))
    }
    @Test fun internationalGamesShowFifaAndTheVerifiedCrestsImmediately() {
        val southAmerica=initialFootballArtwork(FootballDetails("Eliminatórias","Brasil","Argentina"))
        assertEquals(FIFA_BADGE,southAmerica.competition)
        assertTrue(southAmerica.home.endsWith("jl6dip1726167280.png"))
        assertTrue(southAmerica.away.endsWith("3zplhu1726167477.png"))
        val friendly=initialFootballArtwork(FootballDetails("Amistoso Internacional de Futebol Feminino","Estados Unidos","Espanha"))
        assertEquals(FIFA_BADGE,friendly.competition)
        assertTrue(friendly.home.endsWith("21f0oi1597948195.png"))
        assertTrue(friendly.away.endsWith("ncgqyr1726166942.png"))
        assertEquals(FootballArtwork(),initialFootballArtwork(FootballDetails("Premier League","Chelsea","Bournemouth")))
    }
}
