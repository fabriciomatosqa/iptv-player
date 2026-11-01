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

@RunWith(AndroidJUnit4::class)
class CatalogPrefixTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/prefix.m3u"
    private fun url(i: Int)="https://example.test/play/aZ_"+i.toString(16).padStart(61,'0')
    private fun rows(count: Int)=(1..count).joinToString("") {"#EXTINF:-1 group-title=\"Séries\",Append S01E${it.toString().padStart(2,'0')}\n${url(it)}\n"}
    private fun stage(body: String,etag: String="tag",resolved: String=source) {
        val file=File(context.filesDir,"catalog_pending/"+PlaylistRepository.digest(source)+".ready")
        file.parentFile!!.mkdirs();file.writeText(body)
        File(file.parentFile,file.name+".info").writeText(JSONObject().put("etag",etag).put("source",resolved).toString())
    }
    private fun initialEntries(progress: List<CatalogProgress>)=progress.first {it.stage==CatalogStage.IMPORTING}.entries
    @Before fun reset()=resetCatalog(context)

    @Test fun exactCommittedPrefixOnlyParsesAppendedEpisodesAndKeepsBooksAndDirectory() {
        val repo=PlaylistRepository(context)
        try {
            repo.saveSource(source,"Append")
            stage("#EXTM3U\n"+rows(15),"first");assertEquals(15,repo.importPending(source).added)
            repo.saveLast(repo.channel(source,url(1))!!)
            repo.savePlaybackProgress(source,url(1),PlaybackProgress(9000,120000))
            val id=repo.series(BrowseQuery(source,MediaKind.SERIES)).single().id
            repo.saveSeriesFavorites(source,setOf(id))
            stage("#EXTM3U\n"+rows(17),"second")
            val progress=mutableListOf<CatalogProgress>()
            val result=repo.importPending(source,progress={progress.add(it)})
            assertEquals(15,initialEntries(progress));assertEquals(2,result.added);assertEquals(0,result.changed)
            assertEquals(17,result.total);assertEquals(17,repo.seriesDetail(source,id)!!.episodeCount)
            assertEquals(setOf(id),repo.seriesFavorites(source));assertEquals(url(1),repo.lastUrl(source))
            assertEquals(9000L,repo.playbackProgress(source,url(1))!!.positionMs)
        } finally {repo.close()}
        PlaylistRepository(context).let {repo ->try {
            stage("#EXTM3U\n"+rows(19))
            val progress=mutableListOf<CatalogProgress>()
            assertEquals(2,repo.importPending(source,progress={progress.add(it)}).added)
            assertEquals(17,initialEntries(progress))
        } finally {repo.close()}}
    }

    @Test fun changedOldBytesAndForcedRefreshUseFullComparison() {
        val repo=PlaylistRepository(context)
        try {
            stage("#EXTM3U\n"+rows(15));repo.importPending(source)
            stage(("#EXTM3U\n"+rows(17)).replace("https://example.test/play/","https://changed.test/play/"))
            val progress=mutableListOf<CatalogProgress>()
            val result=repo.importPending(source,progress={progress.add(it)})
            assertEquals(0,initialEntries(progress));assertEquals(2,result.added);assertEquals(15,result.changed)
            stage(("#EXTM3U\n"+rows(19)).replace("https://example.test/play/","https://changed.test/play/"))
            progress.clear()
            assertEquals(2,repo.importPending(source,progress={progress.add(it)},reusePrefix=false).added)
            assertEquals(0,initialEntries(progress))
        } finally {repo.close()}
    }

    @Test fun changedProviderCategoriesDisableReuseEvenForEqualBytes() {
        val repo=PlaylistRepository(context)
        fun body(count: Int)="#EXTM3U\n"+(1..count).joinToString("") {"#EXTINF:-1 group-title=\"Mix\",Entry $it\n${url(it)}\n"}
        try {
            stage(body(10));repo.importPending(source)
            stage(body(12))
            val progress=mutableListOf<CatalogProgress>()
            val result=repo.importPending(source,ProviderMetadata(categories=mapOf("Mix" to MediaKind.MOVIES)),{progress.add(it)})
            assertEquals(0,initialEntries(progress));assertEquals(2,result.added);assertEquals(10,result.changed)
            assertEquals(12,repo.overview(source).movies);assertEquals(0,repo.overview(source).live)
        } finally {repo.close()}
    }

    @Test fun inheritedParserOptionsAndMissingNewlineNeverUsePrefixShortcut() {
        val repo=PlaylistRepository(context)
        try {
            val first="#EXTM3U\n#EXTINF:-1,First\n${url(1)}\n#EXTGRP:Inherited group\n#EXTVLCOPT:http-user-agent=InheritedAgent\n"
            stage(first);repo.importPending(source)
            stage(first+url(2)+"\n")
            val progress=mutableListOf<CatalogProgress>()
            assertEquals(1,repo.importPending(source,progress={progress.add(it)}).added)
            assertEquals(0,initialEntries(progress))
            assertEquals("Inherited group",repo.channel(source,url(2))!!.group)
            assertEquals("InheritedAgent",repo.channel(source,url(2))!!.userAgent)
            stage("#EXTM3U\n"+rows(15).trimEnd());repo.importPending(source)
            stage("#EXTM3U\n"+rows(17));progress.clear()
            repo.importPending(source,progress={progress.add(it)})
            assertEquals(0,initialEntries(progress))
        } finally {repo.close()}
    }

    @Test fun duplicatePrefixRowsKeepAutomaticNamesAndPositionsCorrect() {
        val repo=PlaylistRepository(context)
        val entry={i: Int->"#EXTINF:-1 group-title=\"Ao Vivo\",\n${url(i)}\n"}
        try {
            val first="#EXTM3U\n"+entry(1)+entry(1)+entry(2)
            stage(first);assertEquals(2,repo.importPending(source).added)
            stage(first+entry(3))
            val progress=mutableListOf<CatalogProgress>()
            assertEquals(1,repo.importPending(source,progress={progress.add(it)}).added)
            assertEquals(3,initialEntries(progress));assertEquals("Canal 4",repo.channel(source,url(3))!!.name)
            assertEquals(listOf(url(1),url(2),url(3)),repo.channels(BrowseQuery(source,MediaKind.LIVE)).map {it.url})
        } finally {repo.close()}
    }

    @Test fun rollbackKeepsPrefixReceiptAndRetriesWithoutLosingRows() {
        val repo=PlaylistRepository(context)
        try {
            stage("#EXTM3U\n"+rows(15),"saved");repo.importPending(source)
            val cacheKey="catalog-prefix:"+PlaylistRepository.digest(source)
            val receipt=repo.cachedArtwork(cacheKey)
            SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
                db.execSQL("CREATE TRIGGER reject_append BEFORE INSERT ON channels WHEN NEW.episode=17 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
                stage("#EXTM3U\n"+rows(17),"next")
                try {repo.importPending(source);fail("Append must roll back")}
                catch(_: android.database.sqlite.SQLiteException) { }
                assertEquals(15,repo.overview(source).total);assertEquals("saved",repo.state(source).etag)
                assertEquals(receipt,repo.cachedArtwork(cacheKey));assertTrue(repo.hasPending(source))
                db.execSQL("DROP TRIGGER reject_append")
                val progress=mutableListOf<CatalogProgress>()
                assertEquals(2,repo.importPending(source,progress={progress.add(it)}).added)
                assertEquals(15,initialEntries(progress));assertEquals(17,repo.overview(source).total)
            }
        } finally {repo.close()}
    }

    @Test fun relativeUrlsAreResolvedAgainWhenTheRedirectTargetChanges() {
        val repo=PlaylistRepository(context)
        val relative={count: Int->("#EXTM3U\n"+rows(count)).replace("https://example.test","")}
        try {
            repo.saveSource(source,"Redirect")
            stage(relative(15),resolved="https://one.test/root/list.m3u");repo.importPending(source)
            val old=url(1).replace("example.test","one.test")
            repo.saveFavorites(setOf(old));repo.savePlaybackProgress(source,old,PlaybackProgress(9000,120000))
            stage(relative(17),resolved="https://two.test/root/list.m3u")
            val progress=mutableListOf<CatalogProgress>()
            val result=repo.importPending(source,progress={progress.add(it)})
            val fresh=url(1).replace("example.test","two.test")
            assertEquals(0,initialEntries(progress));assertEquals(2,result.added);assertEquals(15,result.changed)
            assertEquals(17,result.total);assertTrue(repo.favorites().contains(fresh))
            assertEquals(9000L,repo.playbackProgress(source,fresh)!!.positionMs)
        } finally {repo.close()}
    }

    @Test fun directImportsInvalidateTheReceiptAndStaleReceiptsAreCheckedInsideTheTransaction() {
        val repo=PlaylistRepository(context)
        try {
            stage("#EXTM3U\n"+rows(15));repo.importPending(source)
            repo.appendCache(source,Download(Playlist(listOf(Channel("Other HD",url(100),"Ao Vivo"))),"",""))
            assertNull(repo.cachedArtwork("catalog-prefix:"+PlaylistRepository.digest(source)))
            stage("#EXTM3U\n"+rows(17))
            val progress=mutableListOf<CatalogProgress>()
            assertEquals(2,repo.importPending(source,progress={progress.add(it)}).added)
            assertEquals(0,initialEntries(progress));assertEquals(18,repo.overview(source).total)
            SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
                // Models another writer changing the committed receipt after the preflight read.
                db.execSQL("CREATE TRIGGER change_receipt BEFORE INSERT ON playlists BEGIN UPDATE artwork_cache SET json='changed' WHERE cache_key='catalog-body:"+PlaylistRepository.digest(source)+"'; END")
                stage("#EXTM3U\n"+rows(19));progress.clear()
                assertEquals(2,repo.importPending(source,progress={progress.add(it)}).added)
                assertEquals(0,initialEntries(progress));assertEquals(20,repo.overview(source).total)
                db.execSQL("DROP TRIGGER change_receipt")
            }
        } finally {repo.close()}
    }
}
