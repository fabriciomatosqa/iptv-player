package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class LocalPlaylistImportAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bytes(text: String) = ByteArrayInputStream(text.trimIndent().toByteArray(Charsets.UTF_8))

    @Before fun reset() = resetCatalog(context)

    @Test fun localPlaylistSurvivesRestartAndReimportDoesNotDuplicateItems() {
        val source = PlaylistRepository.newLocalSource()
        val content = """
            #EXTM3U
            #EXTINF:-1 group-title="Brasil",Canal Aurora
            https://example.test/live/a
            #EXTINF:-1 group-title="Brasil",Canal repetido
            https://example.test/live/a
            #EXTINF:-1 group-title="Filmes",Filme Aurora
            https://example.test/movie/b
        """
        PlaylistRepository(context).also { repository ->
            assertTrue(PlaylistRepository.isLocalSource(source))
            assertEquals(2, repository.importLocalFile(source, bytes(content)).total)
            repository.saveSource(source, "Arquivo Aurora")
            assertEquals(2, repository.overview(source).total)
            repository.close()
        }
        PlaylistRepository(context).also { repository ->
            assertEquals(source, repository.savedUrl())
            assertEquals("Arquivo Aurora", repository.savedName())
            assertEquals(listOf(SavedPlaylist(source, "Arquivo Aurora")), repository.savedPlaylists())
            repository.restore(source)
            assertEquals(2, repository.overview(source).total)
            assertEquals(0, repository.reimportLocalFile(source).added)
            assertEquals(2, repository.overview(source).total)
            repository.close()
        }
    }

    @Test fun replacingFilePreservesFavoritesAndProgressAndRejectsBadHls() {
        val source = PlaylistRepository.newLocalSource()
        val original = """
            #EXTM3U
            #EXTINF:-1,Canal anterior
            https://example.test/live/old
        """
        val replacement = """
            #EXTM3U
            #EXTINF:-1,Canal anterior
            https://example.test/live/old
            #EXTINF:-1,Canal novo
            https://example.test/live/new
        """
        val bad = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:1
            #EXTINF:6,
            https://example.test/segment.ts
        """
        PlaylistRepository(context).also { repository ->
            repository.importLocalFile(source, bytes(original))
            repository.saveSource(source, "Minha lista")
            repository.saveFavorites(setOf("https://example.test/live/old"))
            repository.savePlaybackProgress(source, "https://example.test/live/old", PlaybackProgress(15000, 60000))
            try {
                repository.importLocalFile(source, bytes(bad))
                fail("An HLS video manifest must not replace a playlist.")
            } catch (_: IllegalArgumentException) { /* catalog and saved copy remain unchanged */ }
            assertEquals(1, repository.reimportLocalFile(source).total)
            assertEquals(2, repository.importLocalFile(source, bytes(replacement)).total)
            assertEquals(2, repository.reimportLocalFile(source).total)
            // Local replacement removes missing channels, unlike the additive online refresh.
            val onlyNew = """
                #EXTM3U
                #EXTINF:-1,Canal novo
                https://example.test/live/new
            """
            assertEquals(1, repository.importLocalFile(source, bytes(onlyNew)).total)
            assertEquals(1, repository.reimportLocalFile(source).total)
            assertEquals(setOf("https://example.test/live/old"), repository.favorites())
            assertEquals(15000L, repository.playbackProgress(source,"https://example.test/live/old")?.positionMs)
            repository.close()
        }
    }
}
