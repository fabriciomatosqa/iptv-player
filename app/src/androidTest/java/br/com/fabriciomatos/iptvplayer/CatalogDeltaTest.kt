package br.com.fabriciomatos.iptvplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Audit real SQLite writes, not just totals: ignored INSERTs alone do not prove a delta import. */
@RunWith(AndroidJUnit4::class)
class CatalogDeltaTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/delta.m3u"
    private fun url(id: Int,host: String="example.test")="https://$host/play/aZ_"+id.toString(16).padStart(61,'0')
    private fun episodes(count: Int,host: String="example.test")=(1..count).map {
        Channel("Eu sou S01E${it.toString().padStart(2,'0')}",url(it,host),"Séries")
    }
    private fun m3u(rows: List<Channel>)="#EXTM3U\n"+rows.joinToString("\n") {
        "#EXTINF:-1 group-title=\"${it.group}\",${it.name}\n${it.url}"
    }+"\n"
    private fun database()=SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE)
    private fun audit(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE delta_audit(table_name TEXT,operation TEXT)")
        for(table in listOf("channels","series_directory","series_groups","catalog_groups"))
            for(operation in listOf("INSERT","UPDATE","DELETE"))
                db.execSQL("CREATE TRIGGER audit_${table}_$operation AFTER $operation ON $table BEGIN INSERT INTO delta_audit VALUES('$table','$operation'); END")
        // This fires even for INSERT OR IGNORE. Previously stored rows must be compared first.
        db.execSQL("CREATE TRIGGER no_existing_insert BEFORE INSERT ON channels WHEN EXISTS(SELECT 1 FROM channels WHERE source_key=NEW.source_key AND content_key=NEW.content_key) BEGIN SELECT RAISE(ABORT,'Existing content was sent to INSERT'); END")
    }
    private fun writes(db: SQLiteDatabase,table: String,operation: String)=db.rawQuery(
        "SELECT COUNT(*) FROM delta_audit WHERE table_name=? AND operation=?",arrayOf(table,operation)).use {it.moveToFirst();it.getInt(0)}
    private fun allWrites(db: SQLiteDatabase)=db.rawQuery("SELECT COUNT(*) FROM delta_audit",null).use {it.moveToFirst();it.getInt(0)}

    @Before fun reset()=resetCatalog(context)

    @Test fun fifteenSavedEpisodesOnlyInsertSixteenAndSeventeenAndUpdateTheirOwnSeries() {
        val repository=PlaylistRepository(context)
        try {
            val background=(1..1201).map {Channel("Outra série $it S01E01",url(it+1000),"Outras séries")}
            repository.saveSource(source,"Incremental")
            repository.importReader(source,m3u(background+episodes(15)).reader())
            val first=episodes(15).first();val id=seriesIdentity(first).id
            repository.saveSeriesFavorites(source,setOf(id));repository.saveLast(first)
            repository.savePlaybackProgress(source,first.url,PlaybackProgress(21000,120000))
            database().use {db ->
                audit(db)
                val progress=mutableListOf<CatalogProgress>()
                val result=repository.importReader(source,m3u(background+episodes(17)).reader(),etag="new",progress={progress.add(it)})
                assertEquals(2,result.added);assertEquals(0,result.changed);assertEquals(1218,result.total)
                assertEquals(2,writes(db,"channels","INSERT"));assertEquals(0,writes(db,"channels","UPDATE"));assertEquals(0,writes(db,"channels","DELETE"))
                assertEquals(1,writes(db,"series_directory","DELETE"));assertEquals(1,writes(db,"series_directory","INSERT"))
                assertEquals(1,writes(db,"catalog_groups","DELETE"));assertEquals(1,writes(db,"catalog_groups","INSERT"))
                assertEquals(17,repository.seriesDetail(source,id)!!.episodeCount)
                assertEquals(setOf(id),repository.seriesFavorites(source));assertEquals(first.url,repository.lastUrl())
                assertEquals(21000L,repository.playbackProgress(source,first.url)!!.positionMs)
                assertEquals(2,progress.last().added);assertEquals(100,progress.last().percent)
                db.execSQL("DELETE FROM delta_audit")
                val unchanged=repository.importReader(source,m3u(background+episodes(17)).reader())
                assertEquals(0,unchanged.added);assertEquals(0,unchanged.changed);assertEquals(0,allWrites(db))
                repository.importReader(source,m3u((background+episodes(17)).reversed()).reader())
                assertEquals("Reordering an existing list must not rewrite its rows",0,allWrites(db))
                repository.repairMetadata(source,ProviderMetadata(categories=mapOf("Séries" to MediaKind.SERIES,"Outras séries" to MediaKind.SERIES)))
                assertEquals("A 304/metadata check must not rebuild an unchanged catalog",0,allWrites(db))
            }
        } finally {repository.close()}
        PlaylistRepository(context).let {reopened ->try {
            reopened.restore(source)
            assertEquals(1218,reopened.overview(source).total)
            assertEquals(17,reopened.seriesDetail(source,seriesIdentity(episodes(1).first()).id)!!.episodeCount)
            assertEquals(21000L,reopened.playbackProgress(source,episodes(1).first().url)!!.positionMs)
        } finally {reopened.close()}}
    }

    @Test fun changedStreamAddressesAreUpdatedWithoutRewritingUnchangedSeriesDirectories() {
        val repository=PlaylistRepository(context)
        try {
            val old=episodes(17);repository.saveSource(source,"CDN")
            repository.importReader(source,m3u(old).reader());repository.saveLast(old.first())
            repository.savePlaybackProgress(source,old.first().url,PlaybackProgress(9000,120000))
            database().use {db ->
                audit(db)
                val fresh=episodes(17,"cdn.test")
                val result=repository.importReader(source,m3u(fresh).reader())
                assertEquals(0,result.added);assertEquals(17,result.changed);assertEquals(17,result.total)
                assertEquals(0,writes(db,"channels","INSERT"));assertEquals(17,writes(db,"channels","UPDATE"))
                assertEquals(0,writes(db,"series_directory","DELETE"));assertEquals(0,writes(db,"catalog_groups","DELETE"))
                assertEquals(fresh.first().url,repository.lastUrl())
                assertEquals(9000L,repository.playbackProgress(source,fresh.first().url)!!.positionMs)
                db.execSQL("DELETE FROM delta_audit")
                repository.importReader(source,m3u(fresh).reader())
                assertEquals(0,allWrites(db))
            }
        } finally {repository.close()}
    }

    @Test fun interruptedDeltaLeavesOriginalRowsCountsBookmarksAndValidatorsIntact() {
        val repository=PlaylistRepository(context)
        try {
            repository.importReader(source,m3u(episodes(15)).reader(),etag="original")
            database().use {db ->
                audit(db)
                db.execSQL("CREATE TRIGGER fail_new_episode BEFORE INSERT ON channels WHEN NEW.episode=17 BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END")
                try {repository.importReader(source,m3u(episodes(17)).reader(),etag="failed");fail("Must roll back")}
                catch(_: android.database.sqlite.SQLiteException) { }
                assertEquals(0,allWrites(db));assertEquals(15,repository.overview(source).total)
                assertEquals(15,repository.seriesDetail(source,seriesIdentity(episodes(1).first()).id)!!.episodeCount)
                assertEquals("original",repository.state(source).etag)
            }
        } finally {repository.close()}
    }

    @Test fun identicalHttp200BodiesSkipImportAcrossReopeningAndFailedImportsNeverAdvanceTheFingerprint() = runBlocking {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val remote="http://127.0.0.1:${server.localPort}/list.m3u"
        val body=AtomicReference(m3u(episodes(15)))
        val etag=AtomicReference("first")
        val worker=thread(isDaemon=true,name="delta-http-fixture") {
            while(!server.isClosed)try {server.accept().use {socket ->
                socket.soTimeout=3000
                val reader=socket.getInputStream().bufferedReader()
                reader.readLine();while(!reader.readLine().isNullOrEmpty()) { }
                val bytes=body.get().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nETag: ${etag.get()}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes);flush()
                }
            }} catch(_: Exception) { }
        }
        var repository=PlaylistRepository(context)
        try {
            assertTrue(repository.downloadFile(remote,false));assertEquals(15,repository.importPending(remote).added)
            database().use {db ->
                audit(db);etag.set("second")
                assertFalse("Identical HTTP 200 body should skip parsing/import",repository.downloadFile(remote,false))
                assertFalse(repository.hasPending(remote));assertEquals("second",repository.state(remote).etag)
                assertEquals(0,allWrites(db))
                body.set(m3u(episodes(17)));etag.set("third")
                db.execSQL("CREATE TRIGGER fail_hash_import BEFORE INSERT ON channels WHEN NEW.episode=17 BEGIN SELECT RAISE(ABORT,'simulated failure'); END")
                assertTrue(repository.downloadFile(remote,false))
                try {repository.importPending(remote);fail("Must roll back")}
                catch(_: android.database.sqlite.SQLiteException) { }
                assertEquals(15,repository.overview(remote).total);assertEquals(0,allWrites(db))
                assertTrue("Failed import must not mark the new body as committed",repository.downloadFile(remote,false))
                db.execSQL("DROP TRIGGER fail_hash_import")
                val result=repository.importPending(remote)
                assertEquals(2,result.added);assertEquals(0,result.changed);assertEquals(17,result.total)
                assertEquals(2,writes(db,"channels","INSERT"));assertEquals(0,writes(db,"channels","UPDATE"))
                db.execSQL("DELETE FROM delta_audit")
                repository.close();repository=PlaylistRepository(context);repository.restore(remote)
                assertFalse(repository.downloadFile(remote,false));assertFalse(repository.hasPending(remote))
                assertEquals(17,repository.overview(remote).total);assertEquals(0,allWrites(db))
                assertTrue(repository.downloadFile(remote,false,compareBody=false))
                val forced=repository.importPending(remote)
                assertEquals(0,forced.added);assertEquals(0,forced.changed);assertEquals(0,allWrites(db))
            }
        } finally {repository.close();server.close();worker.join(1000)}
    }

    @Test fun reclassificationRemovesOldGroupAndSeriesCountsWithoutTouchingOtherContents() {
        val repository=PlaylistRepository(context)
        try {
            val movie=Channel("Conteúdo",url(900),"Catálogo",typeHint="movie")
            val series=movie.copy(kind=MediaKind.SERIES,typeHint="series",seriesId="42",seriesTitle="Série corrigida",seasonNumber=1,episodeNumber=1)
            repository.appendCache(source,Download(Playlist(listOf(movie)),"",""))
            repository.appendCache(source,Download(Playlist(listOf(series)),"",""))
            assertEquals(0,repository.overview(source).movies);assertEquals(1,repository.overview(source).episodes)
            assertEquals(1,repository.overview(source).series)
            assertTrue(repository.overview(source).groups[MediaKind.MOVIES].orEmpty().isEmpty())
            assertEquals(listOf("Catálogo"),repository.overview(source).groups[MediaKind.SERIES])
            repository.appendCache(source,Download(Playlist(listOf(movie)),"",""))
            assertEquals(1,repository.overview(source).movies);assertEquals(0,repository.overview(source).episodes)
            assertEquals(0,repository.overview(source).series)
            assertTrue(repository.overview(source).groups[MediaKind.SERIES].orEmpty().isEmpty())
            assertEquals(listOf("Catálogo"),repository.overview(source).groups[MediaKind.MOVIES])
        } finally {repository.close()}
    }

    @Test fun deltaAggregatesMatchAFullImportAcrossKindsSeasonsAndPartialResponses() {
        val repository=PlaylistRepository(context)
        try {
            val initial=episodes(15)+Channel("Canal","https://example.test/live/1","Canais")+
                Channel("Filme","https://example.test/movie/1","Filmes")
            val extra=listOf(Channel("Eu sou S02E01",url(100),"Séries"),Channel("Nova série S01E01",url(101),"Séries novas"),
                Channel("Novo filme","https://example.test/movie/2","Filmes"))
            repository.importReader(source,m3u(initial).reader())
            repository.importReader(source,m3u(extra).reader())
            val other="https://example.test/full.m3u"
            repository.importReader(other,m3u(initial+extra).reader())
            assertEquals(repository.overview(other),repository.overview(source))
            val query=BrowseQuery(source,MediaKind.SERIES)
            assertEquals(repository.series(query.copy(source=other)).map {Triple(it.title,it.episodeCount,it.seasons)},
                repository.series(query).map {Triple(it.title,it.episodeCount,it.seasons)})
            val oldId=seriesIdentity(episodes(1).first()).id
            assertEquals(listOf(1,2),repository.seriesDetail(source,oldId)!!.seasons)
        } finally {repository.close()}
    }
}
