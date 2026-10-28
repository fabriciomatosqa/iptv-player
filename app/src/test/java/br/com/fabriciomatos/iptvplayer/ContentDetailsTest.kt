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
    @Test fun exactStreamIdWinsButDifferentScenesAreNotGuessedForOpaqueAddresses() {
        val first=EpisodeSynopsis("10",1,1,"Piloto","Mesmo enredo","https://example.test/scene-a.jpg")
        val other=first.copy(id="20",imageUrl="https://example.test/scene-b.jpg")
        val details=ContentDetails(episodes=listOf(first,other))
        assertEquals(other,selectEpisodeSynopsis(details,Channel("Série S01E01","https://example.test/20.mkv","Séries")))
        assertNull(selectEpisodeSynopsis(details,Channel("Série S01E01","https://example.test/opaque.mkv","Séries")))
    }
    @Test fun publicScenesFillOnlyMissingProviderMetadata() {
        val official=EpisodeSynopsis("10",1,1,"Título do provedor","Enredo do provedor","https://example.test/provider.jpg",2400000)
        val extra=EpisodeSynopsis("",1,1,"Public title","","https://static.tvmaze.com/uploads/images/scene.jpg",1200000,"TVmaze","https://www.tvmaze.com/shows/1")
        assertEquals(official,mergeEpisodeImages(listOf(official),listOf(extra)).single())
        val filled=mergeEpisodeImages(listOf(official.copy(imageUrl="",durationMs=0)),listOf(extra)).single()
        assertEquals("10",filled.id);assertEquals("Título do provedor",filled.title)
        assertEquals("Enredo do provedor",filled.plot);assertEquals(extra.imageUrl,filled.imageUrl)
        assertEquals(1200000L,filled.durationMs);assertEquals("TVmaze",filled.imageSource)
        assertTrue(mergeEpisodeImages(emptyList(),listOf(extra)).single().id.isEmpty())
    }
}
