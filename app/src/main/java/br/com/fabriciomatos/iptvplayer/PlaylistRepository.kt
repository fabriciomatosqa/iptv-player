package br.com.fabriciomatos.iptvplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class Download(val playlist: Playlist, val etag: String, val modified: String)
data class CacheState(val etag: String = "", val modified: String = "", val epgUrl: String = "", val checked: Long = 0L, val updated: Long = 0L)

/** Catalog and refresh ledger share one durable database, independent of preferences. */
class PlaylistRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aurora_iptv_preferences", Context.MODE_PRIVATE)
    private val helper = CatalogDatabase(app)
    fun savedUrl() = prefs.getString("playlist_url", "").orEmpty()
    fun savedName() = prefs.getString("playlist_name", "Minha playlist").orEmpty()
    fun favorites() = prefs.getStringSet("favorite_urls", emptySet()).orEmpty().toSet()
    fun saveFavorites(urls: Set<String>) {
        if (!prefs.edit().putStringSet("favorite_urls", urls).commit()) throw IOException("Não consegui salvar os favoritos.")
    }
    fun lastUrl() = prefs.getString("last_channel_url", "").orEmpty()
    fun saveLast(channel: Channel) { prefs.edit().putString("last_channel_url", channel.url).apply() }

    fun saveSource(url: String, name: String) {
        val editor = prefs.edit().putString("playlist_url", url).putString("playlist_name", name.ifBlank { "Minha playlist" })
        // Used only to identify old caches without a source header during migration.
        if (!prefs.contains("cache_source_hash")) editor.putString("cache_source_hash", digest(savedUrl()))
        if (!editor.commit()) throw IOException("Não consegui salvar o endereço neste aparelho.")
    }

    fun state(source: String): CacheState = state(helper.readableDatabase, digest(source))
    private fun state(db: SQLiteDatabase, key: String): CacheState =
        db.query("playlists", arrayOf("etag", "modified", "epg_url", "checked", "updated"), "source_key=?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) CacheState(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) else CacheState()
        }

    @Synchronized fun claimDailyAttempt(source: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = helper.writableDatabase
        val key = digest(source)
        db.beginTransactionNonExclusive()
        try {
            if (!shouldRefreshDaily(state(db, key).checked, now)) return false
            ensureSource(db, key)
            db.update("playlists", ContentValues().apply { put("checked", now) }, "source_key=?", arrayOf(key))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun recordAttempt(source: String, now: Long = System.currentTimeMillis()) {
        val db = helper.writableDatabase
        val key = digest(source)
        db.beginTransactionNonExclusive()
        try {
            ensureSource(db, key)
            db.update("playlists", ContentValues().apply { put("checked", now) }, "source_key=?", arrayOf(key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun readCache(source: String): Playlist {
        if (source.isBlank()) return Playlist(emptyList())
        val db = helper.readableDatabase
        val channels = ArrayList<Channel>()
        db.query("channels", arrayOf("name", "url", "group_name", "image_url", "tvg_id", "user_agent", "referrer", "mime_type",
            "type_hint", "series_id", "series_title", "series_image", "season", "episode"),
            "source_key=?", arrayOf(digest(source)), null, null, "position").use {
            while (it.moveToNext()) channels.add(Channel(it.getString(0), it.getString(1), it.getString(2), it.getString(3),
                it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getString(9),
                it.getString(10), it.getString(11), it.getInt(12), it.getInt(13)))
        }
        if (channels.isNotEmpty()) return Playlist(channels, state(source).epgUrl)
        val legacy = readLegacy(source)
        if (legacy.channels.isNotEmpty()) {
            appendCache(source, Download(legacy, "", ""), markChecked = false)
            prefs.edit().remove("playlist_cache").apply()
        }
        return legacy
    }

    /** Only insert new URLs. Existing rows and their order survive every refresh. */
    @Synchronized fun appendCache(source: String, download: Download, now: Long = System.currentTimeMillis(), markChecked: Boolean = true): Int {
        val db = helper.writableDatabase
        val key = digest(source)
        var added = 0
        db.beginTransactionNonExclusive()
        try {
            ensureSource(db, key)
            var position = db.rawQuery("SELECT COALESCE(MAX(position), -1)+1 FROM channels WHERE source_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
            db.compileStatement("INSERT OR IGNORE INTO channels(source_key,url,position,name,group_name,image_url,tvg_id,user_agent,referrer,mime_type,type_hint,series_id,series_title,series_image,season,episode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                download.playlist.channels.forEach { channel ->
                    statement.clearBindings()
                    statement.bindString(1, key); statement.bindString(2, channel.url); statement.bindLong(3, position)
                    listOf(channel.name, channel.group, channel.imageUrl, channel.tvgId, channel.userAgent, channel.referrer,
                        channel.mimeType, channel.typeHint, channel.seriesId, channel.seriesTitle, channel.seriesImageUrl).forEachIndexed { i, value -> statement.bindString(i + 4, value) }
                    statement.bindLong(15, channel.seasonNumber.toLong()); statement.bindLong(16, channel.episodeNumber.toLong())
                    if (statement.executeInsert() != -1L) { added++; position++ }
                }
            }
            val values = ContentValues().apply {
                put("etag", download.etag); put("modified", download.modified)
                if (download.playlist.epgUrl.isNotBlank()) put("epg_url", download.playlist.epgUrl)
                if (markChecked) put("checked", now)
                if (added > 0) put("updated", now)
            }
            db.update("playlists", values, "source_key=?", arrayOf(key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    private fun readLegacy(source: String): Playlist {
        val key = digest(source)
        val legacyFiles = listOf(File(app.filesDir, "aurora_playlist_cache.jsonl.bak"), File(app.filesDir, "aurora_playlist_cache.jsonl"))
        for (file in legacyFiles) {
            if (!file.isFile) continue
            val result = ArrayList<Channel>()
            val seen = HashSet<String>()
            var epg = ""
            var hasHeader = false
            var wrongSource = false
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        if (o.has("schema")) {
                            hasHeader = true; wrongSource = o.optString("sourceHash") != key; epg = o.optString("epgUrl")
                        } else legacyChannel(o)?.takeIf { seen.add(it.url) }?.let(result::add)
                    }
                }
            }
            // The file header wins over the auxiliary preference that caused false cache misses.
            if (!wrongSource && (hasHeader || prefs.getString("cache_source_hash", key) == key) && result.isNotEmpty()) return Playlist(result, epg)
        }
        val old = prefs.getString("playlist_cache", "").orEmpty()
        if (old.length in 1..8 * 1024 * 1024 && prefs.getString("cache_source_hash", key) == key) {
            runCatching {
                val array = JSONArray(old)
                val channels = (0 until array.length()).mapNotNull { legacyChannel(array.getJSONObject(it)) }.distinctBy { it.url }
                if (channels.isNotEmpty()) return Playlist(channels)
            }
        }
        return Playlist(emptyList())
    }

    private fun legacyChannel(o: JSONObject): Channel? {
        val url = o.optString("url")
        return if (url.isBlank()) null else Channel(o.optString("name", "Canal"), url, o.optString("group", "Canais"),
            o.optString("imageUrl"), o.optString("tvgId"), o.optString("userAgent"), o.optString("referrer"), o.optString("mimeType"))
    }

    suspend fun download(source: String, conditional: Boolean): Download? {
        val coroutine = currentCoroutineContext()
        val validators = state(source)
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 25000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AuroraIPTV/1.2 Android")
            setRequestProperty("Accept-Encoding", "gzip")
            if (conditional) {
                validators.etag.takeIf(String::isNotBlank)?.let { setRequestProperty("If-None-Match", it) }
                validators.modified.takeIf(String::isNotBlank)?.let { setRequestProperty("If-Modified-Since", it) }
            }
        }
        try {
            coroutine.ensureActive()
            if (connection.responseCode == 304) return null
            if (connection.responseCode !in 200..299) throw IOException("O servidor respondeu HTTP ${connection.responseCode}. Confira o endereço da lista.")
            val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
            val playlist = stream.bufferedReader().use { M3uParser.parse(it, connection.url.toString()) { coroutine.ensureActive() } }
            if (playlist.channels.isEmpty()) throw IOException("Não encontrei conteúdos nesta M3U. Confira o endereço da lista.")
            return Download(playlist, connection.getHeaderField("ETag").orEmpty(), connection.getHeaderField("Last-Modified").orEmpty())
        } finally { connection.disconnect() }
    }

    fun close() = helper.close()
    companion object {
        fun validUrl(value: String): Boolean = runCatching { val u = URL(value); (u.protocol == "http" || u.protocol == "https") && u.host.isNotBlank() }.getOrDefault(false)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun ensureSource(db: SQLiteDatabase, key: String) { db.execSQL("INSERT OR IGNORE INTO playlists(source_key) VALUES(?)", arrayOf(key)) }
    }
}

private class CatalogDatabase(context: Context) : SQLiteOpenHelper(context, "aurora_catalog.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playlists(source_key TEXT PRIMARY KEY, etag TEXT NOT NULL DEFAULT '', modified TEXT NOT NULL DEFAULT '', epg_url TEXT NOT NULL DEFAULT '', checked INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE channels(source_key TEXT NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, group_name TEXT NOT NULL, image_url TEXT NOT NULL, tvg_id TEXT NOT NULL, user_agent TEXT NOT NULL, referrer TEXT NOT NULL, mime_type TEXT NOT NULL, type_hint TEXT NOT NULL, series_id TEXT NOT NULL, series_title TEXT NOT NULL, series_image TEXT NOT NULL, season INTEGER NOT NULL, episode INTEGER NOT NULL, PRIMARY KEY(source_key,url), FOREIGN KEY(source_key) REFERENCES playlists(source_key))")
        db.execSQL("CREATE INDEX channel_order ON channels(source_key,position)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future upgrades must migrate rows; never drop the user's catalog.
    }
}
