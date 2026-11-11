package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic media/EPG only. No provider account or real playlist is used. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SportsHubUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun hierarchyVariantsPlaybackReturnRotationAndPlaylistSwitchPreserveSavedData() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        resetCatalog(context)
        val now=System.currentTimeMillis()
        val first="https://example.test/sports-a.m3u"
        val second="https://example.test/sports-b.m3u"
        val video=File(context.cacheDir,"sports-hub-smoke.mp4")
        instrumentation.context.assets.open("smoke.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        fun channel(id:String,name:String)=Channel(name,"${video.toURI()}#$id","Esportes",tvgId=id.substringBefore('-'),typeHint="live")
        val channels=listOf(channel("nba-hd","Aurora HD"),channel("nba-fhd","Aurora FHD"),channel("f1","Aurora Motor"))
        val basketball=Programme("Alpha x Beta - NBA",now-60000,now+3600000,broadcast=BroadcastHint.LIVE)
        val practice=Programme("F1 - GP do Brasil - Treino Livre 1",now+3600000,now+7200000)
        val qualifying=Programme("F1 - GP do Brasil - Classificação",now+86400000,now+86400000+7200000)
        val guide=Guide(matches=listOf(GuideMatch("nba",basketball),GuideMatch("f1",practice),GuideMatch("f1",qualifying)))
        val tennis=channel("tennis","Aurora Tênis")
        val secondGuide=Guide(matches=listOf(GuideMatch("tennis",Programme("Aline x Bia - WTA",now+3600000,now+7200000))))
        PlaylistRepository(context).also { repo ->
            repo.saveSource(first,"Esportes A");repo.appendCache(first,Download(Playlist(channels),"",""));repo.saveGuide(first,guide)
            repo.saveFavorites(setOf(channels.first().url));repo.recordAttempt(first,now)
            repo.saveSource(second,"Esportes B");repo.appendCache(second,Download(Playlist(listOf(tennis)),"",""));repo.saveGuide(second,secondGuide)
            repo.recordAttempt(second,now);repo.saveSource(first,"Esportes A")
            repo.saveArtwork("sports:teams:espn:basketball/nba","{\"teams\":[]}")
            listOf("alpha","beta").forEach {repo.saveArtwork("sports:team:tsdb:BASKETBALL:$it","{\"badge\":\"\"}")}
            repo.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized && model.sportsHub.disciplines.size==2}
            scenario.onActivity {model.play(channels.first());model.page=Page.HOME}
            compose.onNodeWithTag("home_football").performClick()
            compose.onNodeWithTag("sports_kind_football").assertDoesNotExist()
            compose.openSportsDiscipline("basketball")
            compose.openSportsCompetition("nba")
            compose.onNodeWithTag("sports_day_header").assertExists()
            compose.onAllNodesWithTag("sports_event").assertCountEquals(1)
            compose.onNodeWithTag("football_all_channels").performScrollTo().performClick()
            val inPicker=hasAnyAncestor(hasTestTag("football_channel_picker"))
            compose.onNode(inPicker and hasText("Aurora HD")).assertExists()
            compose.onNode(inPicker and hasText("Aurora FHD")).assertExists()
            UiDevice.getInstance(instrumentation).pressBack()
            // Background preview must not disable the Android Back hierarchy.
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(10000) {model.page==Page.FOOTBALL && model.sportsCompetitionId==null}
            compose.openSportsCompetition("nba")
            compose.onNodeWithTag("football_search").performTextInput("Alpha")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("football_watch_0").performScrollTo().performClick()
            compose.waitUntil(15000) {model.isPlaying && model.fromFootball}
            scenario.onActivity {model.player!!.repeatMode=Player.REPEAT_MODE_ONE}
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(10000) {model.page==Page.FOOTBALL && model.current==null}
            assertEquals("nba",model.sportsCompetitionId);assertEquals("Alpha",model.footballSearch)
            compose.onNodeWithTag("football_search").assertTextContains("Alpha")
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;model.chooseAppearance(AppAppearance.LIGHT)}
            compose.waitUntil(10000) {model.appearance==AppAppearance.LIGHT &&
                context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                compose.onAllNodesWithTag("sports_event").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("sports_event").assertExists()
            scenario.onActivity {model.savePlaylist(second,"Esportes B")}
            compose.waitUntil(15000) {model.playlistUrl==second && !model.switchingPlaylist &&
                model.sportsHub.disciplines.singleOrNull()?.sport==SportKind.TENNIS}
            assertNull(model.sportsDisciplineId);assertNull(model.sportsCompetitionId)
            scenario.onActivity {model.showFootball()}
            compose.openSportsDiscipline("tennis");compose.openSportsCompetition("tennis.wta")
            compose.onNodeWithText("Alpha").assertDoesNotExist()
            compose.onNodeWithText("Aline").assertExists()
            // Ensure the retained Activity ViewModel closes its SQLite handles
            // before this test reopens the repository to check persistence.
            scenario.onActivity { it.viewModelStore.clear() }
        }
        PlaylistRepository(context).also { repo ->
            assertEquals(channels.size,repo.overview(first).total)
            assertTrue(channels.first().url in repo.favorites())
            assertEquals(guide.matches.toSet(),repo.savedGuide(first).matches.toSet())
            repo.close()
        }
    }
}
