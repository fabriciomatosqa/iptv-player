package br.com.fabriciomatos.iptvplayer

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
