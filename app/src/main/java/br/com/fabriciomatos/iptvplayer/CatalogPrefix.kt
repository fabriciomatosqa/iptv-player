package br.com.fabriciomatos.iptvplayer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Only this small receipt is retained; the previous M3U is not copied or kept in memory. */
internal data class CatalogPrefix(val hash: String,val resolved: String,val categories: String,
    val bytes: Long,val entries: Int,val total: Int,val nextPosition: Long,val boundary: Boolean) {
    fun json()=JSONObject().put("parser",1).put("hash",hash).put("resolved",resolved).put("categories",categories)
        .put("bytes",bytes).put("entries",entries).put("total",total).put("next",nextPosition).put("boundary",boundary).toString()
    companion object {
        fun read(value: String?): CatalogPrefix? {
            if(value==null)return null
            return runCatching {
                val json=JSONObject(value)
                if(json.getInt("parser")!=1)null else
                    CatalogPrefix(json.getString("hash"),json.getString("resolved"),json.getString("categories"),
                        json.getLong("bytes"),json.getInt("entries"),json.getInt("total"),json.getLong("next"),json.getBoolean("boundary"))
                        .takeIf {it.bytes>0 && it.entries>0 && it.total>0 && it.nextPosition>=0}
            }.getOrNull()
        }
    }
}
internal data class CatalogInput(val resolved: String,val categories: String,val bytes: Long,val boundary: Boolean)
internal class CatalogPrefixChanged: java.io.IOException("O catálogo salvo mudou durante a comparação.")

internal fun catalogCategoryStamp(categories: Map<String,MediaKind>): String {
    val value=JSONArray().apply {categories.toSortedMap().forEach {(name,kind)->put(JSONArray().put(name).put(kind.ordinal))}}.toString()
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}
internal fun catalogEndsAtLineBoundary(file: File): Boolean = if(file.length()==0L)false else
    RandomAccessFile(file,"r").use {it.seek(it.length()-1);it.read().let {byte->byte==10 || byte==13}}

/** EXTINF resets parser state. Otherwise fall back, including EXTGRP/options inherited at EOF. */
internal fun catalogTailStartsEntry(file: File,offset: Long): Boolean = file.inputStream().use {stream ->
    stream.channel.position(offset)
    val buffer=ByteArray(8192)
    val count=stream.read(buffer)
    count>0 && String(buffer,0,count,Charsets.UTF_8).trimStart().removePrefix("\uFEFF").startsWith("#EXTINF",true)
}
internal fun digestCatalogPrefix(input: InputStream,length: Long,digest: MessageDigest): Boolean {
    val buffer=ByteArray(65536);var remaining=length
    while(remaining>0) {
        val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
        if(count<0)return false
        if(count==0)continue
        digest.update(buffer,0,count);remaining-=count
    }
    return true
}
