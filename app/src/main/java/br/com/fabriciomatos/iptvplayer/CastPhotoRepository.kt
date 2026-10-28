package br.com.fabriciomatos.iptvplayer

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/** A shared actor portrait, never a character image. sourceUrl supplies public attribution. */
data class CastPhoto(val imageUrl: String = "", val sourceUrl: String = "")

internal enum class CastPhotoOrigin { PROVIDER, PUBLIC, AMBIGUOUS, MISSING }
internal data class CastPhotoCacheEntry(
    val photo: CastPhoto,
    val origin: CastPhotoOrigin,
    val expiresAt: Long
)

internal interface CastPhotoCache {
    fun get(key: String, now: Long): CastPhotoCacheEntry?
    fun put(key: String, entry: CastPhotoCacheEntry, now: Long)
}

/**
 * Only portrait metadata lives here. Binary images are held in Coil's
 * bounded cache. This disposable index is shared across playlists and works so a
 * photograph discovered in a film can be reused in a series without another search.
 */
internal class FileCastPhotoCache(
    private val file: File,
    private val maxEntries: Int = 1_000
) : CastPhotoCache {
    private val values = LinkedHashMap<String, CastPhotoCacheEntry>()
    private var loaded = false

    @Synchronized override fun get(key: String, now: Long): CastPhotoCacheEntry? {
        load()
        val entry = values.remove(key) ?: return null
        if (entry.expiresAt <= now) return null
        values[key] = entry // LRU order; writing is deferred until the next change.
        return entry
    }

    @Synchronized override fun put(key: String, entry: CastPhotoCacheEntry, now: Long) {
        load()
        values.entries.removeAll { it.value.expiresAt <= now }
        values.remove(key)
        values[key] = entry
        while (values.size > maxEntries.coerceAtLeast(1)) values.remove(values.keys.first())
        val rows = JSONArray()
        values.forEach { (name, value) ->
            rows.put(JSONObject().put("name", name).put("image", value.photo.imageUrl)
                .put("source", value.photo.sourceUrl).put("origin", value.origin.name)
                .put("expires", value.expiresAt))
        }
        // AtomicFile keeps a process death from leaving a partially written index.
        // Failure to cache a photo never blocks the catalogue or playback.
        runCatching {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(rows.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val atomic = AtomicFile(file)
            val json = atomic.openRead().use { input ->
                // Even a damaged cache must not allocate an unbounded document.
                val bytes = input.readBytesLimited(4 * 1024 * 1024)
                JSONArray(bytes.toString(Charsets.UTF_8))
            }
            for (i in maxOf(0, json.length() - maxEntries.coerceAtLeast(1)) until json.length()) {
                val row = json.optJSONObject(i) ?: continue
                val key = row.optString("name").takeIf { it.length in 1..100 } ?: continue
                val image = row.optString("image").takeIf { it.length <= 2_048 } ?: continue
                val source = row.optString("source").takeIf { it.length <= 512 } ?: continue
                val origin = runCatching { CastPhotoOrigin.valueOf(row.optString("origin")) }.getOrNull() ?: continue
                if (image.isNotBlank() && !isCastImageUrl(image)) continue
                values[key] = CastPhotoCacheEntry(CastPhoto(image, source), origin, row.optLong("expires"))
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= maximum)
        output.write(buffer, 0, count)
    }
}

/**
 * On-demand lookup for visible cast only. The sole outbound query is an actor's
 * name, never an IPTV source, stream address or account identifier. Exact and
 * unique TVmaze people matches avoid assigning a look-alike or a namesake.
 */
class CastPhotoRepository internal constructor(
    private val cache: CastPhotoCache,
    private val request: (String) -> JSONObject = ::readSynopsisJson,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    constructor(context: Context) : this(FileCastPhotoCache(File(context.applicationContext.cacheDir, "cast-photos-v1.json")))

    private val lock = Mutex()
    private var lastRequestAt = 0L
    private val unavailableImages = LinkedHashSet<String>()

    /** Save provider portraits even when the actor's card has not scrolled into view. */
    suspend fun remember(cast: List<CastMember>) = withContext(Dispatchers.IO) {
        lock.withLock { cast.take(100).forEach(::rememberProviderPhoto) }
    }

    suspend fun resolve(member: CastMember, failedImageUrl: String? = null): CastPhoto = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!failedImageUrl.isNullOrBlank()) {
                unavailableImages.add(failedImageUrl)
                while (unavailableImages.size > 100) unavailableImages.remove(unavailableImages.first())
            }
            val direct = member.imageUrl.takeIf { isCastImageUrl(it) && it !in unavailableImages }
            if (direct != null) {
                rememberProviderPhoto(member)
                return@withLock CastPhoto(direct)
            }
            if (!searchableCastName(member.name)) return@withLock CastPhoto()
            val key = normalized(member.name)
            cache.get(key, now())?.let { cached ->
                if (cached.photo.imageUrl !in unavailableImages) return@withLock cached.photo
                if (cached.origin == CastPhotoOrigin.PUBLIC) {
                    cache.put(key, CastPhotoCacheEntry(CastPhoto(), CastPhotoOrigin.MISSING,
                        now() + 15L * 60_000), now())
                    return@withLock CastPhoto()
                }
            }
            // Serialize searches and stay below TVmaze's documented request limit.
            val wait = lastRequestAt + 650L - now()
            if (lastRequestAt > 0 && wait > 0) pause(wait)
            lastRequestAt = now()
            val photo: CastPhoto
            val lifetime: Long
            try {
                val response = request("https://api.tvmaze.com/search/people?q=" +
                    URLEncoder.encode(member.name.trim(), "UTF-8"))
                coroutineContext.ensureActive()
                photo = selectPublicCastPhoto(member.name, response).takeIf {
                    it.imageUrl !in unavailableImages
                } ?: CastPhoto()
                lifetime = if (photo.imageUrl.isBlank()) 6L * 3_600_000 else 30L * 86_400_000
            } catch (failure: CancellationException) { throw failure }
              catch (_: Exception) {
                // Short negative caching backs off on timeout or rate limiting.
                cache.put(key, CastPhotoCacheEntry(CastPhoto(), CastPhotoOrigin.MISSING,
                    now() + 15L * 60_000), now())
                return@withLock CastPhoto()
            }
            cache.put(key, CastPhotoCacheEntry(photo,
                if (photo.imageUrl.isBlank()) CastPhotoOrigin.MISSING else CastPhotoOrigin.PUBLIC,
                now() + lifetime), now())
            photo
        }
    }

    private fun rememberProviderPhoto(member: CastMember) {
        if (!searchableCastName(member.name) || !isCastImageUrl(member.imageUrl) ||
            member.imageUrl in unavailableImages) return
        val key = normalized(member.name)
        val previous = cache.get(key, now())
        val ambiguous = previous?.origin == CastPhotoOrigin.AMBIGUOUS ||
            (previous?.origin == CastPhotoOrigin.PROVIDER && previous.photo.imageUrl != member.imageUrl)
        val entry = if (ambiguous) CastPhotoCacheEntry(CastPhoto(), CastPhotoOrigin.AMBIGUOUS,
            now() + 30L * 86_400_000)
        else CastPhotoCacheEntry(CastPhoto(member.imageUrl), CastPhotoOrigin.PROVIDER,
            now() + 30L * 86_400_000)
        if (previous?.photo != entry.photo || previous?.origin != entry.origin)
            cache.put(key, entry, now())
    }
}

internal fun searchableCastName(name: String): Boolean = name.length in 3..100 &&
    name.count(Char::isLetter) >= 2 && name.all {
        it.isLetterOrDigit() || it == ' ' || it in "-'’."
    }

private fun isCastImageUrl(value: String) = value.length in 10..2_048 &&
    (value.startsWith("https://") || value.startsWith("http://")) && value.none(Char::isISOControl)

internal fun selectPublicCastPhoto(name: String, json: JSONObject): CastPhoto {
    val items = json.optJSONArray("items") ?: return CastPhoto()
    val people = (0 until minOf(items.length(), 100)).mapNotNull { index ->
        items.optJSONObject(index)?.optJSONObject("person")
    }.filter { normalized(it.optString("name")) == normalized(name) }
        .distinctBy { it.optLong("id", -1) }
    if (people.size != 1) return CastPhoto()
    val person = people.single()
    if (person.optLong("id", -1) <= 0) return CastPhoto()
    val image = person.optJSONObject("image")?.let {
        it.optString("medium").ifBlank { it.optString("original") }
    }.orEmpty().takeIf { isCastImageUrl(it) && it.startsWith("https://static.tvmaze.com/uploads/images/") }
        ?: return CastPhoto()
    val source = person.optString("url").takeIf {
        it.length <= 512 && it.startsWith("https://www.tvmaze.com/people/") && it.none(Char::isISOControl)
    } ?: "https://www.tvmaze.com/people/${person.optLong("id") }"
    return CastPhoto(image, source)
}
