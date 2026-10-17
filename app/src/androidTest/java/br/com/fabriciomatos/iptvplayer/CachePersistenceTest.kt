package br.com.fabriciomatos.iptvplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class CachePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "https://example.test/list.m3u"
    private val a = Channel("TV salva", "https://example.test/live/1", "Brasil")
    private val b = Channel("Filme salvo", "https://example.test/movie/2", "Filmes")
    private val c = Channel("Rick e Morty S01E01", "https://example.test/series/3", "Séries")

    @Before fun reset() = resetCatalog(context)

    private fun assertSaved(expected: List<Channel>, actual: List<Channel>) {
        assertEquals(expected.map { Triple(it.url, it.name, it.kind) }, actual.map { Triple(it.url, it.name, it.kind) })
        actual.filter { it.kind == MediaKind.SERIES }.forEach {
            assertEquals(1, it.seasonNumber)
            assertEquals(1, it.episodeNumber)
            assertEquals("Rick e Morty", it.seriesTitle)
        }
    }

    @Test fun additiveCatalogAndDailyLedgerSurviveClosingRepository() {
        val first = PlaylistRepository(context)
        first.saveSource(source, "Minha lista")
        first.appendCache(source, Download(Playlist(listOf(a, b)), "first", ""))
        assertFalse(first.claimDailyAttempt(source))
        first.appendCache(source, Download(Playlist(listOf(b.copy(name = "Renomeado"), c, c)), "second", ""))
        first.close()
        val reopened = PlaylistRepository(context)
        assertSaved(listOf(a, b, c), reopened.readCache(source).channels)
        assertEquals("second", reopened.state(source).etag)
        assertFalse(reopened.claimDailyAttempt(source))
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        reopened.recordAttempt(source, yesterday)
        assertTrue(reopened.claimDailyAttempt(source))
        assertFalse(reopened.claimDailyAttempt(source))
        // A failed request already claimed the day; reopening must not retry automatically.
        reopened.close()
        val next = PlaylistRepository(context)
        assertFalse(next.claimDailyAttempt(source))
        assertTrue(next.readCache("https://other.test/list.m3u").channels.isEmpty())
        next.appendCache(source, Download(Playlist(emptyList()), "", ""))
        assertSaved(listOf(a, b, c), next.readCache(source).channels)
        next.close()
    }

    @Test fun migratesCorrectLegacyHeaderDespiteWrongAuxiliaryPreference() {
        context.getSharedPreferences("aurora_iptv_preferences", 0).edit().putString("cache_source_hash", "incorrect").commit()
        val file = File(context.filesDir, "aurora_playlist_cache.jsonl")
        file.writeText(JSONObject().put("schema", 2).put("sourceHash", PlaylistRepository.digest(source)).toString() + "\n" +
            JSONObject().put("name", c.name).put("url", c.url).put("group", c.group).toString() + "\n")
        val old = PlaylistRepository(context)
        assertSaved(listOf(c), old.readCache(source).channels)
        old.close()
        file.delete()
        val reopened = PlaylistRepository(context)
        assertSaved(listOf(c), reopened.readCache(source).channels)
        reopened.close()
    }

    @Test fun recoversBackupAndNeverUsesCacheFromAnotherSource() {
        val base = File(context.filesDir, "aurora_playlist_cache.jsonl")
        base.writeText("{incomplete")
        val backup = File(context.filesDir, "aurora_playlist_cache.jsonl.bak")
        backup.writeText(JSONObject().put("schema", 2).put("sourceHash", PlaylistRepository.digest(source)).toString() + "\n" +
            JSONObject().put("name", a.name).put("url", a.url).put("group", a.group).toString() + "\n")
        val repository = PlaylistRepository(context)
        assertTrue(repository.readCache("https://other.test/list.m3u").channels.isEmpty())
        assertEquals(listOf(a), repository.readCache(source).channels)
        repository.close()
    }

    @Test fun failedTransactionPreservesCompletePreviousSnapshot() {
        val repository = PlaylistRepository(context)
        repository.appendCache(source, Download(Playlist(listOf(a)), "old", ""))
        // Simulate storage failure after one new row: no partial catalog may be committed.
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON channels WHEN NEW.name='FAIL' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END")
        try {
            repository.appendCache(source, Download(Playlist(listOf(b, c.copy(name = "FAIL"))), "new", ""))
            fail("Storage failure should abort the transaction")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertEquals(listOf(a), repository.readCache(source).channels)
            assertEquals("old", repository.state(source).etag)
        } finally { db.close(); repository.close() }
    }

    @Test fun upgradesVersion12DatabaseWithoutLosingRows() {
        val path = context.getDatabasePath("aurora_catalog.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE playlists(source_key TEXT PRIMARY KEY,etag TEXT NOT NULL DEFAULT '',modified TEXT NOT NULL DEFAULT '',epg_url TEXT NOT NULL DEFAULT '',checked INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE channels(source_key TEXT NOT NULL,url TEXT NOT NULL,position INTEGER NOT NULL,name TEXT NOT NULL,group_name TEXT NOT NULL,image_url TEXT NOT NULL,tvg_id TEXT NOT NULL,user_agent TEXT NOT NULL,referrer TEXT NOT NULL,mime_type TEXT NOT NULL,type_hint TEXT NOT NULL,series_id TEXT NOT NULL,series_title TEXT NOT NULL,series_image TEXT NOT NULL,season INTEGER NOT NULL,episode INTEGER NOT NULL,PRIMARY KEY(source_key,url))")
            val key = PlaylistRepository.digest(source)
            db.execSQL("INSERT INTO playlists(source_key,etag) VALUES(?,?)", arrayOf(key, "saved-validator"))
            listOf(a, b, c).forEachIndexed { index, row ->
                db.execSQL("INSERT INTO channels VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(key,row.url,index,row.name,row.group,"","","","","","","","","",0,0))
            }
            db.version = 1
        }
        val repository = PlaylistRepository(context)
        repository.restore(source)
        assertEquals(3, repository.overview(source).total)
        assertEquals(1, repository.overview(source).series)
        assertSaved(listOf(a, b, c), repository.channels(BrowseQuery(source, kind = null)))
        assertEquals("saved-validator", repository.state(source).etag)
        repository.close()
    }
}

internal fun resetCatalog(context: android.content.Context) {
    // ActivityScenario.close() clears ViewModels, but their non-cancellable persistence and
    // repository shutdown finish on IO. Deleting an open WAL database can corrupt the next
    // test's new files (SQLITE_IOERR_SHORT_READ). Wait for actual descriptors, not a fixed delay.
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    val catalogFiles=setOf("aurora_catalog.db","aurora_catalog.db-wal","aurora_catalog.db-shm")
    fun openCatalogFiles()=File("/proc/self/fd").listFiles().orEmpty().mapNotNull { descriptor ->
        runCatching { android.system.Os.readlink(descriptor.path).removeSuffix(" (deleted)") }.getOrNull()
    }.filter { it.substringAfterLast('/') in catalogFiles }
    val deadline=android.os.SystemClock.uptimeMillis()+30000L
    while(openCatalogFiles().isNotEmpty() && android.os.SystemClock.uptimeMillis()<deadline)
        android.os.SystemClock.sleep(25)
    check(openCatalogFiles().isEmpty()) { "Previous test has not closed its catalog database" }
    context.getSharedPreferences("aurora_settings", 0).edit().clear().commit()
    context.getSharedPreferences("aurora_iptv_preferences", 0).edit().clear().commit()
    context.deleteDatabase("aurora_catalog.db")
    File(context.filesDir, "aurora_playlist_cache.jsonl").delete()
    File(context.filesDir, "aurora_playlist_cache.jsonl.bak").delete()
}
