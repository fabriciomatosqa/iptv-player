package br.com.fabriciomatos.iptvplayer

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.Reader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CacheFirstPlaybackTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun savedGuideStillLoadsWithoutARemoteGuideUrl() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="local-m3u://00000000-0000-0000-0000-000000000001"
        val now=System.currentTimeMillis()
        PlaylistRepository(context).let {repo ->try {
            repo.saveSource(source,"Guia offline")
            repo.appendCache(source,Download(Playlist(listOf(Channel("Guide HD","https://example.test/live/1","Ao Vivo",tvgId="guide.one"))),"",""))
            repo.saveGuide(source,Guide(mapOf("guide.one" to listOf(Programme("Saved programme",now-30000,now+120000))),mapOf("guide hd" to "guide.one")))
        } finally {repo.close()}}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
            compose.waitUntil(15000) {model.guide.programmes["guide.one"]?.any {it.title=="Saved programme"}==true}
        }
    }

    @Test fun cachedVideoPlaysWhileAnotherConnectionHoldsAnImportTransaction() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val video=File(context.cacheDir,"cache-import.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("smoke.mp4").use {input ->
            video.outputStream().use {input.copyTo(it)}
        }
        val source="local-m3u://00000000-0000-0000-0000-000000000002"
        val channel=Channel("Canal durante importação HD",video.toURI().toString(),"Ao Vivo",typeHint="live")
        val repository=PlaylistRepository(context)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            repository.saveSource(source,"Leitura durante importação")
            repository.appendCache(source,Download(Playlist(listOf(channel)),"",""))
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
                scenario.onActivity {model.browse(MediaKind.LIVE)}
                compose.waitUntil(15000) {compose.onAllNodesWithText(channel.name).fetchSemanticsNodes().isNotEmpty()}
                val input=object: Reader() {
                    private val text="#EXTM3U\n#EXTINF:-1 group-title=\"Ao Vivo\",Novo canal HD\nhttps://example.test/live/new\n".reader()
                    private var blocked=false
                    override fun read(buffer: CharArray,offset: Int,length: Int): Int {
                        if(!blocked) {blocked=true;entered.countDown();check(release.await(30,TimeUnit.SECONDS))}
                        return text.read(buffer,offset,length)
                    }
                    override fun close()=text.close()
                }
                val update=executor.submit<ImportResult> {repository.importReader(source,input)}
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS))
                    compose.onAllNodesWithText(channel.name).onFirst().performClick()
                    compose.waitUntil(12000) {model.current?.url==channel.url && model.isPlaying}
                    assertFalse("Import must still hold its transaction when playback starts",update.isDone)
                    assertEquals(1L,release.count)
                } finally {release.countDown()}
                assertEquals(1,update.get(10,TimeUnit.SECONDS).added)
                assertEquals(2,repository.overview(source).total)
            }
        } finally {
            release.countDown();executor.shutdown();executor.awaitTermination(10,TimeUnit.SECONDS);repository.close()
        }
    }

    @Test fun cachedChannelPlaysWhileManualRefreshIsStillWaitingForHttp() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val video=File(context.cacheDir,"cache-first.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("smoke.mp4").use {input ->
            video.outputStream().use {input.copyTo(it)}
        }
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val entered=CountDownLatch(1);val respond=CountDownLatch(1)
        val worker=thread(isDaemon=true,name="cache-first-slow-http") {
            while(!server.isClosed)try {server.accept().use {socket ->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader()
                reader.readLine();while(!reader.readLine().isNullOrEmpty()) { }
                entered.countDown()
                if(respond.await(25,TimeUnit.SECONDS)) {
                    socket.getOutputStream().write("HTTP/1.1 304 Not Modified\r\nETag: saved\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }} catch(_: Exception) { }
        }
        val source="http://127.0.0.1:${server.localPort}/fixture.m3u"
        val channel=Channel("Canal salvo HD",video.toURI().toString(),"Ao Vivo",typeHint="live")
        PlaylistRepository(context).let {repo ->try {
            repo.saveSource(source,"Cache independente")
            repo.appendCache(source,Download(Playlist(listOf(channel)),"saved",""))
            repo.saveMetadata(source,ProviderMetadata())
        } finally {repo.close()}}
        val measurements=JSONObject()
        val started=SystemClock.elapsedRealtimeNanos()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                lateinit var model: AuroraViewModel
                scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
                measurements.put("activity_ready_ms",(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0)
                scenario.onActivity {model.browse(MediaKind.LIVE)}
                compose.waitUntil(15000) {compose.onAllNodesWithText(channel.name).fetchSemanticsNodes().isNotEmpty()}
                scenario.onActivity {model.refresh()}
                assertTrue("Synthetic HTTP request did not start",entered.await(10,TimeUnit.SECONDS))
                compose.waitUntil(5000) {model.updating}
                assertFalse("Manual refresh hid the saved catalog",model.loading)
                val playbackStarted=SystemClock.elapsedRealtimeNanos()
                compose.onAllNodesWithText(channel.name).onFirst().performClick()
                compose.waitUntil(12000) {model.current?.url==channel.url && model.isPlaying}
                assertTrue("Playback must start while the HTTP refresh is actually active",model.updating)
                assertEquals("Playback must start before releasing the HTTP response",1L,respond.count)
                measurements.put("playback_while_http_pending_ms",(SystemClock.elapsedRealtimeNanos()-playbackStarted)/1_000_000.0)
                measurements.put("http_still_pending_when_playback_started",true)
                respond.countDown()
                compose.waitUntil(10000) {!model.updating}
                assertEquals(1,model.catalog.total)
            }
        } finally {
            respond.countDown();server.close();worker.join(1000)
            File(context.filesDir,"cache-first-playback.json").writeText(measurements.toString(2))
        }
    }
}
