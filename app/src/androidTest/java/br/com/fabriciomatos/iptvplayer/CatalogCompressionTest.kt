package br.com.fabriciomatos.iptvplayer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CatalogCompressionTest {
    @Test fun compressedAndIdentityBodiesShareFingerprintAndTruncationPreservesCommittedCatalog() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val plain=buildString {
            append("#EXTM3U\n")
            repeat(800) {append("#EXTINF:-1 group-title=\"Ao Vivo\",Canal $it HD\nhttps://example.test/live/$it\n")}
        }.toByteArray(Charsets.UTF_8)
        val compressed=ByteArrayOutputStream().let {buffer ->
            GZIPOutputStream(buffer).use {it.write(plain)}
            buffer.toByteArray()
        }
        assertTrue("Fixture must demonstrate real wire savings",compressed.size<plain.size/2)
        val mode=AtomicReference("gzip")
        val accepted=AtomicReference("")
        val conditional=AtomicReference("")
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val source="http://127.0.0.1:${server.localPort}/catalog.m3u"
        val worker=thread(isDaemon=true,name="catalog-gzip-fixture") {
            while(!server.isClosed)try {server.accept().use {socket ->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader()
                reader.readLine()
                val headers=mutableMapOf<String,String>()
                while(true) {
                    val line=reader.readLine() ?: break
                    if(line.isEmpty())break
                    val split=line.indexOf(':')
                    if(split>0)headers[line.substring(0,split).lowercase()]=line.substring(split+1).trim()
                }
                accepted.set(headers["accept-encoding"].orEmpty())
                conditional.set(headers["if-none-match"].orEmpty())
                val response=mode.get()
                val output=socket.getOutputStream()
                if(response=="304")output.write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".toByteArray())
                else {
                    val gzip=response!="plain"
                    val bytes=if(gzip)compressed else plain
                    val length=bytes.size+(if(response=="short-wire")10 else 0)
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nETag: $response\r\n"+
                        (if(gzip)"Content-Encoding: gzip\r\n" else "")+"Connection: close\r\n\r\n").toByteArray())
                    output.write(bytes,0,bytes.size-(if(response=="short-crc")8 else 0))
                }
                output.flush()
            }} catch(_: Exception) { }
        }
        val repository=PlaylistRepository(context)
        try {
            val started=SystemClock.elapsedRealtimeNanos()
            assertTrue(repository.downloadFile(source,false))
            val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            assertEquals("gzip",accepted.get())
            assertEquals(800,repository.importPending(source).added)
            mode.set("plain")
            assertFalse("Decoded-body fingerprint must match across encodings",repository.downloadFile(source,true))
            assertEquals("gzip",conditional.get())
            assertEquals("plain",repository.state(source).etag)
            assertFalse(repository.hasPending(source))
            mode.set("304")
            assertFalse(repository.downloadFile(source,true))
            assertEquals("plain",conditional.get())
            for(failure in listOf("short-crc","short-wire")) {
                mode.set(failure)
                try {repository.downloadFile(source,false);fail("Incomplete gzip must fail: $failure")}
                catch(_: IOException) { }
                assertFalse(repository.hasPending(source))
                assertEquals(800,repository.overview(source).total)
                assertEquals("plain",repository.state(source).etag)
            }
            File(context.filesDir,"gzip-download.json").writeText(JSONObject()
                .put("synthetic_rows",800).put("identity_wire_bytes",plain.size)
                .put("gzip_wire_bytes",compressed.size).put("gzip_download_ms",elapsed)
                .put("identical_decoded_body_skipped_import",true)
                .put("http304_skipped_body",true).put("both_truncations_preserved_catalog",true).toString(2))
        } finally {repository.close();server.close();worker.join(1000)}
    }
}
