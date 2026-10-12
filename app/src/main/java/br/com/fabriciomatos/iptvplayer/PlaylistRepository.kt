package br.com.fabriciomatos.iptvplayer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.paging.PagingSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference
import java.util.zip.GZIPInputStream

data class Download(val playlist: Playlist, val etag: String, val modified: String)
data class CacheState(val etag: String = "", val modified: String = "", val epgUrl: String = "", val checked: Long = 0L, val updated: Long = 0L)
data class ImportResult(val added: Int, val total: Int)

/** Only page-sized objects live in memory. The complete catalog stays in SQLite. */
class PlaylistRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aurora_iptv_preferences", Context.MODE_PRIVATE)
    private val helper = CatalogDatabase(app)
    private val pages = CopyOnWriteArrayList<WeakReference<PagingSource<*, *>>>()
    private var favoritesSynced = false
    fun savedUrl() = prefs.getString("playlist_url", "").orEmpty()
    fun savedName() = prefs.getString("playlist_name", "Minha playlist").orEmpty()
    fun favorites() = prefs.getStringSet("favorite_urls", emptySet()).orEmpty().toSet()
    fun lastUrl() = prefs.getString("last_channel_url", "").orEmpty()
    fun saveLast(channel: Channel) { prefs.edit().putString("last_channel_url", channel.url).commit() }

    @Synchronized fun saveFavorites(urls: Set<String>) {
        if (!prefs.edit().putStringSet("favorite_urls", urls).commit()) throw IOException("Não consegui salvar os favoritos.")
        syncFavorites(urls)
    }
    private fun syncFavorites(urls: Set<String> = favorites()) {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("favorites", null, null)
            db.compileStatement("INSERT OR IGNORE INTO favorites(url) VALUES(?)").use { statement ->
                urls.forEach { statement.bindString(1, it); statement.executeInsert() }
            }
            db.setTransactionSuccessful(); favoritesSynced = true
        } finally { db.endTransaction() }
    }
    fun saveSource(url: String, name: String) {
        val edit = prefs.edit().putString("playlist_url", url).putString("playlist_name", name.ifBlank { "Minha playlist" })
        if (!prefs.contains("cache_source_hash")) edit.putString("cache_source_hash", digest(savedUrl()))
        if (!edit.commit()) throw IOException("Não consegui salvar o endereço neste aparelho.")
    }
    fun state(source: String): CacheState = state(helper.readableDatabase, digest(source))
    private fun state(db: SQLiteDatabase, key: String): CacheState =
        db.query("playlists", arrayOf("etag", "modified", "epg_url", "checked", "updated"), "source_key=?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) CacheState(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) else CacheState()
        }

    @Synchronized fun claimDailyAttempt(source: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = helper.writableDatabase; val key = digest(source)
        db.beginTransactionNonExclusive()
        try {
            // An interrupted first import is recoverable, even on the same day.
            val empty = scalar(db, "SELECT total_count FROM playlists WHERE source_key=?", arrayOf(key)) == 0
            if (!empty && !shouldRefreshDaily(state(db, key).checked, now)) return false
            ensureSource(db, key)
            db.update("playlists", ContentValues().apply { put("checked", now) }, "source_key=?", arrayOf(key))
            db.setTransactionSuccessful(); return true
        } finally { db.endTransaction() }
    }
    @Synchronized fun recordAttempt(source: String, now: Long = System.currentTimeMillis()) {
        val db = helper.writableDatabase; val key = digest(source)
        ensureSource(db, key)
        db.update("playlists", ContentValues().apply { put("checked", now) }, "source_key=?", arrayOf(key))
    }

    /** Upgrade/migrate in bounded batches; never materialize 300,000 Channel objects. */
    @Synchronized fun restore(source: String, progress: (CatalogProgress) -> Unit = {}) {
        if (!favoritesSynced) syncFavorites()
        if (source.isBlank()) return
        val db = helper.writableDatabase; val key = digest(source)
        ensureSource(db, key)
        val count = scalar(db, "SELECT COUNT(*) FROM channels WHERE source_key=?", arrayOf(key))
        if (count == 0) migrateLegacy(source, progress)
        val remaining = scalar(db, "SELECT COUNT(*) FROM channels WHERE source_key=? AND (kind=-1 OR adult=-1)", arrayOf(key))
        if (remaining > 0) {
            val kinds = metadata(source).categories
            var done = 0
            while (true) {
                val batch = db.rawQuery("SELECT " + RAW_COLUMNS + " FROM channels c WHERE source_key=? AND (kind=-1 OR adult=-1) LIMIT 256", arrayOf(key)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(readChannel(cursor)) }
                }
                if (batch.isEmpty()) break
                db.beginTransactionNonExclusive()
                try {
                    batch.forEach { indexRow(db, key, typed(it, kinds)) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                done += batch.size
                progress(CatalogProgress(CatalogStage.PREPARING, done * 98 / remaining))
            }
        }
        // A process may stop after the last migration batch and before aggregation.
        if (remaining > 0 || scalar(db, "SELECT indexed FROM playlists WHERE source_key=?", arrayOf(key)) == 0) {
            db.beginTransactionNonExclusive()
            try { rebuild(db, key); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
    }

    fun overview(source: String): CatalogOverview {
        if (source.isBlank()) return CatalogOverview()
        val key = digest(source); val db = helper.readableDatabase
        val groups = mutableMapOf<MediaKind, MutableList<String>>()
        db.rawQuery("SELECT kind,group_name FROM catalog_groups WHERE source_key=? ORDER BY group_name", arrayOf(key)).use {
            while (it.moveToNext()) groups.getOrPut(MediaKind.entries[it.getInt(0)]) { ArrayList() }.add(it.getString(1))
        }
        return db.rawQuery("SELECT total_count,live_count,movie_count,episode_count,series_count FROM playlists WHERE source_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) CatalogOverview(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4), groups) else CatalogOverview()
        }
    }
    private fun <T : Any> tracked(page: CatalogPage<T>): PagingSource<Int, T> {
        pages.add(WeakReference(page)); return page
    }
    fun channelPages(query: BrowseQuery): PagingSource<Int, Channel> = tracked(CatalogPage { offset, limit -> channels(query, offset, limit) })
    fun seriesPages(query: BrowseQuery): PagingSource<Int, SeriesEntry> = tracked(CatalogPage { offset, limit -> series(query, offset, limit) })
    fun invalidatePages() { pages.forEach { it.get()?.invalidate() }; pages.removeAll { it.get() == null || it.get()?.invalid == true } }

    private fun where(query: BrowseQuery, shows: Boolean): Pair<String, Array<String>> {
        val clauses = mutableListOf("c.source_key=?"); val args = mutableListOf(digest(query.source))
        if (query.group == ADULT_GROUP) { clauses.add("c.adult=1");if(!query.includeAdult)clauses.add("1=0") }
        else if (!query.includeAdult) clauses.add("c.adult=0")
        if (shows) {
            if (query.group != null && query.group != ADULT_GROUP) {
                clauses.add("EXISTS(SELECT 1 FROM series_groups g WHERE g.source_key=c.source_key AND g.series_key=c.series_key AND g.group_name=?)")
                args.add(query.group)
            }
            if (query.favoritesOnly) clauses.add("EXISTS(SELECT 1 FROM channels e JOIN favorites f ON e.url=f.url WHERE e.source_key=c.source_key AND e.series_key=c.series_key)")
        } else {
            query.kind?.let { clauses.add("c.kind=?"); args.add(it.ordinal.toString()) }
            query.group?.takeIf { it != ADULT_GROUP }?.let {
                val names = query.groupAliases.ifEmpty { if(it == RECENT_GROUP)emptyList() else listOf(it) }
                val groupClause = if(names.isEmpty())"0=1" else "c.group_name IN("+List(names.size){"?"}.joinToString(",")+")"
                if(it == RECENT_GROUP) {
                    clauses.add("("+groupClause+" OR c.first_added>=?)")
                    args.addAll(names);args.add((System.currentTimeMillis()-30L*86400000).toString())
                } else {clauses.add(groupClause);args.addAll(names)}
            }
            query.seriesId?.let { clauses.add("c.series_key=?"); args.add(it) }
            query.season?.let { clauses.add("c.season=?"); args.add(it.toString()) }
            if (query.favoritesOnly) clauses.add("EXISTS(SELECT 1 FROM favorites f WHERE f.url=c.url)")
        }
        normalized(query.search).split(' ').filter(String::isNotBlank).forEach {
            clauses.add("c.search_text LIKE ?"); args.add("%" + it + "%")
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
    fun matchingCount(query: BrowseQuery): Int {
        val shows = query.kind == MediaKind.SERIES && query.seriesId == null
        val (where, args) = where(query, shows)
        return scalar(helper.readableDatabase, "SELECT COUNT(*) FROM " + (if (shows) "series_directory" else "channels") + " c WHERE " + where, args)
    }
    /** Find the remembered item in the complete filtered catalog, beyond the loaded pages. */
    fun catalogOffset(query: BrowseQuery, item: String): Int? {
        val shows=query.kind==MediaKind.SERIES && query.seriesId==null
        val (where,args)=where(query,shows)
        val table=if(shows)"series_directory" else "channels"
        val id=if(shows)"series_key"else"url"
        val fields=if(shows)"c.name_norm,c.series_key"else"c.position,c.first_added,c.season,CASE WHEN c.episode>0 THEN c.episode ELSE 2147483647 END"
        val db=helper.readableDatabase
        return db.rawQuery("SELECT $fields FROM $table c WHERE $where AND c.$id=?",args+item).use {cursor->
            if(!cursor.moveToFirst())return@use null
            val (before,values)=when {
                shows->"(c.name_norm<? OR (c.name_norm=? AND c.series_key<?))" to arrayOf(cursor.getString(0),cursor.getString(0),cursor.getString(1))
                query.group==RECENT_GROUP->"(c.first_added>? OR (c.first_added=? AND c.position>?))" to arrayOf(cursor.getLong(1).toString(),cursor.getLong(1).toString(),cursor.getLong(0).toString())
                query.seriesId!=null->{
                    val episode="CASE WHEN c.episode>0 THEN c.episode ELSE 2147483647 END"
                    "(c.season<? OR (c.season=? AND $episode<?) OR (c.season=? AND $episode=? AND c.position<?))" to arrayOf(cursor.getInt(2).toString(),cursor.getInt(2).toString(),cursor.getInt(3).toString(),cursor.getInt(2).toString(),cursor.getInt(3).toString(),cursor.getLong(0).toString())
                }
                else->"c.position<?" to arrayOf(cursor.getLong(0).toString())
            }
            scalar(db,"SELECT COUNT(*) FROM $table c WHERE $where AND $before",args+values)
        }
    }
    fun channels(query: BrowseQuery, offset: Int = 0, limit: Int = 60): List<Channel> {
        if (query.source.isBlank()) return emptyList()
        val (where, args) = where(query, false)
        val order = if(query.group==RECENT_GROUP)"c.first_added DESC,c.position DESC" else if (query.seriesId == null) "c.position" else "c.season,CASE WHEN c.episode>0 THEN c.episode ELSE 2147483647 END,c.position"
        return helper.readableDatabase.rawQuery("SELECT " + CHANNEL_COLUMNS + " FROM channels c LEFT JOIN series_posters p ON p.source_key=c.source_key AND p.name_norm=c.series_norm WHERE " +
            where + " ORDER BY " + order + " LIMIT ? OFFSET ?", args + arrayOf(limit.coerceIn(1, 1000).toString(), offset.coerceAtLeast(0).toString())).use {
            buildList { while (it.moveToNext()) add(readChannel(it)) }
        }
    }
    fun series(query: BrowseQuery, offset: Int = 0, limit: Int = 60): List<SeriesEntry> {
        if (query.source.isBlank()) return emptyList()
        val (where, args) = where(query, true)
        return helper.readableDatabase.rawQuery("SELECT c.series_key,c.title,CASE WHEN EXISTS(SELECT 1 FROM series_groups ag WHERE ag.source_key=c.source_key AND ag.series_key=c.series_key AND (lower(ag.group_name) LIKE '%crunchyroll%' OR lower(ag.group_name) LIKE '%anime%' OR lower(ag.group_name) LIKE '%funimation%' OR lower(ag.group_name) LIKE '%hidive%')) THEN COALESCE(NULLIF((SELECT ac.image FROM anime_covers ac WHERE ac.source_key=c.source_key AND ac.name_norm=c.name_norm),''),CASE WHEN p.verified_anime=1 THEN p.image ELSE '' END) ELSE COALESCE(NULLIF(p.image,''),c.image) END,c.episode_count,c.seasons,c.adult,EXISTS(SELECT 1 FROM series_groups ag WHERE ag.source_key=c.source_key AND ag.series_key=c.series_key AND (lower(ag.group_name) LIKE '%crunchyroll%' OR lower(ag.group_name) LIKE '%anime%' OR lower(ag.group_name) LIKE '%funimation%' OR lower(ag.group_name) LIKE '%hidive%')) FROM series_directory c LEFT JOIN series_posters p ON p.source_key=c.source_key AND p.name_norm=c.name_norm WHERE " +
            where + " ORDER BY c.name_norm,c.series_key LIMIT ? OFFSET ?", args + arrayOf(limit.coerceIn(1, 1000).toString(), offset.coerceAtLeast(0).toString())).use {
            buildList { while (it.moveToNext()) add(readSeries(it)) }
        }
    }
    fun seriesDetail(source: String, id: String): SeriesEntry? = helper.readableDatabase.rawQuery(
        "SELECT c.series_key,c.title,CASE WHEN EXISTS(SELECT 1 FROM series_groups ag WHERE ag.source_key=c.source_key AND ag.series_key=c.series_key AND (lower(ag.group_name) LIKE '%crunchyroll%' OR lower(ag.group_name) LIKE '%anime%' OR lower(ag.group_name) LIKE '%funimation%' OR lower(ag.group_name) LIKE '%hidive%')) THEN COALESCE(NULLIF((SELECT ac.image FROM anime_covers ac WHERE ac.source_key=c.source_key AND ac.name_norm=c.name_norm),''),CASE WHEN p.verified_anime=1 THEN p.image ELSE '' END) ELSE COALESCE(NULLIF(p.image,''),c.image) END,c.episode_count,c.seasons,c.adult,EXISTS(SELECT 1 FROM series_groups ag WHERE ag.source_key=c.source_key AND ag.series_key=c.series_key AND (lower(ag.group_name) LIKE '%crunchyroll%' OR lower(ag.group_name) LIKE '%anime%' OR lower(ag.group_name) LIKE '%funimation%' OR lower(ag.group_name) LIKE '%hidive%')) FROM series_directory c LEFT JOIN series_posters p ON p.source_key=c.source_key AND p.name_norm=c.name_norm WHERE c.source_key=? AND c.series_key=?",
        arrayOf(digest(source), id)).use { if (it.moveToFirst()) readSeries(it) else null }
    fun seriesId(source: String, url: String): String? = helper.readableDatabase.rawQuery("SELECT series_key FROM channels WHERE source_key=? AND url=?", arrayOf(digest(source), url)).use {
        if (it.moveToFirst()) it.getString(0).takeIf(String::isNotBlank) else null
    }
    fun channel(source: String, url: String): Channel? = helper.readableDatabase.rawQuery("SELECT " + CHANNEL_COLUMNS +
        " FROM channels c LEFT JOIN series_posters p ON p.source_key=c.source_key AND p.name_norm=c.series_norm WHERE c.source_key=? AND c.url=?",
        arrayOf(digest(source), url)).use { if (it.moveToFirst()) readChannel(it) else null }
    fun liveIds(source: String): Set<String> = helper.readableDatabase.rawQuery("SELECT DISTINCT tvg_id FROM channels WHERE source_key=? AND kind=0 AND tvg_id<>''", arrayOf(digest(source))).use {
        buildSet { while (it.moveToNext()) add(it.getString(0)) }
    }
    private fun readSeries(cursor: Cursor) = SeriesEntry(cursor.getString(0), cursor.getString(1), knownAnimePoster(cursor.getString(1)) ?: cursor.getString(2), emptySet(), emptyList(),
        cursor.getInt(3), cursor.getString(4).split(',').mapNotNull(String::toIntOrNull).distinct().sorted(),cursor.getInt(5)!=0,cursor.getInt(6)!=0)
    private fun readChannel(cursor: Cursor): Channel {
        val kind = cursor.getInt(14).takeIf { it in 0..3 }?.let { MediaKind.entries[it] }
            ?: classifyContent(cursor.getString(0), cursor.getString(2), cursor.getString(1), cursor.getString(8))
        return Channel(cursor.getString(0), cursor.getString(1), cursor.getString(2), if(kind==MediaKind.SERIES && isAnimeGroup(cursor.getString(2)))knownAnimePoster(cursor.getString(10)) ?: cursor.getString(11) else cursor.getString(3), cursor.getString(4),
            cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8), cursor.getString(9),
            cursor.getString(10), knownAnimePoster(cursor.getString(10)) ?: cursor.getString(11), cursor.getInt(12), cursor.getInt(13), kind, cursor.getInt(15).let { if(it<0)isAdultGroup(cursor.getString(2)) else it!=0 })
    }

    fun metadata(source: String): ProviderMetadata {
        val key = digest(source); val db = helper.readableDatabase
        val kinds = HashMap<String, MediaKind>(); val posters = HashMap<String, SeriesPoster>()
        db.rawQuery("SELECT group_name,kind FROM provider_categories WHERE source_key=?", arrayOf(key)).use {
            while (it.moveToNext()) kinds[it.getString(0)] = MediaKind.entries[it.getInt(1)]
        }
        db.rawQuery("SELECT name_norm,title,image,provider_id,verified_anime FROM series_posters WHERE source_key=?", arrayOf(key)).use {
            while (it.moveToNext()) posters[it.getString(0)] = SeriesPoster(it.getString(1), it.getString(2), it.getString(3),it.getInt(4)==1)
        }
        return ProviderMetadata(kinds, posters)
    }
    fun needsMetadata(source: String): Boolean {
        if (ProviderApi.endpoint(source, "get_series") == null) return false
        return helper.readableDatabase.rawQuery("SELECT metadata_ready,metadata_checked FROM playlists WHERE source_key=?", arrayOf(digest(source))).use {
            !it.moveToFirst() || (it.getInt(0) < 2 && shouldRefreshDaily(it.getLong(1)))
        }
    }
    suspend fun fetchMetadata(source: String): ProviderMetadata {
        val key = digest(source); val db = helper.writableDatabase
        ensureSource(db, key)
        db.update("playlists", ContentValues().apply { put("metadata_checked", System.currentTimeMillis()) }, "source_key=?", arrayOf(key))
        val fresh = ProviderApi.load(source)
        saveMetadata(source, fresh)
        return metadata(source)
    }
    @Synchronized fun saveMetadata(source: String, fresh: ProviderMetadata) {
        val key = digest(source); val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            ensureSource(db, key)
            db.compileStatement("INSERT OR REPLACE INTO provider_categories(source_key,group_name,kind) VALUES(?,?,?)").use { statement ->
                fresh.categories.forEach { (name, kind) -> statement.bindString(1,key); statement.bindString(2,name); statement.bindLong(3,kind.ordinal.toLong()); statement.executeInsert() }
            }
            db.compileStatement("INSERT OR REPLACE INTO series_posters(source_key,name_norm,title,image,provider_id,verified_anime) VALUES(?,?,?,?,?,?)").use { statement ->
                fresh.posters.forEach { (name, poster) ->
                    statement.bindString(1,key); statement.bindString(2,name); statement.bindString(3,poster.title)
                    statement.bindString(4,poster.image); statement.bindString(5,poster.providerId);statement.bindLong(6,if(poster.anime)1 else 0); statement.executeInsert()
                }
            }
            val ready = if (fresh.posters.isNotEmpty()) 2 else if (fresh.categories.isNotEmpty()) 1 else 0
            if (ready > 0) db.update("playlists", ContentValues().apply { put("metadata_ready",ready) }, "source_key=?",arrayOf(key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun repairMetadata(source: String, metadata: ProviderMetadata) {
        val db = helper.writableDatabase; val key = digest(source)
        db.beginTransactionNonExclusive()
        try {
            metadata.categories.forEach { (group, kind) ->
                db.execSQL("UPDATE channels SET kind=-1 WHERE source_key=? AND group_name=? AND kind<>?", arrayOf(key,group,kind.ordinal))
            }
            while (true) {
                val batch = db.rawQuery("SELECT " + RAW_COLUMNS + " FROM channels c WHERE source_key=? AND (kind=-1 OR adult=-1) LIMIT 256", arrayOf(key)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(readChannel(cursor)) }
                }
                if (batch.isEmpty()) break
                batch.forEach { indexRow(db, key, typed(it, metadata.categories)) }
            }
            rebuild(db,key); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidatePages()
    }

    private fun readyFile(source: String): File = File(app.filesDir, "catalog_pending/" + digest(source) + ".ready")
    fun hasPending(source: String) = readyFile(source).isFile
    /** Download to disk, validate EOF, then atomically mark the file ready for resumable import. */
    suspend fun downloadFile(source: String, conditional: Boolean, progress: (CatalogProgress) -> Unit = {}): Boolean {
        val context = currentCoroutineContext(); val validators = state(source)
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout=15000; readTimeout=30000; instanceFollowRedirects=true
            setRequestProperty("User-Agent","AuroraIPTV/1.6 Android"); setRequestProperty("Accept-Encoding","identity")
            if (conditional) {
                validators.etag.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match",it) }
                validators.modified.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since",it) }
            }
        }
        val ready = readyFile(source); ready.parentFile?.mkdirs()
        val partial = File(ready.parentFile, ready.name + ".partial")
        try {
            if (connection.responseCode == 304) return false
            if (connection.responseCode !in 200..299) throw IOException("O servidor respondeu HTTP " + connection.responseCode + ".")
            var bytes = 0L; var shown = -1L
            val raw = connection.inputStream
            val gzip = connection.contentEncoding.equals("gzip",true)
            val input = if (gzip) GZIPInputStream(raw) else raw
            val total = if(gzip)0L else connection.contentLengthLong
            progress(downloadProgress(0,total))
            input.use { stream -> partial.outputStream().buffered(65536).use { output ->
                val buffer=ByteArray(65536)
                while (true) {
                    context.ensureActive()
                    val n=stream.read(buffer); if(n<0)break
                    output.write(buffer,0,n); bytes+=n
                    val step = if(total>0)bytes*100/total else bytes/(1024*1024)
                    if(step!=shown){ shown=step; progress(downloadProgress(bytes,total)) }
                }
            } }
            if (!gzip && connection.contentLengthLong > 0L && bytes != connection.contentLengthLong) throw IOException("O download da lista ficou incompleto.")
            if (bytes == 0L) throw IOException("A lista veio vazia.")
            java.io.FileOutputStream(partial,true).use { it.fd.sync() }
            if (!partial.renameTo(ready)) throw IOException("Não consegui guardar o download da lista.")
            val info = JSONObject().put("etag",connection.getHeaderField("ETag").orEmpty())
                .put("modified",connection.getHeaderField("Last-Modified").orEmpty()).put("source",connection.url.toString())
            File(ready.parentFile,ready.name + ".info").writeText(info.toString())
            return true
        } finally { connection.disconnect(); partial.delete() }
    }
    fun importPending(source: String, metadata: ProviderMetadata = metadata(source), progress: (CatalogProgress) -> Unit = {}): ImportResult {
        val ready = readyFile(source)
        if (!ready.isFile) return ImportResult(0,overview(source).total)
        val info = runCatching { JSONObject(File(ready.parentFile,ready.name + ".info").readText()) }.getOrNull()
        val total=ready.length()
        val result = ready.inputStream().use { input ->
            val counting=CountingInputStream(input)
            counting.bufferedReader().use { reader -> importReader(source,reader,metadata,info?.optString("etag").orEmpty(),
                info?.optString("modified").orEmpty(),info?.optString("source")?.ifBlank { source } ?: source,progress,
                {counting.bytes},total) }
        }
        ready.delete(); File(ready.parentFile,ready.name + ".info").delete()
        invalidatePages(); return result
    }
    @Synchronized fun importReader(source: String, reader: java.io.Reader, metadata: ProviderMetadata = ProviderMetadata(),
        etag: String = "", modified: String = "", resolvedSource: String = source, progress: (CatalogProgress) -> Unit = {},
        bytesRead: () -> Long = {0L},totalBytes: Long = 0L): ImportResult =
        importRows(source,etag,modified,true,progress = { progress(importProgress(bytesRead(),totalBytes,it)) },
            finalizing={progress(CatalogProgress(CatalogStage.FINALIZING,99))}) { accept ->
            progress(importProgress(0,totalBytes,0))
            val stats=M3uParser.scan(reader,resolvedSource,metadata.categories) { accept(it) }
            stats.epgUrl
        }.also { progress(CatalogProgress(CatalogStage.COMPLETE,100,entries=it.total)) }

    /** Small-fixture compatibility; production always imports via the streaming reader. */
    @Synchronized fun appendCache(source: String, download: Download, now: Long = System.currentTimeMillis(), markChecked: Boolean = true): Int =
        importRows(source,download.etag,download.modified,markChecked,now) { accept -> download.playlist.channels.forEach(accept); download.playlist.epgUrl }.added
    fun readCache(source: String): Playlist {
        restore(source)
        return Playlist(channels(BrowseQuery(source,kind=null),limit=1000),state(source).epgUrl)
    }

    @Synchronized private fun importRows(source: String, etag: String, modified: String, markChecked: Boolean, now: Long = System.currentTimeMillis(),
        progress: (Int) -> Unit = {}, finalizing: () -> Unit = {}, scan: ((Channel) -> Unit) -> String): ImportResult {
        val db=helper.writableDatabase; val key=digest(source); var added=0; var parsed=0
        db.beginTransactionNonExclusive()
        try {
            ensureSource(db,key)
            val firstAdded=if(scalar(db,"SELECT total_count FROM playlists WHERE source_key=?",arrayOf(key))>0)now else 0L
            val generation=scalar(db,"SELECT generation FROM playlists WHERE source_key=?",arrayOf(key))+1
            var position=db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM channels WHERE source_key=?",arrayOf(key)).use { it.moveToFirst();it.getLong(0) }
            val insertSql="INSERT OR IGNORE INTO channels(source_key,url,position,name,group_name,image_url,tvg_id,user_agent,referrer,mime_type,type_hint,series_id,series_title,series_image,season,episode,kind,search_text,series_key,series_norm,episode_title,seen_generation,adult,first_added) VALUES(" + List(24){"?"}.joinToString(",") + ")"
            val updateSql="UPDATE channels SET kind=?,search_text=CASE WHEN search_text='' THEN ? ELSE search_text END,series_key=?,series_norm=?,series_title=?,series_image=?,season=?,episode=?,episode_title=?,type_hint=?,seen_generation=?,adult=? WHERE source_key=? AND url=? AND seen_generation<>?"
            var epg=""
            db.compileStatement(insertSql).use { insert -> db.compileStatement(updateSql).use { update ->
                epg=scan { c ->
                    val identity=if(c.kind==MediaKind.SERIES)seriesIdentity(c) else null
                    val title=identity?.title ?: c.seriesTitle
                    val seriesKey=identity?.id.orEmpty(); val norm=if(identity==null)"" else normalized(identity.title)
                    val season=identity?.episode?.season ?: c.seasonNumber
                    val number=identity?.episode?.number ?: c.episodeNumber
                    val episodeTitle=identity?.episode?.title.orEmpty()
                    insert.clearBindings();insert.bindString(1,key);insert.bindString(2,c.url);insert.bindLong(3,position)
                    listOf(c.name,c.group,c.imageUrl,c.tvgId,c.userAgent,c.referrer,c.mimeType,c.typeHint,c.seriesId,title,c.seriesImageUrl).forEachIndexed { i,v -> insert.bindString(i+4,v) }
                    insert.bindLong(15,season.toLong());insert.bindLong(16,number.toLong());insert.bindLong(17,c.kind.ordinal.toLong())
                    insert.bindString(18,c.searchText);insert.bindString(19,seriesKey);insert.bindString(20,norm);insert.bindString(21,episodeTitle);insert.bindLong(22,generation.toLong());insert.bindLong(23,if(c.adult)1L else 0L)
                    insert.bindLong(24,firstAdded)
                    if(insert.executeInsert()!=-1L){added++;position++}
                    else {
                        update.clearBindings();update.bindLong(1,c.kind.ordinal.toLong());update.bindString(2,c.searchText)
                        update.bindString(3,seriesKey);update.bindString(4,norm);update.bindString(5,title);update.bindString(6,c.seriesImageUrl)
                        update.bindLong(7,season.toLong());update.bindLong(8,number.toLong());update.bindString(9,episodeTitle)
                        update.bindString(10,c.typeHint);update.bindLong(11,generation.toLong());update.bindLong(12,if(c.adult)1L else 0L);update.bindString(13,key);update.bindString(14,c.url);update.bindLong(15,generation.toLong())
                        update.executeUpdateDelete()
                    }
                    parsed++
                    if(parsed%5000==0)progress(parsed)
                }
            } }
            if(parsed==0 && scalar(db,"SELECT COUNT(*) FROM channels WHERE source_key=?",arrayOf(key))==0) throw IOException("Não encontrei conteúdos nesta M3U.")
            val values=ContentValues().apply {
                put("etag",etag);put("modified",modified);put("generation",generation)
                if(epg.isNotBlank())put("epg_url",epg)
                if(markChecked)put("checked",now)
                if(added>0)put("updated",now)
            }
            finalizing()
            db.update("playlists",values,"source_key=?",arrayOf(key));rebuild(db,key)
            val total=scalar(db,"SELECT total_count FROM playlists WHERE source_key=?",arrayOf(key))
            db.setTransactionSuccessful();return ImportResult(added,total)
        } finally { db.endTransaction() }
    }
    private fun typed(channel: Channel, kinds: Map<String, MediaKind>): Channel {
        val hint=kinds[channel.group.trim()]?.name?.lowercase() ?: channel.typeHint
        return channel.copy(typeHint=hint,kind=classifyContent(channel.name,channel.group,channel.url,hint))
    }
    private fun indexRow(db: SQLiteDatabase, key: String, c: Channel) {
        val identity=if(c.kind==MediaKind.SERIES)seriesIdentity(c) else null
        val values=ContentValues().apply {
            put("adult",if(c.adult)1 else 0);put("kind",c.kind.ordinal);put("search_text",c.searchText);put("type_hint",c.typeHint)
            put("series_key",identity?.id.orEmpty());put("series_norm",identity?.title?.let(::normalized).orEmpty())
            put("series_title",identity?.title ?: c.seriesTitle);put("season",identity?.episode?.season ?: c.seasonNumber)
            put("episode",identity?.episode?.number ?: c.episodeNumber);put("episode_title",identity?.episode?.title.orEmpty())
        }
        db.update("channels",values,"source_key=? AND url=?",arrayOf(key,c.url))
    }
    private fun rebuild(db: SQLiteDatabase, key: String) {
        db.delete("series_directory","source_key=?",arrayOf(key))
        db.delete("series_groups","source_key=?",arrayOf(key))
        db.delete("catalog_groups","source_key=?",arrayOf(key))
        db.execSQL("INSERT INTO series_directory(source_key,series_key,name_norm,title,image,episode_count,seasons,search_text,adult) SELECT source_key,series_key,MIN(series_norm),MIN(series_title),COALESCE(NULLIF(MAX(series_image),''),MIN(image_url)),COUNT(*),GROUP_CONCAT(DISTINCT season),MIN(series_norm)||' '||GROUP_CONCAT(DISTINCT group_name),MAX(adult) FROM channels WHERE source_key=? AND kind=2 AND series_key<>'' GROUP BY series_key",arrayOf(key))
        db.execSQL("INSERT INTO series_groups(source_key,series_key,group_name) SELECT DISTINCT source_key,series_key,group_name FROM channels WHERE source_key=? AND kind=2 AND series_key<>''",arrayOf(key))
        db.execSQL("INSERT INTO catalog_groups(source_key,kind,group_name,amount) SELECT source_key,kind,group_name,COUNT(*) FROM channels WHERE source_key=? AND kind<>2 GROUP BY kind,group_name",arrayOf(key))
        db.execSQL("INSERT INTO catalog_groups(source_key,kind,group_name,amount) SELECT source_key,2,group_name,COUNT(*) FROM series_groups WHERE source_key=? GROUP BY group_name",arrayOf(key))
        val counts=IntArray(4)
        db.rawQuery("SELECT kind,COUNT(*) FROM channels WHERE source_key=? GROUP BY kind",arrayOf(key)).use {
            while(it.moveToNext())if(it.getInt(0) in 0..3)counts[it.getInt(0)]=it.getInt(1)
        }
        val values=ContentValues().apply {
            put("total_count",counts.sum());put("live_count",counts[0]);put("movie_count",counts[1]);put("episode_count",counts[2])
            put("series_count",scalar(db,"SELECT COUNT(*) FROM series_directory WHERE source_key=?",arrayOf(key)));put("indexed",1)
        }
        db.update("playlists",values,"source_key=?",arrayOf(key))
    }
    private fun migrateLegacy(source: String, progress: (CatalogProgress) -> Unit) {
        val key=digest(source)
        for(file in listOf(File(app.filesDir,"aurora_playlist_cache.jsonl.bak"),File(app.filesDir,"aurora_playlist_cache.jsonl"))){
            if(!file.isFile)continue
            val header=runCatching { file.bufferedReader().use { JSONObject(it.readLine().orEmpty()) } }.getOrNull()
            if(header?.has("schema")==true){if(header.optString("sourceHash")!=key)continue}
            else if(prefs.getString("cache_source_hash",key)!=key)continue
            progress(CatalogProgress(CatalogStage.PREPARING,0))
            val result=try { importRows(source,"","",false) { accept ->
                file.bufferedReader().useLines { lines -> lines.forEach { raw ->
                    val item=runCatching { JSONObject(raw) }.getOrNull()
                    if(item!=null&&!item.has("schema"))legacyChannel(item)?.let(accept)
                } }
                header?.optString("epgUrl").orEmpty()
            } } catch (_: IOException) { continue }
            if(result.total>0)return
        }
        val raw=prefs.getString("playlist_cache","").orEmpty()
        if(raw.length in 1..8*1024*1024 && prefs.getString("cache_source_hash",key)==key){
            runCatching {
                val array=JSONArray(raw)
                importRows(source,"","",false) { accept -> for(i in 0 until array.length())legacyChannel(array.getJSONObject(i))?.let(accept);"" }
                prefs.edit().remove("playlist_cache").commit()
            }
        }
    }
    private fun legacyChannel(o: JSONObject): Channel? = o.optString("url").takeIf(String::isNotBlank)?.let {
        Channel(o.optString("name","Canal"),it,o.optString("group","Canais"),o.optString("imageUrl"),o.optString("tvgId"),o.optString("userAgent"),o.optString("referrer"),o.optString("mimeType"))
    }
    fun liveChannels(source: String): List<Channel> = helper.readableDatabase.rawQuery("SELECT " + RAW_COLUMNS + " FROM channels c WHERE source_key=? AND kind=0 AND adult=0 ORDER BY position",arrayOf(digest(source))).use {
        buildList { while(it.moveToNext()) add(readChannel(it)) }
    }
    fun saveGuide(source: String, guide: Guide, now: Long = System.currentTimeMillis()) {
        val db=helper.writableDatabase;val key=digest(source)
        db.beginTransactionNonExclusive()
        try {
            db.delete("guide_names","source_key=?",arrayOf(key));db.delete("guide_programmes","source_key=?",arrayOf(key))
            db.compileStatement("INSERT INTO guide_names VALUES(?,?,?)").use { statement ->
                guide.names.forEach { (name,id)->statement.bindString(1,key);statement.bindString(2,name);statement.bindString(3,id);statement.executeInsert() }
            }
            db.compileStatement("INSERT OR IGNORE INTO guide_programmes VALUES(?,?,?,?,?,?,?,?,?)").use { insert ->
                db.compileStatement("UPDATE guide_programmes SET is_game=MAX(is_game,?),show_now=MAX(show_now,?) WHERE source_key=? AND channel_id=? AND start=? AND title=?").use { update ->
                    fun row(id:String,p:Programme,game:Boolean,current:Boolean) {
                        insert.bindString(1,key);insert.bindString(2,id);insert.bindLong(3,p.start);insert.bindLong(4,p.end)
                        insert.bindString(5,p.title);insert.bindString(6,p.image);insert.bindLong(7,if(game)1L else 0L);insert.bindLong(8,if(current)1L else 0L);insert.bindString(9,p.category)
                        if(insert.executeInsert()==-1L) {
                            update.bindLong(1,if(game)1L else 0L);update.bindLong(2,if(current)1L else 0L);update.bindString(3,key)
                            update.bindString(4,id);update.bindLong(5,p.start);update.bindString(6,p.title);update.executeUpdateDelete()
                        }
                    }
                    guide.programmes.forEach { (id,list)->list.forEach {row(id,it,false,true)} }
                    guide.matches.forEach {row(it.channelId,it.programme,true,false)}
                }
            }
            db.execSQL("INSERT OR REPLACE INTO guide_cache VALUES(?,?)",arrayOf(key,now));db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun guideChecked(source: String): Long = helper.readableDatabase.rawQuery("SELECT updated FROM guide_cache WHERE source_key=?",arrayOf(digest(source))).use { if(it.moveToFirst())it.getLong(0)else 0L }

    fun cachedAnimeCover(source: String, title: String, now: Long = System.currentTimeMillis()): String? = helper.readableDatabase.rawQuery(
        "SELECT image,checked FROM anime_covers WHERE source_key=? AND name_norm=?",arrayOf(digest(source),normalized(title))).use {
        if(!it.moveToFirst())null else it.getString(0).takeIf { image -> now-it.getLong(1)<if(image.isEmpty())3600000L else 365L*86400000 }
    }
    fun saveAnimeCover(source: String, title: String, image: String) {
        helper.writableDatabase.execSQL("INSERT OR REPLACE INTO anime_covers VALUES(?,?,?,?,?)",arrayOf(digest(source),normalized(title),image,if(image.isEmpty())0 else 1,System.currentTimeMillis()))
    }
    fun cachedArtwork(key: String, now: Long = System.currentTimeMillis()): String? = helper.readableDatabase.rawQuery(
        "SELECT json,expires FROM artwork_cache WHERE cache_key=?",arrayOf(key)).use { if(it.moveToFirst() && it.getLong(1)>now)it.getString(0)else null }
    fun saveArtwork(key: String, json: String, lifetime: Long = 30L*86400000) {
        helper.writableDatabase.execSQL("INSERT OR REPLACE INTO artwork_cache VALUES(?,?,?)",arrayOf(key,json,System.currentTimeMillis()+lifetime))
    }
    fun savedGuide(source: String): Guide {
        val db=helper.readableDatabase;val key=digest(source)
        val names=HashMap<String,String>();val shows=HashMap<String,MutableList<Programme>>();val matches=ArrayList<GuideMatch>()
        db.rawQuery("SELECT name,channel_id FROM guide_names WHERE source_key=?",arrayOf(key)).use {while(it.moveToNext())names[it.getString(0)]=it.getString(1)}
        db.rawQuery("SELECT channel_id,start,end,title,image,is_game,show_now,category FROM guide_programmes WHERE source_key=? ORDER BY start",arrayOf(key)).use {
            while(it.moveToNext()) {
                val programme=Programme(it.getString(3),it.getLong(1),it.getLong(2),it.getString(4),it.getString(7))
                if(it.getInt(5)==1)matches.add(GuideMatch(it.getString(0),programme))
                if(it.getInt(6)==1)shows.getOrPut(it.getString(0)){ArrayList()}.add(programme)
            }
        }
        return Guide(shows,names,matches)
    }
    fun close() = helper.close()
    companion object {
        private const val RAW_COLUMNS="c.name,c.url,c.group_name,c.image_url,c.tvg_id,c.user_agent,c.referrer,c.mime_type,c.type_hint,c.series_id,c.series_title,c.series_image,c.season,c.episode,c.kind,c.adult"
        private const val CHANNEL_COLUMNS="c.name,c.url,c.group_name,c.image_url,c.tvg_id,c.user_agent,c.referrer,c.mime_type,c.type_hint,c.series_id,c.series_title,CASE WHEN lower(c.group_name) LIKE '%crunchyroll%' OR lower(c.group_name) LIKE '%anime%' OR lower(c.group_name) LIKE '%funimation%' OR lower(c.group_name) LIKE '%hidive%' THEN COALESCE(NULLIF((SELECT ac.image FROM anime_covers ac WHERE ac.source_key=c.source_key AND ac.name_norm=c.series_norm),''),CASE WHEN p.verified_anime=1 THEN p.image ELSE '' END) ELSE COALESCE(NULLIF(p.image,''),c.series_image) END,c.season,c.episode,c.kind,c.adult"
        fun validUrl(value: String): Boolean = runCatching { val u=URL(value);(u.protocol=="http"||u.protocol=="https")&&u.host.isNotBlank() }.getOrDefault(false)
        fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
        private fun ensureSource(db: SQLiteDatabase,key: String){db.execSQL("INSERT OR IGNORE INTO playlists(source_key) VALUES(?)",arrayOf(key))}
        private fun scalar(db: SQLiteDatabase,sql: String,args: Array<String>): Int=db.rawQuery(sql,args).use { if(it.moveToFirst())it.getInt(0)else 0 }
    }
}

private class CatalogDatabase(context: Context): SQLiteOpenHelper(context,"aurora_catalog.db",null,4){
    init{setWriteAheadLoggingEnabled(true)}
    override fun onConfigure(db: SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true);db.execSQL("PRAGMA synchronous=FULL")}
    override fun onCreate(db: SQLiteDatabase){
        db.execSQL("CREATE TABLE playlists(source_key TEXT PRIMARY KEY,etag TEXT NOT NULL DEFAULT '',modified TEXT NOT NULL DEFAULT '',epg_url TEXT NOT NULL DEFAULT '',checked INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE channels(source_key TEXT NOT NULL,url TEXT NOT NULL,position INTEGER NOT NULL,name TEXT NOT NULL,group_name TEXT NOT NULL,image_url TEXT NOT NULL,tvg_id TEXT NOT NULL,user_agent TEXT NOT NULL,referrer TEXT NOT NULL,mime_type TEXT NOT NULL,type_hint TEXT NOT NULL,series_id TEXT NOT NULL,series_title TEXT NOT NULL,series_image TEXT NOT NULL,season INTEGER NOT NULL,episode INTEGER NOT NULL,PRIMARY KEY(source_key,url),FOREIGN KEY(source_key) REFERENCES playlists(source_key))")
        upgrade(db); upgradePreferences(db); upgradeArtwork(db)
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int){
        if(oldVersion<2)upgrade(db)
        if(oldVersion<3)upgradePreferences(db)
        if(oldVersion<4)upgradeArtwork(db)
    }
    private fun upgradeArtwork(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE channels ADD COLUMN first_added INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX channel_recent ON channels(source_key,kind,first_added)")
        db.execSQL("ALTER TABLE guide_programmes ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE artwork_cache(cache_key TEXT PRIMARY KEY,json TEXT NOT NULL,expires INTEGER NOT NULL)")
    }
    private fun upgradePreferences(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE channels ADD COLUMN adult INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE series_directory ADD COLUMN adult INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE series_posters ADD COLUMN verified_anime INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE playlists SET metadata_ready=0,metadata_checked=0")
        db.execSQL("CREATE INDEX channel_adult_order ON channels(source_key,kind,adult,position)")
        db.execSQL("CREATE TABLE anime_covers(source_key TEXT NOT NULL,name_norm TEXT NOT NULL,image TEXT NOT NULL,verified INTEGER NOT NULL,checked INTEGER NOT NULL,PRIMARY KEY(source_key,name_norm))")
        db.execSQL("CREATE TABLE guide_cache(source_key TEXT PRIMARY KEY,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE guide_names(source_key TEXT NOT NULL,name TEXT NOT NULL,channel_id TEXT NOT NULL,PRIMARY KEY(source_key,name))")
        db.execSQL("CREATE TABLE guide_programmes(source_key TEXT NOT NULL,channel_id TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,title TEXT NOT NULL,image TEXT NOT NULL,is_game INTEGER NOT NULL,show_now INTEGER NOT NULL,PRIMARY KEY(source_key,channel_id,start,title))")
    }
    private fun upgrade(db: SQLiteDatabase){
        listOf("kind INTEGER NOT NULL DEFAULT -1","search_text TEXT NOT NULL DEFAULT ''","series_key TEXT NOT NULL DEFAULT ''","series_norm TEXT NOT NULL DEFAULT ''",
            "episode_title TEXT NOT NULL DEFAULT ''","seen_generation INTEGER NOT NULL DEFAULT 0").forEach { db.execSQL("ALTER TABLE channels ADD COLUMN "+it) }
        listOf("total_count","live_count","movie_count","episode_count","series_count","indexed","generation","metadata_ready","metadata_checked").forEach {
            db.execSQL("ALTER TABLE playlists ADD COLUMN "+it+" INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("CREATE TABLE favorites(url TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE provider_categories(source_key TEXT NOT NULL,group_name TEXT NOT NULL,kind INTEGER NOT NULL,PRIMARY KEY(source_key,group_name))")
        db.execSQL("CREATE TABLE series_posters(source_key TEXT NOT NULL,name_norm TEXT NOT NULL,title TEXT NOT NULL,image TEXT NOT NULL,provider_id TEXT NOT NULL,PRIMARY KEY(source_key,name_norm))")
        db.execSQL("CREATE TABLE series_directory(source_key TEXT NOT NULL,series_key TEXT NOT NULL,name_norm TEXT NOT NULL,title TEXT NOT NULL,image TEXT NOT NULL,episode_count INTEGER NOT NULL,seasons TEXT NOT NULL,search_text TEXT NOT NULL,PRIMARY KEY(source_key,series_key))")
        db.execSQL("CREATE TABLE series_groups(source_key TEXT NOT NULL,series_key TEXT NOT NULL,group_name TEXT NOT NULL,PRIMARY KEY(source_key,series_key,group_name))")
        db.execSQL("CREATE TABLE catalog_groups(source_key TEXT NOT NULL,kind INTEGER NOT NULL,group_name TEXT NOT NULL,amount INTEGER NOT NULL,PRIMARY KEY(source_key,kind,group_name))")
        db.execSQL("CREATE INDEX channel_kind_order ON channels(source_key,kind,position)")
        db.execSQL("CREATE INDEX channel_group_order ON channels(source_key,kind,group_name,position)")
        db.execSQL("CREATE INDEX channel_series_order ON channels(source_key,series_key,season,episode,position)")
        db.execSQL("CREATE INDEX series_sort ON series_directory(source_key,name_norm)")
        db.execSQL("CREATE INDEX series_group_filter ON series_groups(source_key,group_name,series_key)")
    }
}
