package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesFavoritesTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/favorites.m3u"
    private val episodes=listOf(Channel("Série favorita S01E01","https://example.test/series/1","Séries"),
        Channel("Série favorita S02E01","https://example.test/series/2","Séries"))
    private val movie=Channel("Filme favorito","https://example.test/movie/1","Filmes")

    @Test fun versionSixEpisodeFavoritesBecomeOneSeriesWithAllSeasonsAndNewEpisodes() {
        resetCatalog(context)
        PlaylistRepository(context).also {
            it.saveSource(source,"Favoritos");it.appendCache(source,Download(Playlist(episodes+movie),"",""))
            it.saveFavorites(setOf(episodes.first().url,movie.url));it.close()
        }
        legacyHostCopies(context,source,0)
        SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE series_favorites");it.execSQL("DROP TABLE playback_progress");it.version=6
        }
        val id=seriesIdentity(episodes.first()).id
        PlaylistRepository(context).also {
            it.restore(source)
            assertEquals(setOf(movie.url),it.favorites());assertEquals(setOf(id),it.seriesFavorites(source))
            assertEquals(2,it.matchingCount(BrowseQuery(source,kind=null,favoritesOnly=true)))
            assertEquals(listOf(movie.url),it.channels(BrowseQuery(source,kind=null,favoritesOnly=true)).map {c->c.url})
            assertEquals(listOf(id),it.series(BrowseQuery(source,MediaKind.SERIES,favoritesOnly=true)).map {s->s.id})
            assertEquals(2,it.channels(BrowseQuery(source,MediaKind.SERIES,favoritesOnly=true,seriesId=id)).size)
            it.appendCache(source,Download(Playlist(listOf(Channel("Série favorita S02E02","https://example.test/series/3","Séries"))),"",""))
            assertEquals(3,it.channels(BrowseQuery(source,MediaKind.SERIES,favoritesOnly=true,seriesId=id)).size)
            assertTrue(it.seriesFavorites("https://other.test/list.m3u").isEmpty())
            it.savePlaybackProgress(source,movie.url,PlaybackProgress(123456,600000))
            it.close()
        }
        PlaylistRepository(context).also {
            it.restore(source);assertEquals(setOf(id),it.seriesFavorites(source))
            assertEquals(123456L,it.playbackProgress(source,movie.url)?.resumePositionMs)
            it.saveSeriesFavorites(source,emptySet())
            assertEquals(1,it.matchingCount(BrowseQuery(source,kind=null,favoritesOnly=true)))
            assertEquals(setOf(movie.url),it.favorites());it.close()
        }
    }
    @Test fun starsFavoriteTheSeriesAndFavoritesOpenEverySeasonWithoutEpisodeStars() {
        resetCatalog(context)
        PlaylistRepository(context).also {
            it.saveSource(source,"Favoritos");it.appendCache(source,Download(Playlist(episodes+movie),"",""));it.saveFavorites(setOf(movie.url));it.close()
        }
        val id=seriesIdentity(episodes.first()).id
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==3}
            scenario.onActivity {model.browse(MediaKind.SERIES)}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("series_favorite_$id").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("series_favorite_$id").performClick()
            compose.waitUntil(10000) {id in model.seriesFavorites}
            assertTrue(model.favorites.none {it in episodes.map {e->e.url}})
            scenario.onActivity {model.browse(null,true)}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Série favorita").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Série favorita").assertExists();compose.onNodeWithText("Filme favorito").assertExists()
            screenshot("series-favorites-portrait")
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000) {context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithText("Série favorita").assertExists();screenshot("series-favorites-landscape")
            compose.onNodeWithText("Série favorita").performClick()
            compose.waitUntil(10000) {model.openedSeriesId==id && model.visibleCount==1 && !model.filtering}
            compose.onNodeWithText(episodes[0].name).assertExists()
            compose.onAllNodesWithContentDescription("Adicionar favorito").assertCountEquals(0)
            scenario.onActivity {model.selectSeason(2)}
            compose.waitUntil(10000) {compose.onAllNodesWithText(episodes[1].name).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("series_favorite_$id").performClick()
            compose.waitUntil(10000) {model.seriesFavorites.isEmpty()}
            // Removing a favorite while inside a series must not filter away its episodes.
            compose.onNodeWithText(episodes[1].name).assertExists()
            scenario.onActivity {model.closeSeries()}
            compose.waitUntil(10000) {!model.filtering && model.openedSeriesId==null && model.visibleCount==1}
            compose.onNodeWithText("Filme favorito").assertExists()
        }
    }
    private fun screenshot(name: String) {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/$name.png")
    }
}
