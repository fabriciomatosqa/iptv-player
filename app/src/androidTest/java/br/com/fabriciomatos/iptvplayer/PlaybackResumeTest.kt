package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackResumeTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun openingWatchedContentAsksBeforePlayingAndHonorsContinueRestartCancelAndPlaylistIsolation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val file=File(context.cacheDir,"resume-choices.mp4")
        instrumentation.context.assets.open("tracks.mp4").use {input->file.outputStream().use {input.copyTo(it)}}
        val source="local-m3u://00000000-0000-0000-0000-000000000041"
        val other="local-m3u://00000000-0000-0000-0000-000000000042"
        val first=Channel("Série de escolhas S01E01","${file.toURI()}#choices-first","Séries",typeHint="series")
        val completed=first.copy(name="Série de escolhas S01E02",url="${file.toURI()}#choices-completed")
        val fresh=first.copy(name="Série de escolhas S01E03",url="${file.toURI()}#choices-new")
        val live=Channel("Canal sem pergunta","${file.toURI()}#choices-live","Canais",kind=MediaKind.LIVE)
        val repository=PlaylistRepository(context)
        try {
            for(list in listOf(source,other)) {
                repository.saveSource(list,"Escolhas")
                repository.appendCache(list,Download(Playlist(listOf(first,completed,fresh,live)),"",""))
            }
            repository.savePlaybackProgress(source,first.url,PlaybackProgress(12000,30000))
            repository.savePlaybackProgress(source,completed.url,PlaybackProgress(30000,30000,true))
            repository.savePlaybackProgress(other,first.url,PlaybackProgress(3000,30000))
            repository.saveSource(source,"Escolhas")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized && model.catalog.total==4}
                fun ready(channel: Channel,position: (Long)->Boolean) {
                    compose.waitUntil(20000) {
                        var result=false
                        scenario.onActivity {
                            result=model.current?.url==channel.url && model.player?.playbackState==Player.STATE_READY &&
                                position(model.player!!.contentPosition)
                        }
                        result
                    }
                    scenario.onActivity {model.player!!.pause()}
                }
                fun ask(fullscreen: Boolean = false) {
                    scenario.onActivity {model.requestPlay(first,fullscreen)}
                    compose.waitUntil(10000) {model.pendingPlayback?.channel?.url==first.url}
                    compose.onNodeWithTag("resume_playback_dialog").assertExists()
                }
                ask(true)
                assertNull(model.current);assertNull(model.player);assertFalse(model.fullscreen)
                compose.onNodeWithText("Série de escolhas S01E01\nVocê parou em 00:12.").assertExists()
                compose.onNodeWithTag("resume_playback_cancel").performClick()
                compose.waitUntil(5000) {model.pendingPlayback==null}
                assertNull(model.current)
                assertEquals(12000L,repository.playbackProgress(source,first.url)!!.positionMs)
                // Leaving the section must also cancel the bookmark lookup before a dialog exists.
                scenario.onActivity {model.requestPlay(first,true);model.showSeriesCast()}
                compose.waitForIdle()
                assertNull(model.pendingPlayback);assertNull(model.current);assertFalse(model.fullscreen)
                scenario.onActivity {model.requestPlay(first,true);model.closeSeriesDetails()}
                compose.waitForIdle()
                assertNull(model.pendingPlayback);assertNull(model.current);assertFalse(model.fullscreen)
                ask(true)
                compose.onNodeWithTag("resume_playback_continue").performClick()
                ready(first) {it>=11500}
                assertTrue(model.fullscreen)
                scenario.onActivity {model.stop()}
                ask()
                compose.onNodeWithTag("resume_playback_start").performClick()
                ready(first) {it<2000}
                scenario.onActivity {model.stop()}
                compose.waitUntil(10000) {repository.playbackProgress(source,first.url)!!.positionMs<2000}
                scenario.onActivity {model.requestPlay(completed)}
                ready(completed) {it<2000}
                assertNull(model.pendingPlayback)
                scenario.onActivity {model.requestPlay(fresh)}
                ready(fresh) {it<2000}
                assertNull(model.pendingPlayback)
                scenario.onActivity {model.requestPlay(live)}
                ready(live) {true}
                assertNull(model.pendingPlayback)
                scenario.onActivity {model.stop()}
                // The pending request belongs to its original playlist and cannot start after switching.
                repository.savePlaybackProgress(source,first.url,PlaybackProgress(12000,30000))
                scenario.onActivity {model.play(first)}
                ready(first) {true}
                scenario.onActivity {model.player!!.seekTo(12000)}
                compose.waitUntil(10000) {var reached=false;scenario.onActivity {reached=model.player!!.contentPosition>=11500};reached}
                scenario.onActivity {model.stop()}
                ask()
                scenario.onActivity {model.switchPlaylist(SavedPlaylist(other,"Outra lista"))}
                compose.waitUntil(15000) {model.playlistUrl==other && !model.switchingPlaylist}
                assertNull(model.pendingPlayback);assertNull(model.current)
                ask()
                assertEquals(3000L,model.pendingPlayback!!.progress.positionMs)
                compose.onNodeWithTag("resume_playback_cancel").performClick()
                compose.waitUntil(5000) {model.pendingPlayback==null}
                scenario.onActivity {model.requestPlay(first.copy(adult=true))}
                compose.waitUntil(5000) {model.parentalDialog}
                assertNull(model.pendingPlayback);assertNull(model.current)
                scenario.onActivity {model.dismissParental()}
            }
        } finally {repository.close()}
    }
    @Test fun realMoviesAndEpisodesResumeIndependentlyAfterSwitchingAndClosingTheActivity() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val file=File(context.cacheDir,"resume.mp4")
        instrumentation.context.assets.open("tracks.mp4").use {input->file.outputStream().use {input.copyTo(it)}}
        val source="https://example.test/resume.m3u"
        val movie=Channel("Filme com retomada","${file.toURI()}#movie","Filmes",typeHint="movie")
        val first=Channel("Série com retomada S01E01","${file.toURI()}#episode1","Séries",typeHint="series")
        val second=first.copy(name="Série com retomada S01E02",url="${file.toURI()}#episode2")
        val live=Channel("Canal ao vivo","${file.toURI()}#live","Canais",kind=MediaKind.LIVE)
        PlaylistRepository(context).also {it.saveSource(source,"Retomada");it.appendCache(source,Download(Playlist(listOf(movie,first,second,live)),"",""));it.close()}
        val repository=PlaylistRepository(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized && model.catalog.total==4}
                fun open(channel: Channel, expected: Long = 0L) {
                    scenario.onActivity {model.play(channel)}
                    compose.waitUntil(20000) {var ready=false;scenario.onActivity {ready=model.player?.playbackState==Player.STATE_READY && model.player!!.contentPosition>=expected};ready}
                    scenario.onActivity {model.player!!.pause()}
                }
                fun seek(position: Long) {
                    scenario.onActivity {model.player!!.seekTo(position)}
                    compose.waitUntil(10000) {var there=false;scenario.onActivity {there=kotlin.math.abs(model.player!!.contentPosition-position)<500 && model.player!!.playbackState==Player.STATE_READY};there}
                }
                open(movie);seek(12000)
                open(first);seek(8000)
                open(second);seek(4000)
                open(movie,11500);seek(3000) // Backward seeks replace a later bookmark.
                scenario.onActivity {model.stop()}
                compose.waitUntil(10000) {repository.playbackProgress(source,movie.url)?.positionMs in 2900L..3500L}
                assertTrue(repository.playbackProgress(source,first.url)!!.positionMs in 7900L..8500L)
                assertTrue(repository.playbackProgress(source,second.url)!!.positionMs in 3900L..4500L)
                assertNull(repository.playbackProgress("https://other.test/list.m3u",movie.url))
            }
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized}
                scenario.onActivity {model.play(movie)}
                compose.waitUntil(20000) {var ready=false;scenario.onActivity {ready=model.player?.playbackState==Player.STATE_READY && model.player!!.contentPosition>=2900};ready}
                scenario.onActivity {model.player!!.pause();model.player!!.seekTo(29500);model.player!!.play()}
                compose.waitUntil(10000) {var ended=false;scenario.onActivity {ended=model.player?.playbackState==Player.STATE_ENDED};ended}
                compose.waitUntil(10000) {repository.playbackProgress(source,movie.url)?.completed==true}
                scenario.onActivity {model.play(movie)}
                compose.waitUntil(20000) {var restarted=false;scenario.onActivity {restarted=model.player?.playbackState==Player.STATE_READY && model.player!!.contentPosition<2000};restarted}
                scenario.onActivity {model.play(live)}
                compose.waitUntil(20000) {var ready=false;scenario.onActivity {ready=model.player?.playbackState==Player.STATE_READY};ready}
                scenario.onActivity {model.player!!.seekTo(5000);model.stop()}
                assertNull(repository.playbackProgress(source,live.url))
            }
        } finally {repository.close()}
    }
}
