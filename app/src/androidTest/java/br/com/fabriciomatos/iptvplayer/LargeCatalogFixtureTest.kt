package br.com.fabriciomatos.iptvplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic catalog matching the reported scale; contains no provider credentials or content. */
@RunWith(AndroidJUnit4::class)
class LargeCatalogFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "https://example.test/large.m3u"
    private val movieUrl = streamUrl(1_043_791)
    private fun streamUrl(id: Int) = "https://example.test/play/"+id.toString(16).padStart(64,'0')
    private val title = "100 Dias para Morar Comecando do Zero"
    private val cover = "https://example.test/correct-series.jpg"

    @Test fun seed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("forceStopPhase") == "seed")
        resetCatalog(context)
        var repository = PlaylistRepository(context)
        repository.saveSource(source, "Catálogo completo")
        val metadata = ProviderMetadata(mapOf("FILMES E SÉRIES" to MediaKind.LIVE, "Ação" to MediaKind.MOVIES,
            "Netflix" to MediaKind.SERIES), mapOf(normalized(title) to SeriesPoster(title, cover, "1")))
        repository.saveMetadata(source, metadata)
        val ready = File(context.filesDir, "catalog_pending/" + PlaylistRepository.digest(source) + ".ready")
        ready.parentFile!!.mkdirs()
        ready.bufferedWriter().use { writer ->
            writer.write("#EXTM3U\n")
            fun row(name: String, group: String, url: String, image: String = "") {
                writer.write("#EXTINF:-1 group-title=\"$group\" tvg-logo=\"$image\",$name\n$url\n")
            }
            repeat(2034) { row("Canal $it", "FILMES E SÉRIES", streamUrl(it)+"/ts") }
            repeat(43792) { row("Filme $it", "Ação", streamUrl(1_000_000+it)) }
            repeat(270072) {
                val show = it % 9447
                val episode = it / 9447 + 1
                val name = if (show == 0) title else "Serie $show"
                row("$name S01E" + episode.toString().padStart(2, '0'), "Netflix", streamUrl(2_000_000+it),
                    "https://example.test/wrong-series.jpg")
            }
            repeat(8) { row("Filme duplicado $it", "Ação", streamUrl(1_000_000+it)) }
        }
        assertTrue(repository.hasPending(source))
        val started = System.nanoTime()
        val result = repository.importPending(source)
        Log.i("AuroraScale", "Imported ${result.total} unique rows in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(315898, result.added)
        assertFalse(repository.hasPending(source))
        repository.saveFavorites(setOf(movieUrl))
        repository.savePlaybackProgress(source,movieUrl,PlaybackProgress(42000,120000,false))
        repository.close()
        legacyHostCopies(context,source,3,originalNewest=true)
        val repairStarted=System.nanoTime()
        repository=PlaylistRepository(context)
        repository.restore(source)
        Log.i("AuroraScale","Repaired 1,263,592 legacy rows to ${repository.overview(source).total} in ${(System.nanoTime()-repairStarted)/1_000_000} ms")
        assertEquals(setOf(movieUrl),repository.favorites())
        assertEquals(42000L,repository.playbackProgress(source,movieUrl)!!.positionMs)
        checkCatalog(repository)
        // A partial next-day response must preserve the entire previous catalog.
        repository.importReader(source, "#EXTM3U\n#EXTINF:-1 group-title=\"Ação\",Renomeado\n$movieUrl\n".reader(), metadata)
        checkCatalog(repository)
        assertEquals("Filme 43791", repository.channel(source, movieUrl)!!.name)
        repository.close()
    }

    @Test fun verify() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("forceStopPhase") == "verify")
        val repository = PlaylistRepository(context)
        assertEquals(source, repository.savedUrl())
        repository.restore(source)
        checkCatalog(repository)
        assertEquals(setOf(movieUrl), repository.favorites())
        assertFalse(repository.claimDailyAttempt(source))
        assertEquals(cover, repository.metadata(source).posters[normalized(title)]!!.image)
        repository.close()
    }

    private fun checkCatalog(repository: PlaylistRepository) {
        val counts = repository.overview(source)
        assertEquals(315898, counts.total)
        assertEquals(2034, counts.live)
        assertEquals(43792, counts.movies)
        assertEquals(270072, counts.episodes)
        assertEquals(9447, counts.series)
        assertFalse(counts.groups[MediaKind.SERIES].orEmpty().contains("FILMES E SÉRIES"))
        assertEquals(60, repository.channels(BrowseQuery(source, MediaKind.MOVIES)).size)
        assertEquals(movieUrl, repository.channels(BrowseQuery(source, MediaKind.MOVIES), 43791, 60).single().url)
        val search = BrowseQuery(source, MediaKind.MOVIES, search = "filme 43791")
        assertEquals(1, repository.matchingCount(search))
        assertEquals(movieUrl, repository.channels(search).single().url)
        val show = repository.series(BrowseQuery(source, MediaKind.SERIES, search = "100 dias")).single()
        assertEquals(cover, show.imageUrl)
        assertTrue(show.episodes.isEmpty())
        assertEquals(29, show.episodeCount)
        assertEquals(cover, repository.channels(BrowseQuery(source, MediaKind.SERIES, seriesId = show.id)).first().seriesImageUrl)
        assertEquals(MediaKind.LIVE, repository.channels(BrowseQuery(source, MediaKind.LIVE)).first().kind)
    }
}
