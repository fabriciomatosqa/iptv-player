package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real HTTP playback, category navigation, reopening and a delayed daily refresh. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuroraUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun catalogSurvivesReopeningAndDailyRefreshKeepsOldContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = instrumentation.context.assets.open("smoke.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val requests = AtomicInteger()
        val dailyStarted = java.util.concurrent.CountDownLatch(1)
        val releaseDaily = java.util.concurrent.CountDownLatch(1)
        val initial = """
            #EXTM3U url-tvg="$base/guide.xml"
            #EXTINF:-1 tvg-id="aurora" group-title="Brasil",TV Aurora
            $base/live/clip.mp4
            #EXTINF:-1 group-title="Filmes",Luz do Norte
            $base/movie/clip.mp4
            #EXTINF:-1 group-title="Séries",Rick e Morty S01E01
            $base/series/s1e1.mp4
            #EXTINF:-1 group-title="Séries",Rick e Morty S01E02
            $base/series/s1e2.mp4
            #EXTINF:-1 group-title="Séries",Rick e Morty S02E01
            $base/series/s2e1.mp4
            #EXTINF:-1 group-title="Séries",Outra Série S01E01
            $base/series/other.mp4
        """.trimIndent()
        val playlist = AtomicReference(initial)
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val guide = """<tv><channel id="aurora"><display-name>TV Aurora</display-name></channel><programme channel="aurora" start="${date.format(Date(now - 3600000))}" stop="${date.format(Date(now + 3600000))}"><title>Futebol: Aurora x Norte</title></programme><programme channel="aurora" start="${date.format(Date(now + 3600000))}" stop="${date.format(Date(now + 7200000))}"><title>Viagem ao norte</title></programme><programme channel="aurora" start="${date.format(Date(now + 2 * 86400000L))}" stop="${date.format(Date(now + 2 * 86400000L + 7200000L))}"><title>Champions League: Leste vs Oeste</title></programme></tv>"""
        thread(isDaemon = true, name = "aurora-test-http") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine().orEmpty()
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val path = first.split(' ').getOrNull(1).orEmpty()
                        if (path.contains("list.m3u") && requests.incrementAndGet() == 2) {
                            dailyStarted.countDown()
                            check(releaseDaily.await(25, java.util.concurrent.TimeUnit.SECONDS)) { "Daily response was not released by the test" }
                        }
                        val body = when { path.contains("guide.xml") -> guide.toByteArray(); path.contains("list.m3u") -> playlist.get().toByteArray(); else -> video }
                        val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()?.coerceIn(0, body.lastIndex) ?: 0
                        val mime = when { path.endsWith(".xml") -> "application/xml"; path.endsWith(".m3u") -> "audio/x-mpegurl"; else -> "video/mp4" }
                        val output = socket.getOutputStream()
                        val status = if (range > 0) "206 Partial Content" else "200 OK"
                        val contentRange = if (range > 0) "Content-Range: bytes $range-${body.lastIndex}/${body.size}\r\n" else ""
                        output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size - range}\r\nAccept-Ranges: bytes\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
                        if (!first.startsWith("HEAD")) output.write(body, range, body.size - range)
                        output.flush()
                    }
                } catch (_: Exception) { }
            }
        }
        resetCatalog(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: AuroraViewModel
        try {
            scenario.onActivity { model = ViewModelProvider(it)[AuroraViewModel::class.java]; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15000) { model.initialized }
            compose.onNodeWithTag("settings_gear").performClick()
            compose.onNodeWithText("Minha playlist").performClick()
            compose.onNodeWithText("Endereço M3U / M3U8").performTextInput("$base/list.m3u")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Salvar playlist").performScrollTo().performClick()
            compose.waitUntil(15000) { model.catalog.total == 6 && !model.loading }
            PlaylistRepository(context).also { assertEquals(6, it.readCache("$base/list.m3u").channels.size); it.close() }
            screenshot("portrait-home")
            compose.waitUntil(15000) { model.football.size == 2 }
            compose.onNodeWithTag("home_football").assertIsDisplayed().performClick()
            compose.onNodeWithTag("football_list").performScrollToNode(hasText("Champions League"))
            compose.onNodeWithText("Champions League").assertExists()
            compose.onNodeWithText("Leste").assertExists()
            compose.onNodeWithText("Oeste").assertExists()
            screenshot("football-agenda")
            compose.onNodeWithTag("football_list").performScrollToNode(hasText("Aurora"))
            compose.onNodeWithText("Aurora").performClick()
            compose.waitUntil(20000) { model.isPlaying && model.current?.name == "TV Aurora" }
            compose.onNodeWithContentDescription("Voltar ao futebol").performClick()
            compose.waitUntil(10000) {model.page==Page.FOOTBALL && model.current==null}
            compose.onNodeWithContentDescription("Voltar ao início").performClick()

            compose.onNode(hasText("Filmes") and hasText("1 filme")).performClick()
            compose.waitUntil(15000) { model.visibleCount == 1 && !model.filtering }
            waitForText("Luz do Norte")
            compose.onNodeWithText("Luz do Norte").assertExists()
            compose.onNodeWithText("TV Aurora").assertDoesNotExist()
            compose.onNodeWithText("Rick e Morty S01E01").assertDoesNotExist()
            compose.onNodeWithContentDescription("Voltar ao início").performClick()
            compose.onNodeWithText("Ao vivo").performClick()
            compose.waitUntil(15000) { model.visibleCount == 1 && !model.filtering }
            compose.onNodeWithText("Luz do Norte").assertDoesNotExist()
            waitForText("TV Aurora")
            compose.onNodeWithText("TV Aurora").performClick()
            compose.waitUntil(20000) { model.isPlaying && model.guide.programmes.isNotEmpty() }
            lateinit var original: Player
            scenario.onActivity { original = model.player!!; original.repeatMode = Player.REPEAT_MODE_ONE }
            compose.onNodeWithContentDescription("Adicionar aos favoritos").performClick()
            compose.waitUntil(5000) { model.favorites.isNotEmpty() }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            // Configuration reports landscape before Compose finishes its new layout pass.
            // Await the actual player surface instead of tapping during the transition.
            try {
                compose.waitUntil(15000) {
                    model.current?.name == "TV Aurora" &&
                        compose.onAllNodesWithTag("video_surface").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: Throwable) {
                throw AssertionError("Landscape player missing: " +
                    "page=${model.page}, current=${model.current?.name}, " +
                    "kind=${model.selectedKind}, fullscreen=${model.fullscreen}, " +
                    "orientation=${context.resources.configuration.orientation}, " +
                    "cinema=${compose.onAllNodesWithTag("landscape_cinema_library").fetchSemanticsNodes().size}, " +
                    "preview=${compose.onAllNodesWithTag("landscape_video_preview").fetchSemanticsNodes().size}", failure)
            }
            screenshot("landscape-player")
            revealVideoControls()
            compose.onNodeWithContentDescription("Tela cheia").performClick()
            screenshot("fullscreen")
            revealVideoControls()
            compose.onNodeWithContentDescription("Sair da tela cheia").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { assertSame(original, model.player); assertEquals("TV Aurora", model.current?.name) }
            screenshot("portrait-player")
            compose.onNodeWithContentDescription("Voltar ao início").performClick()

            compose.onNode(hasText("Séries") and hasText("2 séries")).performClick()
            compose.waitUntil(15000) { model.visibleCount == 2 && !model.filtering }
            waitForText("Rick e Morty")
            compose.onAllNodesWithText("Rick e Morty").assertCountEquals(1)
            compose.onNodeWithText("Rick e Morty S01E01").assertDoesNotExist()
            compose.onNodeWithText("TV Aurora").assertDoesNotExist()
            compose.onNodeWithText("Luz do Norte").assertDoesNotExist()
            screenshot("portrait-series")
            waitForText("Rick e Morty")
            compose.onNodeWithText("Rick e Morty").performClick()
            compose.waitUntil(15000) {model.seriesDetails.series?.title=="Rick e Morty" &&
                compose.onAllNodesWithTag("landscape_title_details").fetchSemanticsNodes().isNotEmpty()}
            assertNull("Choosing a series must show its synopsis before playback",model.current)
            compose.onNodeWithTag("episode_row_1").assertDoesNotExist()
            compose.onNodeWithTag("landscape_details_play").performClick()
            compose.waitUntil(15000) {model.seriesDetails.episodesVisible &&
                compose.onAllNodesWithTag("episode_card_1_1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("title_details_scroll").performScrollToIndex(2)
            compose.onNodeWithTag("episode_card_1_1").assertExists()
            compose.onNodeWithTag("episode_card_1_2").assertExists()
            compose.onNodeWithText("Outra Série S01E01").assertDoesNotExist()
            screenshot("portrait-episodes")
            compose.onNodeWithTag("title_details_scroll").performScrollToIndex(1)
            compose.onNodeWithTag("episode_season_jump_2").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("episode_card_2_1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("episode_card_2_1").assertIsDisplayed().performClick()
            compose.waitUntil(20000) { model.isPlaying && model.current?.name == "Rick e Morty S02E01" }
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(10000) {!model.fullscreen &&
                compose.onAllNodesWithTag("episode_card_2_1").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(model.seriesDetails.episodesVisible)
            assertEquals("Rick e Morty",model.seriesDetails.series?.title)
            scenario.onActivity {model.stop()}
            compose.onNodeWithTag("title_details_scroll").performScrollToIndex(0)
            compose.onNodeWithTag("landscape_details_back").performClick()
            assertFalse(model.seriesDetails.episodesVisible)
            compose.onNodeWithTag("landscape_details_back").performClick()
            compose.waitUntil(10000) {model.seriesDetails.series==null}
            compose.onNodeWithContentDescription("Voltar ao início").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            screenshot("landscape-home")
            // Small landscape screens now use a scrollable dashboard with two cards per row.
            // Scroll to the series row before clicking, as a DPAD user would navigate to it.
            if (compose.onAllNodesWithTag("home_landscape_compact").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("home_landscape_compact").performScrollToIndex(2)
            }
            compose.onNodeWithTag("home_series").assertIsDisplayed().performClick()
            compose.waitUntil(15000) { model.visibleCount == 2 && !model.filtering }
            screenshot("landscape-series")
            waitForText("Rick e Morty")
            compose.onNodeWithText("Rick e Morty").performClick()
            compose.onNodeWithTag("landscape_title_details").assertIsDisplayed()
            assertNull("Choosing a poster must not auto-play",model.current)
            screenshot("landscape-series-details")
            compose.onNodeWithTag("landscape_details_play").performClick()
            compose.waitUntil(15000) {model.seriesDetails.episodesVisible &&
                compose.onAllNodesWithTag("episode_card_1_1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("title_details_scroll").performScrollToIndex(2)
            compose.onNodeWithTag("episode_card_1_1").assertIsDisplayed()
            screenshot("landscape-episodes")
            scenario.recreate()
            compose.waitUntil(10000) {model.seriesDetails.episodesVisible &&
                compose.onAllNodesWithTag("episode_card_1_1").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("Rick e Morty",model.seriesDetails.series?.title)
            scenario.close()

            // Same-day reopening uses local rows and must not request the M3U again.
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                lateinit var next: AuroraViewModel
                reopened.onActivity { next = ViewModelProvider(it)[AuroraViewModel::class.java] }
                compose.waitUntil(15000) { next.initialized && next.catalog.total == 6 }
                compose.waitForIdle()
                assertEquals(1, requests.get())
                assertTrue(next.favorites.contains("$base/live/clip.mp4"))
            }
            // The next response omits all old items and adds only two. None may disappear.
            playlist.set("""
                #EXTM3U
                #EXTINF:-1 group-title="Filmes",Filme novo
                $base/movie/new.mp4
                #EXTINF:-1 group-title="Séries",Rick e Morty S02E02
                $base/series/s2e2.mp4
            """.trimIndent())
            ActivityScenario.launch(MainActivity::class.java).use { daily ->
                lateinit var next: AuroraViewModel
                daily.onActivity {
                    next = ViewModelProvider(it)[AuroraViewModel::class.java]
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                // Settle launch and rotation before starting the controlled daily request.
                // Otherwise ActivityScenario can consume the server's entire hold window.
                compose.waitUntil(15000) { next.initialized && next.catalog.total == 6 }
                compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                daily.moveToState(Lifecycle.State.CREATED)
                PlaylistRepository(context).also { repository ->
                    repository.recordAttempt("$base/list.m3u", Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
                    repository.close()
                }
                daily.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(5000) { dailyStarted.count == 0L && next.updating }
                assertEquals(6, next.catalog.total)
                assertFalse("Background update must not show loading", next.loading)
                assertTrue("Daily progress must be visible without hiding the saved list", next.updating)
                compose.onNodeWithTag("catalog_progress").assertExists()
                compose.onNodeWithTag("catalog_progress_text").assertTextContains("0%",substring=true)
                compose.onNodeWithText("6 conteúdos disponíveis").assertExists()
                releaseDaily.countDown()
                compose.waitUntil(15000) { next.catalog.total == 8 }
                PlaylistRepository(context).also { assertEquals("TV Aurora", it.channel("$base/list.m3u", "$base/live/clip.mp4")?.name); it.close() }
                assertEquals(2, next.catalog.series)
                PlaylistRepository(context).also { assertEquals(4, it.series(BrowseQuery("$base/list.m3u", MediaKind.SERIES)).single { series -> series.title == "Rick e Morty" }.episodeCount); it.close() }
            }
            ActivityScenario.launch(MainActivity::class.java).use { again ->
                lateinit var next: AuroraViewModel
                again.onActivity { next = ViewModelProvider(it)[AuroraViewModel::class.java] }
                compose.waitUntil(15000) { next.initialized && next.catalog.total == 8 }
                compose.waitForIdle()
                assertEquals(2, requests.get())
            }
        } finally { releaseDaily.countDown(); scenario.close(); server.close(); instrumentation.waitForIdleSync() }
    }

    private fun revealVideoControls() {
        compose.onNodeWithTag("video_surface").performTouchInput {click(androidx.compose.ui.geometry.Offset(center.x,center.y*.44f))}
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle(3000)
        android.os.SystemClock.sleep(600)
        val target = "/sdcard/Download/aurora-ui/$name.png"
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        device.executeShellCommand("screencap -p $target")
        assertTrue(device.executeShellCommand("ls $target").contains("$name.png"))
    }
}
