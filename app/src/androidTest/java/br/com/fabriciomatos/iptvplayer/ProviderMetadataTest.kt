package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ProviderMetadataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() = resetCatalog(context)

    @Test fun providerCategoriesAndCorrectPosterAreSavedAcrossReopening() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val source = "$base/get.php?username=test&password=test&type=m3u_plus"
        val title = "100 Dias para Morar Comecando do Zero"
        val cover = "$base/correct.jpg"
        thread(isDaemon = true) {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = when {
                        request.contains("get_live_categories") -> "[{\"category_name\":\"FILMES E SÉRIES\"}]"
                        request.contains("get_vod_categories") -> "[{\"category_name\":\"Ação\"}]"
                        request.contains("get_series_categories") -> "[{\"category_name\":\"Netflix\"}]"
                        else -> "[{\"name\":\"$title\",\"cover\":\"$cover\",\"series_id\":12979,\"plot\":{\"ignored\":true}}]"
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: Exception) { }
        }
        try {
            val repository = PlaylistRepository(context)
            val live = Channel("A&E", "$base/play/live/ts", "FILMES E SÉRIES", kind = MediaKind.SERIES)
            val movie = Channel("Filme", "$base/play/movie", "Ação", kind = MediaKind.LIVE)
            val episode = Channel("$title S01E01", "$base/play/episode", "Netflix", "$base/wrong.jpg")
            repository.appendCache(source, Download(Playlist(listOf(live, movie, episode)), "", ""))
            val metadata = runBlocking(Dispatchers.IO) { repository.fetchMetadata(source) }
            assertEquals(MediaKind.LIVE, metadata.categories["FILMES E SÉRIES"])
            assertEquals(cover, metadata.posters[normalized(title)]!!.image)
            repository.repairMetadata(source, metadata)
            repository.close()
            val reopened = PlaylistRepository(context)
            reopened.restore(source)
            assertEquals(1, reopened.overview(source).live)
            assertEquals(1, reopened.overview(source).movies)
            assertEquals(1, reopened.overview(source).series)
            assertEquals(cover, reopened.series(BrowseQuery(source, MediaKind.SERIES)).single().imageUrl)
            assertEquals(cover, reopened.channel(source, episode.url)!!.seriesImageUrl)
            assertEquals("12979", reopened.metadata(source).posters[normalized(title)]!!.providerId)
            assertTrue(reopened.channels(BrowseQuery(source, MediaKind.LIVE)).single().url.endsWith("/ts"))
            reopened.close()
        } finally { server.close() }
    }
}
