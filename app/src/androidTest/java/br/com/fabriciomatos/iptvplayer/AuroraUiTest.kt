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
        val guide = """<tv><channel id="aurora"><display-name>TV Aurora</display-name></channel><programme channel="aurora" start="${date.format(Date(now - 3600000))}" stop="${date.format(Date(now + 3600000))}"><title>Paisagens da aurora</title></programme><programme channel="aurora" start="${date.format(Date(now + 3600000))}" stop="${date.format(Date(now + 7200000))}"><title>Viagem ao norte</title></programme></tv>"""
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
                            releaseDaily.await(15, java.util.concurrent.TimeUnit.SECONDS)
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
            compose.onNodeWithContentDescription("Configurações").performClick()
            compose.onNodeWithText("Endereço M3U / M3U8").performTextInput("$base/list.m3u")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Salvar playlist").performScrollTo().performClick()
            compose.waitUntil(15000) { model.catalog.channels.size == 6 && !model.loading }
            PlaylistRepository(context).also { assertEquals(6, it.readCache("$base/list.m3u").channels.size); it.close() }
            screenshot("portrait-home")

            compose.onNodeWithText("Filmes").performClick()
            compose.waitUntil { model.visible.size == 1 }
            compose.onNodeWithText("Luz do Norte").assertExists()
            compose.onNodeWithText("TV Aurora").assertDoesNotExist()
            compose.onNodeWithText("Rick e Morty S01E01").assertDoesNotExist()
            compose.onNodeWithContentDescription("Voltar ao início").performClick()
            compose.onNodeWithText("Ao vivo").performClick()
            compose.waitUntil { model.visible.size == 1 }
            compose.onNodeWithText("Luz do Norte").assertDoesNotExist()
            compose.onNodeWithText("TV Aurora").performClick()
            compose.waitUntil(20000) { model.isPlaying && model.guide.programmes.isNotEmpty() }
            lateinit var original: Player
            scenario.onActivity { original = model.player!!; original.repeatMode = Player.REPEAT_MODE_ONE }
            compose.onNodeWithContentDescription("Adicionar aos favoritos").performClick()
            compose.waitUntil(5000) { model.favorites.isNotEmpty() }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            screenshot("landscape-player")
            compose.onNodeWithContentDescription("Tela cheia").performClick()
            screenshot("fullscreen")
            compose.onNodeWithContentDescription("Sair da tela cheia").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { assertSame(original, model.player); assertEquals("TV Aurora", model.current?.name) }
            screenshot("portrait-player")
            compose.onNodeWithContentDescription("Voltar ao início").performClick()

            compose.onNode(hasText("Séries") and hasText("Suas séries e temporadas")).performClick()
            compose.waitUntil { model.visibleSeries.size == 2 }
            compose.onAllNodesWithText("Rick e Morty").assertCountEquals(1)
            compose.onNodeWithText("Rick e Morty S01E01").assertDoesNotExist()
            compose.onNodeWithText("TV Aurora").assertDoesNotExist()
            compose.onNodeWithText("Luz do Norte").assertDoesNotExist()
            screenshot("portrait-series")
            compose.onNodeWithText("Rick e Morty").performClick()
            compose.waitUntil { model.visible.size == 2 }
            compose.onNodeWithText("Rick e Morty S01E01").assertExists()
            compose.onNodeWithText("Rick e Morty S01E02").assertExists()
            compose.onNodeWithText("Rick e Morty S02E01").assertDoesNotExist()
            screenshot("portrait-episodes")
            compose.onNodeWithText("Temporada 2").performClick()
            compose.waitUntil { model.visible.size == 1 }
            compose.onNodeWithText("Rick e Morty S01E01").assertDoesNotExist()
            compose.onNodeWithText("Rick e Morty S02E01").performClick()
            compose.waitUntil(20000) { model.isPlaying && model.current?.name == "Rick e Morty S02E01" }
            compose.onNodeWithContentDescription("Voltar às séries").performClick()
            compose.onNodeWithContentDescription("Voltar ao início").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            screenshot("landscape-home")
            compose.onNode(hasText("Séries") and hasText("Suas séries e temporadas")).performClick()
            compose.waitUntil { model.visibleSeries.size == 2 }
            screenshot("landscape-series")
            compose.onNodeWithText("Rick e Morty").performClick()
            compose.waitUntil { model.visible.size == 2 }
            screenshot("landscape-episodes")
            scenario.recreate()
            scenario.close()

            // Same-day reopening uses local rows and must not request the M3U again.
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                lateinit var next: AuroraViewModel
                reopened.onActivity { next = ViewModelProvider(it)[AuroraViewModel::class.java] }
                compose.waitUntil(15000) { next.initialized && next.catalog.channels.size == 6 }
                compose.waitForIdle()
                assertEquals(1, requests.get())
                assertTrue(next.favorites.contains("$base/live/clip.mp4"))
            }
            val repository = PlaylistRepository(context)
            repository.recordAttempt("$base/list.m3u", Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
            repository.close()
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
                compose.waitUntil(15000) { next.initialized && dailyStarted.count == 0L }
                compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                assertEquals(6, next.catalog.channels.size)
                assertFalse("Background update must not show loading", next.loading)
                compose.onNodeWithText("6 conteúdos disponíveis").assertExists()
                releaseDaily.countDown()
                compose.waitUntil(15000) { next.catalog.channels.size == 8 }
                assertTrue(next.catalog.channels.any { it.name == "TV Aurora" })
                assertEquals(2, next.catalog.series.size)
                assertEquals(4, next.catalog.series.single { it.title == "Rick e Morty" }.episodes.size)
            }
            ActivityScenario.launch(MainActivity::class.java).use { again ->
                lateinit var next: AuroraViewModel
                again.onActivity { next = ViewModelProvider(it)[AuroraViewModel::class.java] }
                compose.waitUntil(15000) { next.initialized && next.catalog.channels.size == 8 }
                compose.waitForIdle()
                assertEquals(2, requests.get())
            }
        } finally { releaseDaily.countDown(); scenario.close(); server.close() }
    }

    private fun screenshot(name: String) {
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
