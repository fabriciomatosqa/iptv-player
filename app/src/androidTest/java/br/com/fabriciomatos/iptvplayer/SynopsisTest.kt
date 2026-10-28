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
            assertEquals("História do filme de teste.",runBlocking { model.synopsis(movie,null).plot })
            compose.onNodeWithText(movie.name).assertIsDisplayed()
            compose.onNodeWithTag("synopsis_content_action",useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            try {awaitSynopsis("História do filme de teste.")}
            catch(e: androidx.compose.ui.test.ComposeTimeoutException) {screenshot("movie-synopsis-failure");throw e}
            assertNull(model.current);screenshot("movie-synopsis")
            closeSynopsis(scenario)
            scenario.onActivity {model.browse(MediaKind.SERIES)}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Série de teste").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Série de teste").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("História da série de teste.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("landscape_details_play").performScrollTo().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("episode_card_1_1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("synopsis_content_action",useUnmergedTree=true)
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            awaitSynopsis("História do episódio piloto.")
            compose.onNode(synopsisText("História da série de teste."),useUnmergedTree=true).assertExists()
            assertNull(model.current);screenshot("series-episode-synopsis")
            closeSynopsis(scenario)
            // Exercise the accessible button action after native dialog removal; input dispatch can lag window focus.
            compose.onNodeWithTag("synopsis_series_action",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            awaitSynopsis("História da série de teste.")
            compose.onNodeWithTag("episode_synopsis").assertDoesNotExist()
        }
    }
    @Test fun providerCinemaMetadataAndActorImagesSurviveCacheAndOlderJson() {
        val provider = JSONObject(
            """{"info":{"plot":"Uma aventura.","backdrop_path":["/cinema-backdrop.jpg"],
              "actors":[{"name":"Atriz A","profile_path":"/cast-a.jpg"},
                        {"name":"Ator B"}],
              "rating":"7.8","releasedate":"2026-06-01",
              "duration":"02:03:00","genre":"Aventura","director":"Diretora A"}}"""
        )
        val details = parseContentDetails(provider)
        assertEquals("Uma aventura.", details.plot)
        assertEquals("https://image.tmdb.org/t/p/w1280/cinema-backdrop.jpg", details.backdropUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/cast-a.jpg", details.cast.first().imageUrl)
        assertEquals("Ator B", details.cast.last().name)
        assertEquals("7.8", details.rating)
        assertEquals("2026-06-01", details.year)
        assertEquals("02:03:00", details.duration)
        assertEquals(details, decodeContentDetails(encodeContentDetails(details)))

        // Existing 1.12.x cache rows must remain readable without a database migration.
        val old = JSONObject("""{"plot":"Sinopse anterior","episodes":[]}""")
        val restored = decodeContentDetails(old)
        assertEquals("Sinopse anterior", restored.plot)
        assertTrue(restored.cast.isEmpty())
        assertTrue(restored.backdropUrl.isBlank())
        assertEquals("", providerImageUrl("javascript:alert(1)"))
    }

    private fun awaitSynopsis(text: String) {
        compose.onNodeWithTag("synopsis_dialog",useUnmergedTree=true).assertExists()
        compose.waitUntil(30000) {
            // IO completion and the native dialog's recomposition may land on different frames.
            // Advance several frames explicitly, then synchronize before reading the dialog tree.
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            compose.onAllNodes(synopsisText(text),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(synopsisText(text),useUnmergedTree=true).assertIsDisplayed()
    }
    private fun synopsisText(text: String)=hasText(text) and hasAnyAncestor(hasTestTag("synopsis_dialog"))
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
