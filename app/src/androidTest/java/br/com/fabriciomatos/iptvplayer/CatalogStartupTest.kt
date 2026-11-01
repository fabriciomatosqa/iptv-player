package br.com.fabriciomatos.iptvplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CatalogStartupTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/startup.m3u"
    private fun db()=SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE)
    @Before fun reset()=resetCatalog(context)

    @Test fun readyCatalogOpensReadOnlyWithoutRunningLegacyRecovery() {
        PlaylistRepository(context).let {repo ->
            repo.appendCache(source,Download(Playlist(listOf(Channel("Saved HD","https://example.test/live/1","Ao Vivo"))),"",""))
            repo.close()
        }
        db().use {database ->
            database.execSQL("CREATE TRIGGER opening_must_not_write BEFORE INSERT ON playlists BEGIN SELECT RAISE(ABORT,'Ready opening wrote to playlists'); END")
            database.execSQL("CREATE TRIGGER opening_must_not_sync BEFORE DELETE ON favorites BEGIN SELECT RAISE(ABORT,'Ready opening ran bookmark maintenance'); END")
            // Add a bookmark so an unnecessary DELETE really triggers the assertion.
            database.execSQL("INSERT INTO favorites(url) VALUES('https://example.test/live/1')")
            PlaylistRepository(context).let {repo ->try {
                assertTrue(repo.hasUsableCatalog(source))
                repo.openCatalog(source)
                assertEquals("Saved HD",repo.channels(BrowseQuery(source,MediaKind.LIVE)).single().name)
                assertFalse(repo.hasUsableCatalog("https://example.test/missing.m3u"))
                val plan=database.rawQuery("EXPLAIN QUERY PLAN SELECT indexed,total_count FROM playlists WHERE source_key=?",arrayOf(PlaylistRepository.digest(source))).use {cursor ->
                    buildList {while(cursor.moveToNext())add(cursor.getString(3))}.joinToString(" ")
                }
                assertTrue(plan.contains("SEARCH"));assertFalse(plan.contains("channels"))
            } finally {repo.close()}}
        }
    }

    @Test fun unfinishedClassificationIsRecoveredAndAdultContentStaysHidden() {
        val repo=PlaylistRepository(context)
        try {
            repo.appendCache(source,Download(Playlist(listOf(
                Channel("News HD","https://example.test/live/1","Ao Vivo"),
                Channel("Restricted","https://example.test/movie/2","Adultos +18",typeHint="movie")
            )),"",""))
            db().use {database ->
                database.execSQL("UPDATE channels SET kind=-1,adult=-1")
                database.execSQL("UPDATE playlists SET indexed=0")
            }
            assertFalse(repo.hasUsableCatalog(source))
            repo.openCatalog(source)
            assertTrue(repo.hasUsableCatalog(source))
            assertEquals(1,repo.channels(BrowseQuery(source,kind=null)).size)
            assertEquals(2,repo.channels(BrowseQuery(source,kind=null,includeAdult=true)).size)
        } finally {repo.close()}
    }

    @Test fun cachedPagesAndBookmarksRemainReadableWhileImportIsWaitingForMoreInput() {
        val repo=PlaylistRepository(context)
        val first=Channel("Saved HD","https://example.test/live/1","Ao Vivo")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(2)
        try {
            repo.appendCache(source,Download(Playlist(listOf(first)),"",""))
            repo.saveFavorites(setOf(first.url));repo.savePlaybackProgress(source,first.url,PlaybackProgress(9000,120000))
            val text="#EXTM3U\n#EXTINF:-1 group-title=\"Ao Vivo\",New HD\nhttps://example.test/live/2\n"
            val input=object: Reader() {
                private val delegate=text.reader()
                private var blocked=false
                override fun read(buffer: CharArray,offset: Int,length: Int): Int {
                    if(!blocked) {blocked=true;entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                    return delegate.read(buffer,offset,length)
                }
                override fun close()=delegate.close()
            }
            val update=workers.submit<ImportResult> {repo.importReader(source,input)}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                val read=workers.submit<Boolean> {
                    repo.openCatalog(source)
                    repo.channels(BrowseQuery(source,MediaKind.LIVE)).single().url==first.url &&
                        repo.favorites().contains(first.url) && repo.playbackProgress(source,first.url)?.positionMs==9000L
                }
                assertTrue(read.get(3,TimeUnit.SECONDS))
            } finally {release.countDown()}
            assertEquals(1,update.get(10,TimeUnit.SECONDS).added)
            assertEquals(2,repo.overview(source).total)
        } finally {release.countDown();workers.shutdownNow();repo.close()}
    }
}
