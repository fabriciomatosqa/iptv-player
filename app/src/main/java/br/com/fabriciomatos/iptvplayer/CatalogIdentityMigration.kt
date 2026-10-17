package br.com.fabriciomatos.iptvplayer

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase

/** Pin only bookmarked URLs; storing every historic CDN URL would grow the cache again. */
internal fun pinSavedCatalogUrls(db: SQLiteDatabase, prefs: SharedPreferences, key: String) {
    val saved = prefs.getStringSet("favorite_urls", emptySet()).orEmpty() +
        prefs.all.filterKeys { it.startsWith("last_") }.values.filterIsInstance<String>()
    db.compileStatement("INSERT OR IGNORE INTO channel_url_aliases(source_key,old_url,content_key) SELECT source_key,url,content_key FROM channels WHERE source_key=? AND url=?").use { statement ->
        saved.forEach { url -> statement.bindString(1,key); statement.bindString(2,url); statement.executeInsert() }
    }
    db.execSQL("INSERT OR IGNORE INTO channel_url_aliases(source_key,old_url,content_key) SELECT source_key,url,content_key FROM channels WHERE source_key=? AND (url IN(SELECT url FROM favorites) OR url IN(SELECT url FROM playback_progress WHERE source_key=?))", arrayOf(key,key))
}

internal fun resolveCatalogUrl(db: SQLiteDatabase, key: String, url: String): String = db.rawQuery(
    "SELECT url FROM channels WHERE source_key=? AND content_key=?",arrayOf(key,catalogContentKey(url))
).use { if(it.moveToFirst())it.getString(0) else url }

/** Keep the latest saved position when several old host URLs have progress. */
internal fun repairCatalogProgress(db: SQLiteDatabase, key: String) {
    db.execSQL("""INSERT OR REPLACE INTO playback_progress(source_key,url,position_ms,duration_ms,completed)
        SELECT p.source_key,c.url,p.position_ms,p.duration_ms,p.completed FROM playback_progress p
        JOIN channel_url_aliases a ON a.source_key=p.source_key AND a.old_url=p.url
        JOIN channels c ON c.source_key=a.source_key AND c.content_key=a.content_key
        WHERE p.source_key=? AND p.url<>c.url AND NOT EXISTS(
            SELECT 1 FROM playback_progress newer WHERE newer.source_key=p.source_key AND newer.url=c.url AND newer.rowid>p.rowid)
        ORDER BY p.rowid""",arrayOf(key))
    db.execSQL("DELETE FROM playback_progress WHERE source_key=? AND EXISTS(SELECT 1 FROM channel_url_aliases a JOIN channels c ON c.source_key=a.source_key AND c.content_key=a.content_key WHERE a.source_key=playback_progress.source_key AND a.old_url=playback_progress.url AND c.url<>playback_progress.url)",arrayOf(key))
}

/** SQLiteOpenHelper wraps this migration in a transaction, including URL aliases and progress. */
internal fun upgradeCatalogIdentity(db: SQLiteDatabase, prefs: SharedPreferences): Boolean {
    db.execSQL("ALTER TABLE channels ADD COLUMN content_key TEXT NOT NULL DEFAULT ''")
    db.execSQL("CREATE TABLE channel_url_aliases(source_key TEXT NOT NULL,old_url TEXT NOT NULL,content_key TEXT NOT NULL,PRIMARY KEY(source_key,old_url))")
    db.execSQL("CREATE INDEX catalog_alias_url ON channel_url_aliases(old_url)")
    db.execSQL("UPDATE channels SET content_key="+catalogContentKeySql("url"))
    db.execSQL("CREATE INDEX catalog_identity_migration ON channels(source_key,content_key,seen_generation DESC,position DESC)")
    val sources=db.rawQuery("SELECT source_key FROM playlists",null).use { cursor -> buildList {while(cursor.moveToNext())add(cursor.getString(0))} }
    sources.forEach {pinSavedCatalogUrls(db,prefs,it)}
    db.execSQL("""CREATE TEMP TABLE catalog_keepers AS
        SELECT g.source_key,g.content_key,g.position,g.first_added,
            (SELECT c.rowid FROM channels c WHERE c.source_key=g.source_key AND c.content_key=g.content_key
                ORDER BY c.seen_generation DESC,c.position DESC,c.rowid DESC LIMIT 1) AS keep_row
        FROM (SELECT source_key,content_key,MIN(position) AS position,MIN(first_added) AS first_added
            FROM channels GROUP BY source_key,content_key HAVING COUNT(*)>1) g""")
    db.execSQL("CREATE UNIQUE INDEX catalog_keepers_key ON catalog_keepers(source_key,content_key)")
    db.execSQL("CREATE UNIQUE INDEX catalog_keepers_row ON catalog_keepers(keep_row)")
    val changed=db.rawQuery("SELECT DISTINCT source_key FROM catalog_keepers",null).use { cursor -> buildList {while(cursor.moveToNext())add(cursor.getString(0))} }
    db.execSQL("UPDATE channels SET position=(SELECT k.position FROM catalog_keepers k WHERE k.keep_row=channels.rowid),first_added=(SELECT k.first_added FROM catalog_keepers k WHERE k.keep_row=channels.rowid) WHERE rowid IN(SELECT keep_row FROM catalog_keepers)")
    db.execSQL("DELETE FROM channels WHERE EXISTS(SELECT 1 FROM catalog_keepers k WHERE k.source_key=channels.source_key AND k.content_key=channels.content_key AND k.keep_row<>channels.rowid)")
    db.execSQL("DROP TABLE catalog_keepers")
    db.execSQL("DROP INDEX catalog_identity_migration")
    db.execSQL("CREATE UNIQUE INDEX channel_content_identity ON channels(source_key,content_key)")
    sources.forEach {repairCatalogProgress(db,it)}
    // Aggregate lazily on first opening each repaired playlist, using the existing recovery path.
    changed.forEach {db.execSQL("UPDATE playlists SET indexed=0 WHERE source_key=?",arrayOf(it))}
    return changed.isNotEmpty()
}
