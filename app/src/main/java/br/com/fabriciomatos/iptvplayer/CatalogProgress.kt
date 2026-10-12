package br.com.fabriciomatos.iptvplayer

enum class CatalogStage { PREPARING, DOWNLOADING, IMPORTING, FINALIZING, COMPLETE }
data class CatalogProgress(val stage: CatalogStage, val percent: Int? = null, val bytes: Long = 0, val entries: Int = 0)

/** Byte counts, rather than a timer, drive progress. 100% means the transaction committed. */
fun downloadProgress(bytes: Long, total: Long): CatalogProgress = CatalogProgress(CatalogStage.DOWNLOADING,
    if(total > 0) (bytes.coerceAtMost(total) * 50 / total).toInt() else null, bytes)
fun importProgress(bytes: Long, total: Long, entries: Int): CatalogProgress = CatalogProgress(CatalogStage.IMPORTING,
    if(total > 0) (50 + bytes.coerceAtMost(total) * 48 / total).toInt() else null, bytes, entries)

internal class CountingInputStream(private val input: java.io.InputStream) : java.io.InputStream() {
    var bytes = 0L; private set
    override fun read(): Int = input.read().also { if(it >= 0)bytes++ }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer,offset,length).also { if(it > 0)bytes+=it }
    override fun close() = input.close()
}
