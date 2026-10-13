package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class ContentDetailsTest {
    @Test fun anOpaquePlaybackAddressUsesTheExactSeasonAndEpisode() {
        val details=ContentDetails("Sinopse da série",listOf(EpisodeSynopsis("10",1,1,"Piloto","Primeiro episódio"),
            EpisodeSynopsis("20",2,1,"Retorno","Segunda temporada")))
        val channel=Channel("Minha série S02E01","https://example.test/opaque-token.mkv","Séries")
        assertEquals("Segunda temporada",selectEpisodeSynopsis(details,channel)?.plot)
        assertNull(selectEpisodeSynopsis(details,channel.copy(name="Outra série S02E02")))
        assertNull(selectEpisodeSynopsis(details.copy(episodes=details.episodes+EpisodeSynopsis("21",2,1,"Outro","Sinopse incompatível")),channel))
    }
    @Test fun streamIdsAreParsedWithoutUsingAccountSegmentsAndUnknownIdsDoNotSelectAnArbitraryEpisode() {
        assertEquals("123",playbackContentId("https://example.test/series/user/password/123.mkv?x=1"))
        assertNull(playbackContentId("https://example.test/opaque/abc.mkv"))
        val details=ContentDetails(episodes=listOf(EpisodeSynopsis("10",1,1,"Piloto","Primeiro")))
        assertNull(selectEpisodeSynopsis(details,Channel("Série","https://example.test/11.mkv","Séries",typeHint="series")))
    }
}
