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
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** Real HTTP video playback; no provider credentials or external network dependency. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuroraUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savesPlaylistAndKeepsVideoAcrossNavigationAndRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = instrumentation.context.assets.open("smoke.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val playlist = """
            #EXTM3U url-tvg="$base/guide.xml"
            #EXTINF:-1 tvg-id="aurora" group-title="Brasil",TV Aurora
            $base/live/clip.mp4
            #EXTINF:-1 group-title="Filmes",Luz do Norte
            $base/movie/clip.mp4
            #EXTINF:-1 group-title="Séries",Aurora S01E01
            $base/series/clip.mp4
        """.trimIndent()
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
                        val body = when { path.contains("guide.xml") -> guide.toByteArray(); path.contains("list.m3u") -> playlist.toByteArray(); else -> video }
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
        context.getSharedPreferences("aurora_iptv_preferences", 0).edit().clear().commit()
        File(context.filesDir, "aurora_playlist_cache.jsonl").delete()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: AuroraViewModel
        try {
            scenario.onActivity { model = ViewModelProvider(it)[AuroraViewModel::class.java]; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15000) { model.initialized }
            compose.onNodeWithContentDescription("Configurações").performClick()
            compose.onNodeWithText("Endereço M3U / M3U8").performTextInput("$base/list.m3u")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Salvar playlist").performScrollTo().performClick()
            compose.waitUntil(15000) { model.catalog.channels.size == 3 && !model.loading }
            assertEquals("$base/list.m3u", context.getSharedPreferences("aurora_iptv_preferences", 0).getString("playlist_url", ""))
            screenshot("portrait-home")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            screenshot("landscape-home")
            compose.onNodeWithText("Ao vivo").performClick()
            compose.onNodeWithText("TV Aurora").performClick()
            compose.waitUntil(20000) { model.isPlaying && model.guide.programmes.isNotEmpty() }
            lateinit var original: Player
            scenario.onActivity { original = model.player!!; original.repeatMode = Player.REPEAT_MODE_ONE }
            screenshot("landscape-player")
            compose.onNodeWithContentDescription("Adicionar aos favoritos").performClick()
            compose.waitUntil(5000) { model.favorites.isNotEmpty() }
            compose.onNodeWithContentDescription("Tela cheia").performClick()
            compose.waitForIdle()
            screenshot("fullscreen")
            compose.onNodeWithContentDescription("Sair da tela cheia").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.waitForIdle()
            scenario.onActivity { assertSame(original, model.player); assertEquals("TV Aurora", model.current?.name) }
            screenshot("portrait-player")
            compose.onNodeWithContentDescription("Voltar ao início").performClick()
            compose.onNodeWithContentDescription("Configurações").performClick()
            compose.onNodeWithContentDescription("Mostrar endereço").performClick()
            compose.onNodeWithText("$base/list.m3u").assertExists()
            compose.onNodeWithText("Salvar playlist").performClick()
            scenario.recreate()
            scenario.onActivity {
                assertSame(original, ViewModelProvider(it)[AuroraViewModel::class.java].player)
            }
            assertEquals(3, PlaylistRepository(context).readCache("$base/list.m3u").channels.size)
            assertTrue(PlaylistRepository(context).favorites().contains("$base/live/clip.mp4"))
            scenario.close()
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                lateinit var next: AuroraViewModel
                reopened.onActivity { next = ViewModelProvider(it)[AuroraViewModel::class.java] }
                compose.waitUntil(15000) { next.initialized && next.catalog.channels.size == 3 }
                assertEquals("$base/list.m3u", next.playlistUrl)
                assertTrue(next.favorites.contains("$base/live/clip.mp4"))
            }
        } finally { scenario.close(); server.close() }
    }

    private fun screenshot(name: String) {
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-checks")
        target.mkdirs()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(target, "$name.png")))
    }
}
