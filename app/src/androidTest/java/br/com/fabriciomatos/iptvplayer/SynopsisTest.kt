package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SynopsisTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun providerIndexResolvesOpaqueMoviesAndDetailsSurviveOfflineReopening() = runBlocking {
        resetCatalog(context)
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val indexCalls=AtomicInteger()
        val source="http://127.0.0.1:${server.localPort}/get.php?username=test&password=test&type=m3u_plus"
        thread(isDaemon=true) {while(!server.isClosed)try {server.accept().use {socket->
            val reader=socket.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()) { }
            indexCalls.incrementAndGet()
            val body="""[{"name":"Filme (2026)","title":"Filme","stream_id":123},{"name":"Filme (2020)","title":"Filme","stream_id":456}]""".toByteArray()
            socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
        }}catch(_:Exception) { }}
        try {
            val repository=PlaylistRepository(context);var detailsCalls=0
            val channel=Channel("Filme (2026)","https://example.test/opaque-video.mkv","Filmes",typeHint="movie")
            val loader=SynopsisRepository(repository) {url->
                assertTrue(url.startsWith("http://127.0.0.1:"));assertTrue(url.contains("vod_id=123"));detailsCalls++
                JSONObject("""{"info":{"plot":"","description":"<p>Sinopse correta &amp; completa.</p>"}}""")
            }
            assertEquals("Sinopse correta & completa.",loader.load(source,channel).plot)
            assertEquals("Sinopse correta & completa.",loader.load(source,channel).plot)
            assertEquals(1,indexCalls.get());assertEquals(1,detailsCalls)
            assertNull(repository.synopsisMovieId(source,"Filme"));assertEquals("456",repository.synopsisMovieId(source,"Filme (2020)"))
            repository.close();server.close()
            val reopened=PlaylistRepository(context)
            val offline=SynopsisRepository(reopened) {error("Offline")}
            assertEquals("Sinopse correta & completa.",offline.load(source,channel,force=true).plot)
            val seriesJson=JSONObject("""{"info":{"plot":"Sinopse da série."},"episodes":{"1":[{"id":"10","episode_num":1,"season":1,"title":"Piloto","info":{"description":"Sinopse do piloto."}}],"2":[{"id":"20","episode_num":1,"season":2,"title":"Retorno","info":{"plot":"Sinopse do retorno."}}]}}""")
            val details=parseContentDetails(seriesJson)
            assertEquals(details,decodeContentDetails(encodeContentDetails(details)))
            val series=SeriesEntry("id:5","Série de teste","",emptySet(),emptyList())
            reopened.saveArtwork(synopsisCacheKey(source,false,"5"),encodeContentDetails(details).toString())
            val episode=Channel("Série de teste S02E01","https://example.test/opaque.mkv","Séries",seriesId="5")
            val result=offline.load(source,episode,series)
            assertEquals("Sinopse da série.",result.plot);assertEquals("Sinopse do retorno.",result.episode?.plot)
            assertNull(offline.load(source,episode.copy(name="Série de teste S02E02"),series).episode)
            reopened.close()
        } finally {server.close()}
    }
    @Test fun movieSeriesAndEpisodeSynopsesCanBeReadWithoutStartingPlayback() {
        resetCatalog(context)
        val source="https://example.test/get.php?username=test&password=test&type=m3u_plus"
        val movie=Channel("Filme de teste","https://example.test/movie/test/test/123.mkv","Drama")
        val episode=Channel("Série de teste S01E01","https://example.test/series/test/test/124.mkv","Drama",seriesId="5")
        val repository=PlaylistRepository(context)
        repository.saveSource(source,"Catálogo de teste");repository.appendCache(source,Download(Playlist(listOf(movie,episode)),"",""))
        // This case verifies cached details, independently of the daily network refresh.
        repository.recordAttempt(source)
        repository.saveMetadata(source,ProviderMetadata(posters=mapOf(normalized("Série de teste") to SeriesPoster("Série de teste","","5"))))
        repository.saveArtwork(synopsisCacheKey(source,true,"123"),encodeContentDetails(ContentDetails("História do filme de teste.")).toString())
        repository.saveArtwork(synopsisCacheKey(source,false,"5"),encodeContentDetails(ContentDetails("História da série de teste.",
            listOf(EpisodeSynopsis("124",1,1,"O piloto","História do episódio piloto.")))).toString());repository.close()
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized}
            scenario.onActivity {model.browse(MediaKind.MOVIES)}
            compose.waitUntil(10000) {!model.filtering && model.visibleCount==1 && compose.onAllNodesWithText(movie.name).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("synopsis_content_action",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(movie.name).assertIsDisplayed()
            compose.onNodeWithTag("synopsis_content_action",useUnmergedTree=true).performClick()
            try {compose.waitUntil(30000) {compose.onAllNodesWithText("História do filme de teste.").fetchSemanticsNodes().isNotEmpty()}}
            catch(e: androidx.compose.ui.test.ComposeTimeoutException) {screenshot("movie-synopsis-failure");throw e}
            assertNull(model.current);screenshot("movie-synopsis")
            closeSynopsis(scenario)
            scenario.onActivity {model.browse(MediaKind.SERIES)}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Série de teste").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Série de teste").performClick()
            compose.waitUntil(10000) {!model.filtering && compose.onAllNodesWithText(episode.name).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("synopsis_content_action",useUnmergedTree=true).performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("História do episódio piloto.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("História da série de teste.").assertExists()
            assertNull(model.current);screenshot("series-episode-synopsis")
            closeSynopsis(scenario)
            // Exercise the accessible button action after native dialog removal; input dispatch can lag window focus.
            compose.onNodeWithTag("synopsis_series_action",useUnmergedTree=true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(10000) {compose.onAllNodesWithText("História da série de teste.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("episode_synopsis").assertDoesNotExist()
        }
    }
    private fun closeSynopsis(scenario: ActivityScenario<MainActivity>) {
        compose.onNodeWithText("Fechar").performClick()
        // The native dialog window can still own input after its Compose nodes disappear.
        compose.waitUntil(5000) {
            var focused=false
            scenario.onActivity {focused=it.window.decorView.hasWindowFocus()}
            focused && compose.onAllNodesWithTag("synopsis_dialog").fetchSemanticsNodes().isEmpty()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui");device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/$name.png")
    }
}
