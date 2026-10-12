package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class OrganizationTest {
    @Test fun movieTabsMergeAccentsPrefixesAndDecorationsAndUseRequestedOrder() {
        val raw=listOf("Filmes | Drama","♦️ Drama ✔️","Documentarios","♦️ Documentário ✔️","Filmes | Ficcao","♦️ Ficcao Cientifica ✔️",
            "Update","♦️Top Lançamentos 2026 ⭐","♦️ UHD| 4K ⭐","♦️ Aventura","XXX")
        assertEquals(listOf(RECENT_GROUP,RELEASES_2026_GROUP,UHD_GROUP,"Aventura","Documentários","Drama","Ficção científica",ADULT_GROUP),orderedMovieCategories(raw))
    }
    @Test fun footballNeedsTwoTeamsAndRemovesRepeatedTitlesWithoutBreakingHyphenatedNames() {
        assertNull(footballDetails("Libertadores e Sulamericana"))
        assertNull(footballDetails("Canal de Futebol 24 horas"))
        assertNull(footballDetails("NBA: Cleveland Cavaliers x Orlando Magic"))
        assertNull(footballDetails("Spy x Family"))
        assertEquals(FootballDetails("Campeonato Brasileiro - Série B","Operário-PR","Goiás"),
            footballDetails("Operário-PR x Goiás - Campeonato Brasileiro - Série B ║ Operário-PR x Goiás"))
        assertEquals(FootballDetails("Premier League","Chelsea","Bournemouth"),footballDetails("Chelsea vs. Bournemouth - Premier League || Chelsea vs. Bournemouth"))
        assertEquals(FootballDetails("Copa Libertadores da América","Fluminense","Palmeiras"),
            footballDetails("Semifinal, Jogo de Ida: Fluminense x Palmeiras - Copa Libertadores da América ║ Semifinal, Jogo de Ida: Fluminense x Palmeiras"))
        assertEquals(FootballDetails("Futebol","Aurora","Norte"),footballDetails("Aurora x Norte","Futebol"))
        assertNull(footballDetails("Futebol: Aurora x Aurora"))
    }
    @Test fun animeMatchingRejectsOtherTitlesAndPrefersExactNamesOverSeasons() {
        assertEquals(100,animeTitleScore("Sono Bisque Doll wa Koi wo Suru","Sono Bisque Doll wa Koi wo Suru"))
        assertEquals(0,animeTitleScore("Sono Bisque Doll wa Koi wo Suru","Sono Bisque Doll wa Koi wo Suru 2nd Season"))
        assertEquals(98,animeTitleScore("Tensai Ouji no Akaji Kokka Saisei Jutsu","Tensai Ouji no Akaji Kokka Saisei Jutsu: Souda, Baikoku shiyou"))
        assertEquals(0,animeTitleScore("Black Clover","Black Mirror"))
        assertEquals(teamKey("Operário PR"),teamKey("Operário-PR"))
        assertEquals(teamKey("Bournemouth"),teamKey("AFC Bournemouth"))
    }
}
