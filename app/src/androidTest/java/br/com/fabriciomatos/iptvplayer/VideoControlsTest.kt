package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
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

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoControlsTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun actualAudioAndSubtitleSelectionWorksAndOverlayFadesThenReturnsOnTap() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val video=File(context.cacheDir,"tracks.mp4")
        instrumentation.context.assets.open("tracks.mp4").use {input->video.outputStream().use {output->input.copyTo(output)}}
        val source="https://example.test/list.m3u"
        val movie=Channel("Vídeo com idiomas",video.toURI().toString(),"Filmes",typeHint="movie")
        PlaylistRepository(context).also {it.saveSource(source,"Lista de teste");it.appendCache(source,Download(Playlist(listOf(movie)),"",""));it.close()}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
            scenario.onActivity {model.play(movie);model.player!!.repeatMode=Player.REPEAT_MODE_ONE}
            compose.waitUntil(20000) {
                var ready=false;scenario.onActivity {ready=model.isPlaying && model.player!!.currentTracks.groups.count {g->g.type==C.TRACK_TYPE_AUDIO}==2};ready
            }
            reveal()
            compose.onNodeWithTag("audio_menu").assertExists()
            compose.onNodeWithTag("subtitle_menu").assertExists()
            assertTrue(compose.onNodeWithTag("audio_menu").fetchSemanticsNode().boundsInRoot.top>=compose.onNodeWithTag("video_time").fetchSemanticsNode().boundsInRoot.bottom)
            compose.onNodeWithTag("audio_menu").performClick()
            compose.onNodeWithText("Inglês").performClick()
            compose.waitUntil(5000) {
                var selected=false;scenario.onActivity {selected=model.player!!.currentTracks.groups.any {g->g.type==C.TRACK_TYPE_AUDIO && g.isSelected && g.getTrackFormat(0).language in listOf("eng","en")}};selected
            }
            reveal()
            compose.onNodeWithTag("subtitle_menu").performClick()
            compose.onNodeWithText("Português").performClick()
            compose.waitUntil(5000) {
                var selected=false;scenario.onActivity {selected=model.player!!.currentTracks.groups.any {g->g.type==C.TRACK_TYPE_TEXT && g.isSelected}};selected
            }
            val device=UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
            device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/player-tracks.png")
            compose.waitUntil(5000) {var cue=false;scenario.onActivity {cue=model.player!!.currentCues.cues.any {it.text?.contains("Legenda de teste Aurora")==true}};cue}
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;model.fullscreen=true}
            compose.waitUntil(10000) {context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            // Advance animation frames alongside wall time instead of auto-fast-forwarding the fade.
            compose.mainClock.autoAdvance=false
            try {
                reveal()
                for(i in 0 until 40) {android.os.SystemClock.sleep(50);compose.mainClock.advanceTimeBy(50)}
                compose.onNodeWithTag("video_back").assertExists()
                compose.onNodeWithTag("video_fullscreen").assertExists()
                for(i in 0 until 68) {android.os.SystemClock.sleep(50);compose.mainClock.advanceTimeBy(50)}
                compose.onNodeWithTag("video_back").assertDoesNotExist()
                compose.onNodeWithTag("video_fullscreen").assertDoesNotExist()
                device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/fullscreen-no-controls.png")
                reveal();compose.mainClock.advanceTimeBy(200)
                compose.onNodeWithTag("video_back").assertExists()
                compose.onNodeWithTag("video_fullscreen").assertExists()
            } finally {compose.mainClock.autoAdvance=true}
        }
    }
    private fun reveal()=compose.onNodeWithTag("video_surface").performTouchInput {click(Offset(size.width*.5f,size.height*.22f))}
}
