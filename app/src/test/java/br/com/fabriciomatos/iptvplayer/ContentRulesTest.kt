package br.com.fabriciomatos.iptvplayer
import org.junit.Assert.*
import org.junit.Test
class ContentRulesTest {
    @Test fun adultGroupsDoNotConfuseOrdinaryMovieTitles() {
        assertTrue(isAdultGroup("🔞 Filmes +18"));assertTrue(isAdultGroup("Adultos"));assertTrue(isAdultGroup("XXX"))
        assertFalse(isAdultGroup("Drama"));assertFalse(isAdultGroup("Lançamentos 2018"))
    }
    @Test fun gamesExcludeFootballNewsAndUnrelatedBrazilianTitles() {
        assertTrue(isFootballProgramme("Futebol: Aurora x Norte"))
        assertTrue(isFootballProgramme("Champions League: Aurora vs Norte"))
        assertFalse(isFootballProgramme("Arte Brasileira Quadro a Quadro"))
        assertFalse(isFootballProgramme("Arquitetos Brasileiros"))
        assertFalse(isFootballProgramme("NFL: Aurora x Norte"))
        assertFalse(isFootballProgramme("Debate do Futebol"))
    }
    @Test fun posterAliasesKeepExplicitSeriesYearsWhileAddingSafeLookup() {
        assertEquals("black clover", posterAlias("Black Clover (2017)"))
        assertEquals("arifureta shokugyou de sekai saikyou",canonicalAnimeTitle("Arifureta Shokugyou de Sekai Saikyou [L]"))
        assertTrue(isAnimationGenre("Animação, Comédia"));assertFalse(isAnimationGenre("Drama"))
    }
}
