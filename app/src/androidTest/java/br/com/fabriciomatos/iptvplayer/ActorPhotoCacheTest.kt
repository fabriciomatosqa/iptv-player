package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ActorPhotoCacheTest {
    @Test fun actorPhotoIsSharedAcrossTitlesAndSurvivesRepositoryReopening() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val calls=AtomicInteger(0)
        val wiki="https://upload.wikimedia.org/wikipedia/commons/a/aa/test-person.jpg"
        val reader:(String)->JSONObject={ address ->
            calls.incrementAndGet()
            assertTrue(address.contains("wikipedia.org/api/rest_v1/page/summary/"))
            JSONObject("""{"title":"Leonardo DiCaprio","type":"standard",
                 "description":"American actor and film producer",
                 "thumbnail":{"source":"$wiki"}}""")
        }
        PlaylistRepository(context).use { db ->
            val photos=ActorPhotoRepository(db,reader)
            val first=photos.image(CastMember("Leonardo DiCaprio"))
            assertEquals(wiki,first)
            assertEquals(first,photos.image(CastMember("Leonardo DiCaprio")))
            assertEquals(1,calls.get())
        }
        PlaylistRepository(context).use { reopened ->
            val noNetwork=ActorPhotoRepository(reopened){ error("Photo should be cached") }
            assertEquals(wiki,noNetwork.image(CastMember("Leonardo DiCaprio")))
        }
    }

    @Test fun rejectsAmbiguousAndUnrelatedWikipediaIdentity() {
        val candidate="https://upload.wikimedia.org/wikipedia/commons/a/aa/photo.jpg"
        assertEquals("",wikipediaPhoto("John Smith") {
            JSONObject("""{"title":"John Smith","type":"disambiguation",
                "description":"actor","thumbnail":{"source":"$candidate"}}""")
        })
        assertEquals("",wikipediaPhoto("John Smith") {
            JSONObject("""{"title":"John Smith","type":"standard",
                "description":"professional footballer","thumbnail":{"source":"$candidate"}}""")
        })
        assertEquals("",wikipediaPhoto("John Smith") {
            JSONObject("""{"title":"John Smith Jr.","type":"standard",
                "description":"American actor","thumbnail":{"source":"$candidate"}}""")
        })
    }

    @Test fun missingPortugueseEntryFallsBackToExactEnglishProfile() {
        val urls=mutableListOf<String>()
        val image=wikipediaPhoto("Leonardo DiCaprio"){url->
            urls.add(url)
            if("pt.wikipedia.org" in url) error("No Portuguese article")
            JSONObject("""{"title":"Leonardo DiCaprio","type":"standard",
                "description":"American actor","thumbnail":{
                "source":"https://upload.wikimedia.org/wikipedia/commons/a/aa/photo.jpg"}}""")
        }
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/aa/photo.jpg",image)
        assertEquals(2,urls.size)
        assertFalse(trustedActorImageUrl("http://127.0.0.1/x.jpg"))
    }
}
