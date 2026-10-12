package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invoked in separate instrumentation processes by verify-force-stop.py. */
@RunWith(AndroidJUnit4::class)
class ForceStopFixtureTest {
    private val source = "https://example.test/force-stop.m3u"
    @Test fun seed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("forceStopPhase") == "seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val repository = PlaylistRepository(context)
        repository.saveSource(source, "Lista persistente")
        val channels = listOf(Channel("Canal persistente", "https://example.test/live/1"),
            Channel("Filme persistente", "https://example.test/movie/2", "Filmes"),
            Channel("Rick e Morty S01E01", "https://example.test/series/3", "Séries"))
        repository.appendCache(source, Download(Playlist(channels), "", ""))
        repository.saveFavorites(setOf(channels[0].url))
        repository.close()
    }
    @Test fun verify() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("forceStopPhase") == "verify")
        val repository = PlaylistRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(source, repository.savedUrl())
        assertEquals(3, repository.readCache(source).channels.size)
        assertEquals(setOf("https://example.test/live/1"), repository.favorites())
        assertFalse(repository.claimDailyAttempt(source))
        repository.close()
    }
}
