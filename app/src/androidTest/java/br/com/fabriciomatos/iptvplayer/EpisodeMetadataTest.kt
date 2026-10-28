package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeMetadataTest {
    @Test fun providerScenesDurationsAndOldCachesRoundTripWithoutInventedPosters() {
        val json=JSONObject("""{"info":{"plot":"Enredo","cover":"https://example.test/poster.jpg"},"episodes":{"1":[
          {"id":"101","season":1,"episode_num":1,"title":"<b>O piloto</b>","info":{"movie_image":"/one.jpg","duration_secs":2400}},
          {"id":"102","episode_num":2,"title":null,"info":{"still_path":"/two.jpg","duration":"00:42:03"}},
          {"id":"103","episode_num":3,"info":{"movie_image":"https://example.test/poster.jpg","runtime":40}},
          {"id":"104","episode_num":4,"info":{"cover":"/series-poster.jpg","duration":"-2:99:00"}}
        ]}}""")
        val parsed=parseContentDetails(json)
        assertEquals("O piloto",parsed.episodes[0].title)
        assertEquals("https://image.tmdb.org/t/p/w500/one.jpg",parsed.episodes[0].imageUrl)
        assertEquals(2400000L,parsed.episodes[0].durationMs)
        assertEquals("",parsed.episodes[1].title);assertEquals(2523000L,parsed.episodes[1].durationMs)
        assertEquals("",parsed.episodes[2].imageUrl);assertEquals(2400000L,parsed.episodes[2].durationMs)
        assertEquals("",parsed.episodes[3].imageUrl);assertEquals(0L,parsed.episodes[3].durationMs)
        assertEquals(parsed,decodeContentDetails(encodeContentDetails(parsed)))
        val old=decodeContentDetails(JSONObject("""{"plot":"Anterior","episodes":[{"id":"1","season":1,"number":1,"title":"Piloto","plot":"Anterior"}]}"""))
        assertEquals("",old.episodes.single().imageUrl);assertEquals(0L,old.episodes.single().durationMs)
    }

    @Test fun publicLookupUsesExactShowAndEpisodeNumbersAndNeverStreamUrls() = runBlocking {
        val seen=mutableListOf<String>()
        val lookup=EpisodeImageLookup {url->
            seen.add(url)
            if("/search/shows?" in url) JSONObject("""{"items":[{"show":{"id":7,"name":"See","premiered":"2019-11-01"}}]}""")
            else JSONObject("""{"items":[{"id":101,"season":1,"number":1,"name":"Piloto","runtime":40,"image":{"medium":"https://static.tvmaze.com/uploads/images/medium_landscape/1/one.jpg"}},
              {"id":102,"season":2,"number":1,"name":"Retorno","image":{"original":"https://static.tvmaze.com/uploads/images/original_untouched/1/two.jpg"}},
              {"season":1,"number":null,"image":{"medium":"https://static.tvmaze.com/uploads/images/no-number.jpg"}},
              {"season":1,"number":3,"image":null}]}""")
        }
        val result=lookup.find("See (2019) [HD]")
        assertEquals(2,result.size);assertEquals(listOf(1,2),result.map {it.season})
        assertTrue(result.all {it.id.isBlank() && it.imageSource=="TVmaze"})
        assertEquals(2400000L,result.first().durationMs)
        assertTrue(seen.all {it.startsWith("https://api.tvmaze.com/") && "password" !in it && "get.php" !in it})
    }

    @Test fun remakesFuzzyMatchesUnknownYearsAndConflictingScenesAreRejected() {
        val rows=JSONArray("""[{"show":{"id":1,"name":"See","premiered":"2019-01-01"}},
          {"show":{"id":2,"name":"See","premiered":"2026-01-01"}}]""")
        assertNull(selectEpisodeImageShow("See",null,rows))
        assertEquals(1,selectEpisodeImageShow("See",2019,rows)?.getInt("id"))
        assertNull(selectEpisodeImageShow("See",2025,rows))
        assertNull(selectEpisodeImageShow("Sea",2019,rows))
        assertNull(selectEpisodeImageShow("See",2019,JSONArray("""[{"show":{"id":1,"name":"See"}}]""")))
        val images=JSONArray("""[{"season":1,"number":1,"image":{"medium":"https://static.tvmaze.com/uploads/images/a.jpg"}},
          {"season":1,"number":1,"image":{"medium":"https://static.tvmaze.com/uploads/images/b.jpg"}},
          {"season":1,"number":2,"image":{"medium":"https://evil.test/image.jpg"}}]""")
        assertTrue(parsePublicEpisodeImages(images,"https://www.tvmaze.com/shows/1").isEmpty())
    }

    @Test fun legacyProviderCacheRefreshesAndPublicMetadataReusesOfflineWithoutLosingSourceData() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="https://example.test/get.php?username=test&password=test&type=m3u_plus"
        val series=SeriesEntry("id:5","See (2019)","",emptySet(),emptyList(),episodeCount=1)
        val repository=PlaylistRepository(context)
        try {
            repository.saveArtwork(synopsisCacheKey(source,false,"5"),"""{"plot":"Enredo anterior","episodes":[{"id":"100","season":1,"number":1,"title":"Título anterior","plot":"Enredo episódio"}]}""")
            var calls=0;var searches=0
            val publicLookup=EpisodeImageLookup {url->
                searches++
                if("/search/shows?" in url)JSONObject("""{"items":[{"show":{"id":7,"name":"See","premiered":"2019-11-01"}}]}""")
                else JSONObject("""{"items":[{"season":1,"number":1,"runtime":40,"image":{"medium":"https://static.tvmaze.com/uploads/images/a.jpg"}}]}""")
            }
            val loader=SynopsisRepository(repository,episodeLookup=publicLookup) {
                calls++;JSONObject("""{"info":{"plot":"Enredo atualizado"},"episodes":{"1":[{"id":"100","episode_num":1,"title":"Título oficial","info":{"plot":"Enredo episódio"}}]}}""")
            }
            val first=loader.episodeDetails(source,series)
            assertEquals(1,calls);assertEquals(2,searches)
            assertEquals("100",first.single().id);assertEquals("Título oficial",first.single().title)
            assertEquals("TVmaze",first.single().imageSource)
            val offline=SynopsisRepository(repository,episodeLookup=EpisodeImageLookup {error("Offline")}) {error("Offline")}
            assertEquals(first,offline.episodeDetails(source,series))
            assertEquals("Enredo atualizado",offline.load(source,series=series).plot)
        } finally {repository.close()}
    }
}
