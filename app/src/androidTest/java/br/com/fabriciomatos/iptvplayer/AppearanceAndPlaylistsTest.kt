package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceAndPlaylistsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val a="https://example.test/aurora-a.m3u"
    private val b="https://example.test/aurora-b.m3u"
    private val movie=Channel("Filme Aurora","https://example.test/movie/aurora","Terror")
    private val episodes=listOf(Channel("Série Aurora S01E01","https://example.test/series/a1","Séries"),
        Channel("Série Aurora S02E01","https://example.test/series/a2","Séries"))
    private val id get()=seriesIdentity(episodes.first()).id
    private val alternative=Channel("Canal Boreal","https://example.test/live/b1","Brasil")

    @Before fun reset()=resetCatalog(context)

    private fun seed() {
        PlaylistRepository(context).also {
            it.saveSource(a,"Aurora A");it.appendCache(a,Download(Playlist(episodes+movie),"",""))
            it.saveLast(movie,a);it.saveFavorites(setOf(movie.url));it.saveSeriesFavorites(a,setOf(id))
            it.savePlaybackProgress(a,movie.url,PlaybackProgress(120000,600000))
            it.saveSource(b,"Boreal B");it.appendCache(b,Download(Playlist(listOf(alternative)),"",""))
            it.saveLast(alternative,b);it.saveSource(a,"Aurora A");it.close()
        }
        AppSettings(context).apply {saveAppearance(AppAppearance.DARK);saveEpgUrl("https://example.test/a.xml",a);saveEpgUrl("https://example.test/b.xml",b)}
    }

    @Test fun existingSinglePlaylistMigratesAndSourcesKeepTheirOwnRecentContentAndGuide() {
        context.getSharedPreferences("aurora_iptv_preferences",0).edit().putString("playlist_url",a)
            .putString("playlist_name","Lista anterior").putString("last_channel_url",movie.url).commit()
        AppSettings(context).saveEpgUrl("https://example.test/legacy.xml")
        assertEquals("https://example.test/legacy.xml",AppSettings(context).epgUrl(a))
        assertEquals("",AppSettings(context).epgUrl(b))
        PlaylistRepository(context).also {
            it.appendCache(a,Download(Playlist(episodes+movie),"",""))
            assertEquals(listOf(SavedPlaylist(a,"Lista anterior")),it.savedPlaylists())
            it.saveSource(b,"Segunda lista");it.appendCache(b,Download(Playlist(listOf(alternative)),"",""))
            it.saveLast(alternative);assertEquals(movie.url,it.lastUrl(a));assertEquals(alternative.url,it.lastUrl(b))
            it.saveSource(a,"Lista renomeada");it.saveSource(a,"Lista renomeada")
            assertEquals(2,it.savedPlaylists().size);it.close()
        }
        PlaylistRepository(context).also {
            assertEquals(a,it.savedUrl());assertEquals("Lista renomeada",it.savedName())
            assertEquals(3,it.overview(a).total);assertEquals(1,it.overview(b).total)
            assertEquals(movie.url,it.lastUrl());assertEquals(alternative.url,it.lastUrl(b));it.close()
        }
    }

    @Test fun switchingCachedPlaylistsThroughSettingsRestoresEachSourceAfterReopening() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==3}
            compose.onNodeWithTag("settings_gear").performClick()
            compose.onNodeWithText("Minha playlist").performClick()
            compose.onNodeWithTag("add_playlist").performScrollTo().performClick()
            compose.onNodeWithText("Endereço M3U / M3U8").assertExists()
            compose.onNodeWithTag("playlist_${PlaylistRepository.digest(b)}").performScrollTo().performClick()
            compose.waitUntil(15000) {model.playlistUrl==b && model.catalog.total==1 && !model.switchingPlaylist && !model.updating}
            assertEquals(alternative.url,model.recent?.url);assertTrue(model.seriesFavorites.isEmpty())
            assertEquals("https://example.test/b.xml",model.epgOverride)
            assertEquals(1,model.catalog.live);assertEquals(0,model.catalog.movies)
            scenario.onActivity {model.openSettings(1)}
            compose.onNodeWithTag("playlist_${PlaylistRepository.digest(a)}").performScrollTo().performClick()
            compose.waitUntil(15000) {model.playlistUrl==a && model.catalog.total==3 && !model.switchingPlaylist && !model.updating}
            assertEquals(movie.url,model.recent?.url);assertEquals(setOf(id),model.seriesFavorites)
            assertEquals("https://example.test/a.xml",model.epgOverride)
            scenario.onActivity {model.browse(null,true)}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Série Aurora").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Série Aurora").assertExists();compose.onNodeWithText(movie.name).assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized}
            assertEquals(a,model.playlistUrl);assertEquals(2,model.savedPlaylists.size)
            assertEquals(movie.url,model.recent?.url);assertEquals(setOf(id),model.seriesFavorites)
        }
        PlaylistRepository(context).also {
            assertEquals(120000L,it.playbackProgress(a,movie.url)?.resumePositionMs)
            assertNull(it.playbackProgress(b,movie.url));assertEquals(setOf(movie.url),it.favorites());it.close()
        }
    }

    @Test fun homeFitsPortraitAndLandscapeAndAppearancePersistsAndFollowsTheSystem() {
        seed()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("cmd uimode night no")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                compose.waitUntil(15000) {model.initialized && context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
                assertHomeFits()
                val recent=compose.onNodeWithTag("home_recent").fetchSemanticsNode().boundsInRoot
                val live=compose.onNodeWithTag("home_live").fetchSemanticsNode().boundsInRoot
                val football=compose.onNodeWithTag("home_football").fetchSemanticsNode().boundsInRoot
                val total=compose.onNodeWithTag("home_total").fetchSemanticsNode().boundsInRoot
                assertTrue(recent.bottom<=live.top);assertTrue(football.bottom<=total.top)
                compose.onNodeWithText("O que vamos assistir?").assertDoesNotExist();screenshot("aurora-dark-home")
                scenario.onActivity {model.openSettings()}
                compose.onNodeWithText("Aparência").performClick()
                compose.onNodeWithTag("theme_LIGHT").performClick()
                compose.waitUntil(10000) {model.appearance==AppAppearance.LIGHT}
                scenario.onActivity {model.settingsOpen=false}
                assertHomeFits();screenshot("aurora-light-home")
                scenario.onActivity {assertEquals(android.graphics.Color.rgb(244,249,252),it.window.statusBarColor);it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                compose.waitUntil(10000) {context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
                listOf("home_recent","home_live","home_movies","home_series","home_football","home_total").forEach {compose.onNodeWithTag(it).assertIsDisplayed()}
                screenshot("aurora-light-landscape")
                scenario.onActivity {model.chooseAppearance(AppAppearance.SYSTEM)}
                compose.waitUntil(10000) {model.appearance==AppAppearance.SYSTEM}
                val nightResponse=device.executeShellCommand("cmd uimode night yes")
                assertTrue("Android rejected the system appearance request: $nightResponse",nightResponse.contains("Night mode: yes"))
                // Query the Activity receiving configuration changes, rather than the cached instrumentation context.
                compose.waitUntil(10000) {
                    var dark=false
                    scenario.onActivity {dark=it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES && it.window.statusBarColor==android.graphics.Color.rgb(8,13,25)}
                    dark
                }
                compose.waitForIdle()
                scenario.onActivity {assertEquals(android.graphics.Color.rgb(8,13,25),it.window.statusBarColor)}
                screenshot("aurora-system-dark-landscape")
            }
            assertEquals(AppAppearance.SYSTEM,AppSettings(context).appearance())
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized}
                assertEquals(AppAppearance.SYSTEM,model.appearance)
                scenario.onActivity {assertEquals(android.graphics.Color.rgb(8,13,25),it.window.statusBarColor)}
            }
        } finally {device.executeShellCommand("cmd uimode night no")}
    }

    private fun assertHomeFits() {
        listOf("home_recent","home_live","home_movies","home_series","home_football","home_total").forEach {compose.onNodeWithTag(it).assertIsDisplayed()}
        val viewport=compose.onNodeWithTag("home_content").fetchSemanticsNode().boundsInRoot
        val total=compose.onNodeWithTag("home_total").fetchSemanticsNode().boundsInRoot
        val before=compose.onNodeWithTag("home_recent").fetchSemanticsNode().boundsInRoot
        assertTrue(before.top>=viewport.top);assertTrue(total.bottom<=viewport.bottom)
        compose.onNodeWithTag("home_content").performTouchInput {swipeUp()}
        compose.waitForIdle()
        assertEquals(before,compose.onNodeWithTag("home_recent").fetchSemanticsNode().boundsInRoot)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/$name.png")
    }
}
