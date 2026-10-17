package br.com.fabriciomatos.iptvplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Recreate the exact v7 table and redundant rows produced by host rotation. No network or credentials. */
internal fun legacyHostCopies(context: Context, source: String, copies: Int, originalNewest: Boolean = false) {
    SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.beginTransaction()
        try {
            val create=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='channels'",null).use {it.moveToFirst();it.getString(0)}
                .replace(Regex(",\\s*content_key TEXT NOT NULL DEFAULT ''"),"")
            val indexes=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='channels' AND sql IS NOT NULL AND name<>'channel_content_identity'",null).use { c-> buildList {while(c.moveToNext())add(c.getString(0))} }
            val columns=db.rawQuery("PRAGMA table_info(channels)",null).use {c->buildList {while(c.moveToNext())if(c.getString(1)!="content_key")add(c.getString(1))} }
            db.execSQL("DROP TABLE channel_url_aliases")
            db.execSQL("ALTER TABLE channels RENAME TO identity_fixture")
            db.execSQL(create)
            val fields=columns.joinToString(",")
            db.execSQL("INSERT INTO channels($fields) SELECT $fields FROM identity_fixture")
            db.execSQL("DROP TABLE identity_fixture")
            indexes.forEach(db::execSQL)
            val key=PlaylistRepository.digest(source)
            if(originalNewest)db.execSQL("UPDATE channels SET seen_generation=? WHERE source_key=?",arrayOf(copies+1,key))
            repeat(copies) { i ->
                val expressions=columns.joinToString(",") { when(it) {
                    "url"->"replace(url,'example.test','cdn-${i+1}.example.test')"
                    "position"->"position+${(i+1)*1000000}"
                    "seen_generation"->(if(originalNewest)i+1 else i+2).toString()
                    else->it
                } }
                db.execSQL("INSERT INTO channels($fields) SELECT $expressions FROM channels WHERE source_key=? AND url LIKE 'https://example.test/%'",arrayOf(key))
            }
            db.execSQL("UPDATE playlists SET total_count=(SELECT COUNT(*) FROM channels c WHERE c.source_key=playlists.source_key)")
            db.version=7
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}

@RunWith(AndroidJUnit4::class)
class CatalogIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/list.m3u"
    private fun url(id: Int, host: String="example.test", suffix: String="")="https://$host/play/"+id.toString(16).padStart(64,'0')+suffix
    private fun rows(host: String="example.test")=listOf(Channel("Canal HD",url(1,host,"/ts"),"Brasil"),
        Channel("Aurora (2026) 4K",url(2,host),"Filmes"),Channel("Aurora S01E01",url(3,host),"Séries"))
    private fun playlist(rows: List<Channel>)="#EXTM3U\n"+rows.joinToString("\n") {"#EXTINF:-1 group-title=\"${it.group}\",${it.name}\n${it.url}"}+"\n"
    @Before fun reset()=resetCatalog(context)

    @Test fun sqlAndImporterAgreeAndDoNotMergeFormatsQueriesOrOrdinaryUrls() {
        val ordinary=listOf("https://a.test/video/1", "https://b.test/video/1",url(1),url(1,"cdn.test"),url(1,suffix="/ts"),url(1,suffix="/m3u8"),url(1,suffix="?token=a"),url(1,suffix="?token=b"),"https://example.test/play/short",url(1,suffix="/other"))
        PlaylistRepository(context).useRepository {it.restore(source)}
        SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            ordinary.forEach { value ->db.rawQuery("SELECT "+catalogContentKeySql("url")+" FROM (SELECT ? AS url)",arrayOf(value)).use {assertTrue(it.moveToFirst());assertEquals(catalogContentKey(value),it.getString(0))} }
        }
        val repository=PlaylistRepository(context)
        repository.importReader(source,playlist(ordinary.map {Channel("Mesmo título",it,"Filmes")}).reader())
        assertEquals(ordinary.size-1,repository.overview(source).total)
        assertEquals(0,repository.importReader(source,playlist(ordinary.map {Channel("Mesmo título",it,"Filmes")}).reader()).added)
        repository.close()
    }

    @Test fun rotatesHostsWithoutGrowingAndPreservesBookmarksAndPartialCatalogs() {
        val repository=PlaylistRepository(context);val old=rows();repository.saveSource(source,"Lista")
        assertEquals(3,repository.importReader(source,playlist(old).reader()).added)
        repository.saveFavorites(setOf(old[1].url));repository.saveLast(old[2]);repository.saveSeriesFavorites(source,setOf(seriesIdentity(old[2]).id))
        repository.savePlaybackProgress(source,old[1].url,PlaybackProgress(23000,120000,false))
        repeat(3) {i->
            val fresh=rows("cdn-$i.test")
            assertEquals(0,repository.importReader(source,playlist(fresh).reader()).added)
            assertEquals(3,repository.overview(source).total)
            assertEquals(fresh[1].url,repository.channel(source,old[1].url)!!.url)
            assertEquals(setOf(fresh[1].url),repository.favorites())
            assertEquals(fresh[2].url,repository.lastUrl())
            assertEquals(23000L,repository.playbackProgress(source,fresh[1].url)!!.positionMs)
            assertEquals(1,repository.matchingCount(BrowseQuery(source,MediaKind.MOVIES,favoritesOnly=true)))
        }
        val extra=Channel("Novo",url(99),"Filmes")
        assertEquals(1,repository.importReader(source,playlist(listOf(extra)).reader()).added)
        assertEquals(4,repository.overview(source).total)
        repository.close()
        PlaylistRepository(context).useRepository { reopened ->reopened.restore(source);assertEquals(4,reopened.overview(source).total);assertEquals(1,reopened.series(BrowseQuery(source,MediaKind.SERIES)).single().episodeCount)}
    }

    @Test fun repairsVersion7DuplicatesAndRetainsNewestProgressAcrossSources() {
        val original=rows();val other="https://other.test/list.m3u"
        val repository=PlaylistRepository(context);repository.saveSource(source,"Minha lista")
        repository.importReader(source,playlist(original).reader(),etag="validator")
        repository.importReader(other,playlist(listOf(original[1])).reader())
        repository.saveFavorites(setOf(original[1].url));repository.saveLast(original[2])
        repository.saveSeriesFavorites(source,setOf(seriesIdentity(original[2]).id))
        repository.savePlaybackProgress(source,original[1].url,PlaybackProgress(23000,120000,false))
        repository.savePlaybackProgress(source,url(2,"cdn-2.example.test"),PlaybackProgress(9000,120000,false))
        val checked=repository.state(source).checked;repository.close()
        legacyHostCopies(context,source,2)
        PlaylistRepository(context).useRepository { repaired ->
            repaired.restore(source)
            assertEquals(3,repaired.overview(source).total)
            assertEquals(1,repaired.overview(other).total)
            assertEquals(1,repaired.overview(source).episodes)
            assertEquals(1,repaired.series(BrowseQuery(source,MediaKind.SERIES)).single().episodeCount)
            assertEquals(url(2,"cdn-2.example.test"),repaired.channel(source,original[1].url)!!.url)
            assertEquals(setOf(url(2,"cdn-2.example.test"),original[1].url),repaired.favorites())
            assertEquals(url(3,"cdn-2.example.test"),repaired.lastUrl())
            assertEquals(9000L,repaired.playbackProgress(source,original[1].url)!!.positionMs)
            assertEquals(setOf(seriesIdentity(original[2]).id),repaired.seriesFavorites(source))
            assertEquals(checked,repaired.state(source).checked);assertEquals("validator",repaired.state(source).etag)
        }
        PlaylistRepository(context).useRepository { again ->again.restore(source);assertEquals(3,again.overview(source).total);assertEquals(9000L,again.playbackProgress(source,original[1].url)!!.positionMs)}
    }

    @Test fun failedRefreshRollsBackHostChangesAndSavedPositions() {
        val repository=PlaylistRepository(context);val original=rows()
        repository.saveSource(source,"Lista");repository.importReader(source,playlist(original).reader(),etag="old")
        repository.saveFavorites(setOf(original[1].url));repository.saveLast(original[1])
        repository.savePlaybackProgress(source,original[1].url,PlaybackProgress(12000,120000,false))
        SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON channels WHEN NEW.name='FAIL' BEGIN SELECT RAISE(ABORT,'test interruption'); END")
        }
        try {
            repository.importReader(source,playlist(rows("cdn.test")+Channel("FAIL",url(99),"Filmes")).reader(),etag="bad")
            fail("Interrupted import must roll back")
        } catch(_: android.database.sqlite.SQLiteException) {
            assertEquals(3,repository.overview(source).total)
            assertEquals(original[1].url,repository.channel(source,original[1].url)!!.url)
            assertEquals(setOf(original[1].url),repository.favorites())
            assertEquals(original[1].url,repository.lastUrl())
            assertEquals(12000L,repository.playbackProgress(source,original[1].url)!!.positionMs)
            assertEquals("old",repository.state(source).etag)
        } finally {repository.close()}
    }
}

private inline fun <T> PlaylistRepository.useRepository(block: (PlaylistRepository)->T): T = try {block(this)} finally {close()}
