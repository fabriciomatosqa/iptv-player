package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeProgressPersistenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="local-m3u://00000000-0000-0000-0000-000000000051"
    private val other="local-m3u://00000000-0000-0000-0000-000000000052"

    @Before fun reset()=resetCatalog(context)

    @Test fun batchesMoreThanFourHundredUrlsWithoutInventingBookmarksOrMixingPlaylistsAndSurviveReopening() {
        val episodes=(0..500).map {index->
            Channel("Série extensa S01E${index+1}","https://example.test/series/${index+1}.mp4","Séries",typeHint="series")
        }
        val missing=setOf(7,400,500)
        val expected=episodes.mapIndexedNotNull {index,episode->
            if(index in missing)null else episode.url to PlaybackProgress(
                if(index%17==0)120000L else index*100L,120000L,index%17==0)
        }.toMap()
        val otherProgress=PlaybackProgress(7777,90000)
        withRepository {repository->
            repository.saveSource(source,"Progresso em lotes")
            repository.appendCache(source,Download(Playlist(episodes),"",""))
            expected.forEach {(url,progress)->repository.savePlaybackProgress(source,url,progress)}
            repository.saveSource(other,"Outra lista")
            repository.appendCache(other,Download(Playlist(listOf(episodes.first())),"",""))
            repository.savePlaybackProgress(other,episodes.first().url,otherProgress)
            val requested=episodes.map {it.url}+episodes.first().url+"https://example.test/series/absent.mp4"
            val actual=repository.playbackProgresses(source,requested)
            assertEquals(expected,actual)
            assertEquals(mapOf(episodes.first().url to otherProgress),repository.playbackProgresses(other,requested))
            assertTrue(repository.playbackProgresses(source,emptyList()).isEmpty())
            assertTrue(repository.playbackProgresses("local-m3u://00000000-0000-0000-0000-000000000053",requested).isEmpty())
            missing.forEach {assertFalse(actual.containsKey(episodes[it].url))}
        }
        withRepository {reopened->
            reopened.restore(source)
            assertEquals(501,reopened.overview(source).episodes)
            assertEquals(expected,reopened.playbackProgresses(source,episodes.map {it.url}))
            assertEquals(mapOf(episodes.first().url to otherProgress),reopened.playbackProgresses(other,episodes.map {it.url}))
        }
    }

    @Test fun batchBookmarksResolveMigratedHostAliasesWithoutChangingSourceOrRequestedMapKeys() {
        val original=Channel("Série migrada S01E01","https://example.test/play/"+"a".repeat(64),"Séries",typeHint="series")
        val migrated=original.copy(url=original.url.replace("example.test","cdn-1.example.test"))
        val progress=PlaybackProgress(23000,120000)
        val otherProgress=PlaybackProgress(9000,120000)
        withRepository {repository->
            repository.saveSource(source,"Legado")
            repository.appendCache(source,Download(Playlist(listOf(original)),"",""))
            repository.savePlaybackProgress(source,original.url,progress)
            repository.appendCache(other,Download(Playlist(listOf(original)),"",""))
            repository.savePlaybackProgress(other,original.url,otherProgress)
        }
        // Reproduce the v7 duplicate-host schema that the normal app migration repairs.
        legacyHostCopies(context,source,1)
        withRepository {repaired->
            repaired.restore(source)
            assertEquals(1,repaired.overview(source).episodes)
            assertEquals(migrated.url,repaired.channel(source,original.url)!!.url)
            assertEquals(mapOf(original.url to progress,migrated.url to progress),
                repaired.playbackProgresses(source,listOf(original.url,migrated.url)))
            assertEquals(mapOf(original.url to otherProgress),repaired.playbackProgresses(other,listOf(original.url)))
        }
        withRepository {reopened->
            assertEquals(mapOf(original.url to progress,migrated.url to progress),
                reopened.playbackProgresses(source,listOf(original.url,migrated.url)))
        }
    }

    @Test fun artworkPruningUsesLiteralUnderscoresAndCapsOnlyItsOwnPrefixWithoutChangingUserData() {
        val episode=Channel("Série preservada S01E01","https://example.test/series/1.mp4","Séries",typeHint="series")
        val movie=Channel("Filme preservado","https://example.test/movie/1.mp4","Filmes",typeHint="movie")
        val progress=PlaybackProgress(12000,120000)
        val favoriteSeries=seriesIdentity(episode).id
        val prefix="episode_stills:provider_1:"
        val unrelated=mapOf(
            "episodeXstills:providerA1:keep" to "wildcard-looking neighbor",
            "episode_stills:provider_10:keep" to "different provider",
            "synopsis:keep" to "saved synopsis"
        )
        withRepository {repository->
            repository.saveSource(source,"Dados preservados")
            repository.appendCache(source,Download(Playlist(listOf(episode,movie)),"",""))
            repository.saveFavorites(setOf(movie.url))
            repository.saveSeriesFavorites(source,setOf(favoriteSeries))
            repository.saveLast(episode,source)
            repository.savePlaybackProgress(source,episode.url,progress)
            unrelated.forEach {(key,value)->repository.saveArtwork(key,value)}
            repeat(5) {index->repository.saveArtwork("$prefix$index","scene-$index",86400000L+index*60000L)}
            repository.pruneArtwork(prefix,2)
            repository.pruneArtwork(prefix,2) // Repeated maintenance is idempotent.
            assertEquals(listOf("scene-3","scene-4"),(0..4).mapNotNull {repository.cachedArtwork("$prefix$it",0)})
            unrelated.forEach {(key,value)->assertEquals(value,repository.cachedArtwork(key,0))}
        }
        withRepository {reopened->
            reopened.restore(source)
            assertEquals(listOf("scene-3","scene-4"),(0..4).mapNotNull {reopened.cachedArtwork("$prefix$it",0)})
            unrelated.forEach {(key,value)->assertEquals(value,reopened.cachedArtwork(key,0))}
            assertEquals(2,reopened.overview(source).total)
            assertEquals(setOf(movie.url),reopened.favorites())
            assertEquals(setOf(favoriteSeries),reopened.seriesFavorites(source))
            assertEquals(episode.url,reopened.lastUrl(source))
            assertEquals(progress,reopened.playbackProgress(source,episode.url))
            assertEquals(source,reopened.savedUrl())
        }
    }

    private inline fun <T> withRepository(block: (PlaylistRepository)->T): T {
        val repository=PlaylistRepository(context)
        return try {block(repository)} finally {repository.close()}
    }
}
