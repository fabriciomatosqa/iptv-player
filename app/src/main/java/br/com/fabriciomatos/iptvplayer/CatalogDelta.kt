package br.com.fabriciomatos.iptvplayer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

/** Comparison is bounded to one page; unchanged contents never reach a write statement. */
internal data class CatalogImportRow(
    val channel: Channel, val key: String, val seriesKey: String, val norm: String,
    val title: String, val season: Int, val episode: Int, val episodeTitle: String,
    val search: String, val generation: Int, val needsIndexing: Boolean = false
) {
    fun differsFrom(old: CatalogImportRow): Boolean {
        val c=channel;val p=old.channel
        return old.needsIndexing || c.kind!=p.kind || c.adult!=p.adult || seriesKey!=old.seriesKey || norm!=old.norm ||
            title!=old.title || c.seriesImageUrl!=p.seriesImageUrl || season!=old.season ||
            episode!=old.episode || episodeTitle!=old.episodeTitle || c.typeHint!=p.typeHint ||
            c.url!=p.url || c.userAgent!=p.userAgent || c.referrer!=p.referrer || c.mimeType!=p.mimeType ||
            (c.tvgId.isNotBlank() && c.tvgId!=p.tvgId) || (c.imageUrl.isNotBlank() && c.imageUrl!=p.imageUrl) || (old.search.isBlank() && search.isNotBlank())
    }
    fun changesDirectory(old: CatalogImportRow): Boolean = old.needsIndexing || channel.kind!=old.channel.kind ||
        ((channel.kind==MediaKind.SERIES || old.channel.kind==MediaKind.SERIES) &&
            (seriesKey!=old.seriesKey || norm!=old.norm || title!=old.title || season!=old.season ||
                channel.seriesImageUrl!=old.channel.seriesImageUrl || channel.imageUrl!=old.channel.imageUrl ||
                channel.adult!=old.channel.adult))

    /** Names, groups and first-added order already saved on the device remain authoritative. */
    fun preserving(old: CatalogImportRow)=copy(channel=channel.copy(name=old.channel.name,
        group=old.channel.group,tvgId=channel.tvgId.ifBlank {old.channel.tvgId},seriesId=old.channel.seriesId,
        imageUrl=channel.imageUrl.ifBlank {old.channel.imageUrl}),search=old.search.ifBlank {search})

    companion object {
        fun prepare(c: Channel, generation: Int): CatalogImportRow {
            val identity=if(c.kind==MediaKind.SERIES)seriesIdentity(c) else null
            return CatalogImportRow(c,catalogContentKey(c.url),identity?.id.orEmpty(),
                identity?.title?.let(::normalized).orEmpty(),identity?.title ?: c.seriesTitle,
                identity?.episode?.season ?: c.seasonNumber,identity?.episode?.number ?: c.episodeNumber,
                identity?.episode?.title.orEmpty(),c.searchText,generation)
        }
        const val COLUMNS="content_key,name,url,group_name,image_url,tvg_id,user_agent,referrer,mime_type,type_hint,series_id,series_title,series_image,season,episode,kind,adult,series_key,series_norm,episode_title,search_text,seen_generation"
        fun read(row: Cursor): CatalogImportRow {
            val c=Channel(row.getString(1),row.getString(2),row.getString(3),row.getString(4),
                row.getString(5),row.getString(6),row.getString(7),row.getString(8),row.getString(9),
                row.getString(10),row.getString(11),row.getString(12),row.getInt(13),row.getInt(14),
                MediaKind.entries.getOrElse(row.getInt(15)){MediaKind.OTHER},row.getInt(16)>0)
            return CatalogImportRow(c,row.getString(0),row.getString(17),row.getString(18),row.getString(11),
                row.getInt(13),row.getInt(14),row.getString(19),row.getString(20),row.getInt(21),row.getInt(15) !in 0..3 || row.getInt(16)<0)
        }
    }
}

internal class CatalogBatchWriter(
    private val db: SQLiteDatabase, private val source: String, private var position: Long,
    private val firstAdded: Long, private val generation: Int,
    private val changed: (CatalogImportRow?,CatalogImportRow)->Unit
): Closeable {
    private val batch=ArrayList<CatalogImportRow>(BATCH_SIZE)
    private val insert=db.compileStatement("INSERT INTO channels(source_key,url,position,name,group_name,image_url,tvg_id,user_agent,referrer,mime_type,type_hint,series_id,series_title,series_image,season,episode,kind,search_text,series_key,series_norm,episode_title,seen_generation,adult,first_added,content_key) VALUES("+List(25){"?"}.joinToString(",")+")")
    private val update=db.compileStatement("UPDATE channels SET kind=?,search_text=?,series_key=?,series_norm=?,series_title=?,series_image=?,season=?,episode=?,episode_title=?,type_hint=?,seen_generation=?,adult=?,url=?,image_url=?,user_agent=?,referrer=?,mime_type=?,tvg_id=? WHERE source_key=? AND content_key=?")
    var added=0;private set
    var updated=0;private set
    fun accept(channel: Channel) {
        batch.add(CatalogImportRow.prepare(channel,generation))
        if(batch.size==BATCH_SIZE)flush()
    }
    fun flush() {
        if(batch.isEmpty())return
        val keys=batch.map {it.key}.distinct()
        val saved=db.rawQuery("SELECT "+CatalogImportRow.COLUMNS+" FROM channels WHERE source_key=? AND content_key IN ("+
            keys.joinToString(","){"?"}+")",arrayOf(source,*keys.toTypedArray())).use {cursor ->
            buildMap {while(cursor.moveToNext())CatalogImportRow.read(cursor).let {put(it.key,it)}}.toMutableMap()
        }
        batch.forEach { incoming ->
            val old=saved[incoming.key]
            if(old==null) {
                val c=incoming.channel
                insert.clearBindings();insert.bindString(1,source);insert.bindString(2,c.url);insert.bindLong(3,position)
                listOf(c.name,c.group,c.imageUrl,c.tvgId,c.userAgent,c.referrer,c.mimeType,c.typeHint,c.seriesId,incoming.title,c.seriesImageUrl)
                    .forEachIndexed {i,v->insert.bindString(i+4,v)}
                insert.bindLong(15,incoming.season.toLong());insert.bindLong(16,incoming.episode.toLong());insert.bindLong(17,c.kind.ordinal.toLong())
                insert.bindString(18,incoming.search);insert.bindString(19,incoming.seriesKey);insert.bindString(20,incoming.norm)
                insert.bindString(21,incoming.episodeTitle);insert.bindLong(22,generation.toLong());insert.bindLong(23,if(c.adult)1L else 0L)
                insert.bindLong(24,firstAdded);insert.bindString(25,incoming.key)
                insert.executeInsert();added++;position++;saved[incoming.key]=incoming;changed(null,incoming)
            } else if(old.generation!=generation && incoming.differsFrom(old)) {
                val fresh=incoming.preserving(old);val c=fresh.channel
                update.clearBindings();update.bindLong(1,c.kind.ordinal.toLong());update.bindString(2,fresh.search)
                update.bindString(3,fresh.seriesKey);update.bindString(4,fresh.norm);update.bindString(5,fresh.title);update.bindString(6,c.seriesImageUrl)
                update.bindLong(7,fresh.season.toLong());update.bindLong(8,fresh.episode.toLong());update.bindString(9,fresh.episodeTitle)
                update.bindString(10,c.typeHint);update.bindLong(11,generation.toLong());update.bindLong(12,if(c.adult)1L else 0L)
                update.bindString(13,c.url);update.bindString(14,c.imageUrl);update.bindString(15,c.userAgent)
                update.bindString(16,c.referrer);update.bindString(17,c.mimeType);update.bindString(18,c.tvgId);update.bindString(19,source);update.bindString(20,fresh.key)
                updated+=update.executeUpdateDelete();saved[fresh.key]=fresh;changed(old,fresh)
            }
        }
        batch.clear()
    }
    override fun close() {insert.close();update.close()}
    companion object { const val BATCH_SIZE=400 }
}

/** Rebuild only directories affected by actual content changes, not every series in a playlist. */
internal class CatalogIndexDelta {
    private val series=linkedSetOf<String>()
    private val groups=linkedSetOf<Pair<Int,String>>()
    private val counts=IntArray(4)
    var requiresFullRebuild=false;private set
    private var added=0
    fun record(old: CatalogImportRow?, fresh: CatalogImportRow) {
        if(old==null) {added++;counts[fresh.channel.kind.ordinal]++}
        else if(old.channel.kind!=fresh.channel.kind) {counts[old.channel.kind.ordinal]--;counts[fresh.channel.kind.ordinal]++}
        if(requiresFullRebuild || old!=null && !fresh.changesDirectory(old))return
        fun touch(row: CatalogImportRow) {
            if(row.channel.kind==MediaKind.SERIES && row.seriesKey.isNotBlank())series.add(row.seriesKey)
            else groups.add(row.channel.kind.ordinal to row.channel.group)
        }
        old?.let(::touch);touch(fresh)
        if(series.size+groups.size>256) {requiresFullRebuild=true;series.clear();groups.clear()}
    }
    fun apply(db: SQLiteDatabase, source: String) {
        var seriesCount=0
        series.forEach { id ->
            fun collectGroups()=db.rawQuery("SELECT group_name FROM series_groups WHERE source_key=? AND series_key=?",arrayOf(source,id)).use {
                while(it.moveToNext())groups.add(MediaKind.SERIES.ordinal to it.getString(0))
            }
            fun exists()=db.rawQuery("SELECT 1 FROM series_directory WHERE source_key=? AND series_key=?",arrayOf(source,id)).use {it.moveToFirst()}
            val before=exists();collectGroups()
            db.delete("series_directory","source_key=? AND series_key=?",arrayOf(source,id))
            db.delete("series_groups","source_key=? AND series_key=?",arrayOf(source,id))
            db.execSQL("INSERT INTO series_directory(source_key,series_key,name_norm,title,image,episode_count,seasons,search_text,adult) SELECT source_key,series_key,MIN(series_norm),MIN(series_title),COALESCE(NULLIF(MAX(series_image),''),MIN(image_url)),COUNT(*),GROUP_CONCAT(DISTINCT season),MIN(series_norm)||' '||GROUP_CONCAT(DISTINCT group_name),MAX(adult) FROM channels WHERE source_key=? AND series_key=? AND kind=2 GROUP BY series_key",arrayOf(source,id))
            db.execSQL("INSERT INTO series_groups(source_key,series_key,group_name) SELECT DISTINCT source_key,series_key,group_name FROM channels WHERE source_key=? AND series_key=? AND kind=2",arrayOf(source,id))
            collectGroups();seriesCount+=(if(exists())1 else 0)-(if(before)1 else 0)
        }
        groups.forEach { (kind,group) ->
            db.delete("catalog_groups","source_key=? AND kind=? AND group_name=?",arrayOf(source,kind.toString(),group))
            if(kind==MediaKind.SERIES.ordinal)
                db.execSQL("INSERT INTO catalog_groups(source_key,kind,group_name,amount) SELECT source_key,2,group_name,COUNT(*) FROM series_groups WHERE source_key=? AND group_name=? GROUP BY group_name",arrayOf(source,group))
            else db.execSQL("INSERT INTO catalog_groups(source_key,kind,group_name,amount) SELECT source_key,kind,group_name,COUNT(*) FROM channels WHERE source_key=? AND kind=? AND group_name=? GROUP BY kind,group_name",arrayOf(source,kind,group))
        }
        if(added!=0 || counts.any {it!=0} || seriesCount!=0)
            db.execSQL("UPDATE playlists SET total_count=total_count+?,live_count=live_count+?,movie_count=movie_count+?,episode_count=episode_count+?,series_count=series_count+?,indexed=1 WHERE source_key=?",arrayOf(added,counts[0],counts[1],counts[2],seriesCount,source))
    }
}
