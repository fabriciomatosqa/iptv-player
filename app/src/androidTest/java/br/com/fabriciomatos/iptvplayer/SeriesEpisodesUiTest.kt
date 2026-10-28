package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Local metadata, distinct scene images and a real seekable video; no external API is needed. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeriesEpisodesUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun episodesReplaceCastScrollInBothDirectionsAndKeepTheirPlaceAfterPlaybackAndRotation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        resetCatalog(context)
        val strings=AppSettings(context)
        val video=File(context.cacheDir,"series-scenes.mp4")
        instrumentation.context.assets.open("tracks.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        val requests=ConcurrentHashMap.newKeySet<String>()
        val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val base="http://127.0.0.1:${server.localPort}"
        thread(isDaemon=true,name="series-scene-fixture") {
            while(!server.isClosed)try {server.accept().use {socket->
                socket.soTimeout=3000
                val reader=socket.getInputStream().bufferedReader()
                val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()) { }
                val path=request.split(' ').getOrNull(1).orEmpty()
                requests.add(path)
                val bitmap=Bitmap.createBitmap(96,54,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(75+(path.hashCode() and 127),80,140))
                val body=ByteArrayOutputStream().use {bytes->bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bytes.toByteArray()}
                bitmap.recycle()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body);flush()
                }
            }}catch(_:Exception) { }
        }
        val source="$base/get.php?username=test&password=test&type=m3u_plus"
        val channels=(1..3).flatMap {season->(1..8).map {number->
            Channel("Série de referência S${season.toString().padStart(2,'0')}E${number.toString().padStart(2,'0')}",
                "${video.toURI()}#s${season}e$number","Séries",seriesId="555",seriesTitle="Série de referência",
                seasonNumber=season,episodeNumber=number,typeHint="series")
        }}
        val details=ContentDetails(plot="A sinopse da série permanece enquanto as temporadas são exibidas.",
            cast=listOf(CastMember("Atriz da referência","$base/cast.png")),
            episodes=channels.map {channel->
                val e=seriesIdentity(channel).episode
                EpisodeSynopsis("",e.season,e.number,"Cena ${e.season}-${e.number}","Enredo ${e.number}",
                    "$base/scene-${e.season}-${e.number}.png",30000)
            })
        PlaylistRepository(context).also {repository->
            repository.saveSource(source,"Carrosséis")
            repository.appendCache(source,Download(Playlist(channels),"",""))
            repository.recordAttempt(source)
            repository.saveMetadata(source,ProviderMetadata(posters=mapOf(normalized("Série de referência") to
                SeriesPoster("Série de referência","","555"))))
            repository.saveGuide(source,Guide())
            repository.saveArtwork(synopsisCacheKey(source,false,"555"),encodeContentDetails(details).toString())
            repository.savePlaybackProgress(source,channels[1].url,PlaybackProgress(15000,30000))
            repository.savePlaybackProgress(source,channels[2].url,PlaybackProgress(30000,30000,true))
            repository.close()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {
                    model=ViewModelProvider(it)[AuroraViewModel::class.java]
                    it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                compose.waitUntil(15000) {model.initialized && model.catalog.total==24 && context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
                scenario.onActivity {model.browse(MediaKind.SERIES)}
                compose.waitUntil(10000) {compose.onAllNodesWithText("Série de referência").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Série de referência").performClick()
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(1)
                compose.waitUntil(10000) {compose.onAllNodesWithTag("landscape_details_cast").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Atriz da referência").assertIsDisplayed()
                compose.onNodeWithTag("episode_row_1").assertDoesNotExist()
                assertNull(model.current)
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(0)
                compose.onNodeWithTag("landscape_details_play").performClick()
                compose.waitUntil(10000) {model.seriesDetails.episodesVisible && compose.onAllNodesWithTag("episode_card_1_1").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("landscape_details_cast").assertDoesNotExist()
                // Reproduce Back arriving after playback changes fullscreen but before
                // Compose disposes the details screen. It must not consume the episode section.
                fun backDuringFullscreenTransition() {
                    scenario.onActivity {activity ->
                        val scroll=model.seriesDetails.scroll
                        model.fullscreen=true
                        activity.onBackPressedDispatcher.onBackPressed()
                        assertFalse(model.fullscreen)
                        assertTrue(model.seriesDetails.episodesVisible)
                        assertEquals("id:555",model.seriesDetails.series?.id)
                        assertSame(scroll,model.seriesDetails.scroll)
                    }
                }
                backDuringFullscreenTransition()
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(2)
                compose.onNodeWithTag("episode_row_1").performScrollToIndex(0)
                awaitProgress(1,1,0f)
                assertFlag(1,1,strings.text(R.string.episode_unfinished),Color(0xFFFFCF58))
                scene(1,1,strings.text(R.string.episode_scene,1)).assertExists()
                compose.waitUntil(10000) {requests.contains("/scene-1-1.png")}
                compose.onNodeWithTag("episode_row_1").performScrollToIndex(1)
                awaitProgress(1,2,.5f)
                assertFlag(1,2,strings.text(R.string.episode_unfinished),Color(0xFFFFCF58))
                scene(1,2,strings.text(R.string.episode_scene,2)).assertExists()
                compose.waitUntil(10000) {requests.contains("/scene-1-2.png")}
                compose.onNodeWithTag("episode_row_1").performScrollToIndex(2)
                awaitProgress(1,3,1f)
                assertFlag(1,3,strings.text(R.string.episode_completed),Color(0xFF62E8A7))
                snapshot("portrait-episode-progress")

                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(1)
                compose.onNodeWithTag("episode_season_navigation").performScrollToNode(hasTestTag("episode_season_jump_3"))
                compose.onNodeWithTag("episode_season_jump_3").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithTag("episode_row_3").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("episode_row_3").performScrollToIndex(4)
                compose.onNodeWithTag("episode_card_3_5").assertIsDisplayed()
                compose.waitUntil(10000) {model.seriesDetails.row(3).firstVisibleItemIndex==4}
                val vertical=model.seriesDetails.scroll.firstVisibleItemIndex
                val offset=model.seriesDetails.row(3).firstVisibleItemScrollOffset
                compose.onNodeWithTag("episode_card_3_5").performClick()
                compose.waitUntil(20000) {
                    var ready=false
                    scenario.onActivity {ready=model.fullscreen && model.current?.url==channels[20].url && model.player?.playbackState==Player.STATE_READY}
                    ready
                }
                scenario.onActivity {model.player!!.pause();model.player!!.seekTo(12000)}
                compose.waitUntil(10000) {var reached=false;scenario.onActivity {reached=model.player!!.contentPosition in 11500L..12500L};reached}
                compose.awaitFullscreenPlayer()
                UiDevice.getInstance(instrumentation).pressBack()
                compose.awaitEpisodeReturn(model,"episode_card_3_5")
                assertTrue(model.seriesDetails.episodesVisible)
                assertEquals("id:555",model.seriesDetails.series?.id)
                assertEquals(vertical,model.seriesDetails.scroll.firstVisibleItemIndex)
                assertEquals(4,model.seriesDetails.row(3).firstVisibleItemIndex)
                assertEquals(offset,model.seriesDetails.row(3).firstVisibleItemScrollOffset)
                awaitProgress(3,5,.4f)
                scenario.onActivity {model.stop();it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                compose.waitUntil(10000) {context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && compose.onAllNodesWithTag("episode_card_3_5").fetchSemanticsNodes().isNotEmpty()}
                assertTrue(model.seriesDetails.episodesVisible)
                assertEquals(4,model.seriesDetails.row(3).firstVisibleItemIndex)
                awaitProgress(3,5,.4f)
                snapshot("landscape-season-carousels")
                backDuringFullscreenTransition()
                scenario.recreate()
                compose.waitUntil(10000) {compose.onAllNodesWithTag("episode_card_3_5").fetchSemanticsNodes().isNotEmpty()}
                assertEquals("id:555",model.seriesDetails.series?.id)
                assertTrue(model.seriesDetails.episodesVisible)
                assertEquals(4,model.seriesDetails.row(3).firstVisibleItemIndex)
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(0)
                compose.onNodeWithTag("series_show_cast").performClick()
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(1)
                compose.waitUntil(10000) {compose.onAllNodesWithTag("landscape_details_cast").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Atriz da referência").assertIsDisplayed()
                compose.onNodeWithTag("episode_row_3").assertDoesNotExist()
                compose.onNodeWithTag("title_details_scroll").performScrollToIndex(0)
                compose.onNodeWithTag("landscape_details_synopsis").assertTextContains(details.plot)
                assertNull(model.current)
            }
            PlaylistRepository(context).also {repository->
                val saved=repository.playbackProgress(source,channels[20].url)
                assertNotNull(saved);assertTrue(saved!!.positionMs in 11500L..12500L)
                assertFalse(saved.completed)
                repository.close()
            }
        } finally {server.close()}
    }

    private fun scene(season: Int,number: Int,description: String)=compose.onNode(
        hasContentDescription(description) and hasAnyAncestor(hasTestTag("episode_card_${season}_$number")),useUnmergedTree=true)

    private fun awaitProgress(season: Int,number: Int,fraction: Float) {
        val tag="episode_progress_${season}_$number"
        compose.waitUntil(10000) {
            val info=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            info!=null && kotlin.math.abs(info.current-fraction)<.02f
        }
        compose.onNodeWithTag(tag,useUnmergedTree=true).assert(SemanticsMatcher("Correct watch percentage and range") {node->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)?.let {
                it.range==0f..1f && kotlin.math.abs(it.current-fraction)<.02f
            }==true
        })
    }

    private fun assertFlag(season: Int,number: Int,status: String,color: Color) {
        val node=compose.onNodeWithTag("episode_flag_${season}_$number",useUnmergedTree=true)
        node.assert(hasContentDescription(status) or hasAnyDescendant(hasContentDescription(status)))
        val pixels=node.captureToImage().toPixelMap()
        var found=false
        for(y in 0 until pixels.height)for(x in 0 until pixels.width) {
            val pixel=pixels[x,y]
            if(kotlin.math.abs(pixel.red-color.red)<.02f && kotlin.math.abs(pixel.green-color.green)<.02f &&
                kotlin.math.abs(pixel.blue-color.blue)<.02f)found=true
        }
        assertTrue("The episode flag must use its yellow/green watch-state color",found)
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        val path="/sdcard/Download/aurora-ui/$name.png"
        device.executeShellCommand("screencap -p $path")
        assertTrue(device.executeShellCommand("ls $path").contains("$name.png"))
    }
}
