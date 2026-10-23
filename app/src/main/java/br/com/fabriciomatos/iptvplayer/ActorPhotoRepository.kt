package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Public person metadata only. Do not transmit IPTV addresses, playlists or logins.
 * SQLite stores a reusable person -> URL mapping; Coil stores the actual image bytes
 * in its bounded on-disk cache. Both survive restarts and normal app updates.
 */
internal class ActorPhotoRepository(
    private val repository: PlaylistRepository,
    private val readJson: (String) -> JSONObject = ::readSynopsisJson
) {
    private val lock = Mutex()

    suspend fun image(actor: CastMember): String = withContext(Dispatchers.IO) {
        val name = actor.name.trim()
        val identity = normalized(name)
        if (identity.length !in 5..90 || name.any { it in "<>/?&#" }) return@withContext ""
        val key = "person-photo:v1:$identity"
        val provided = actor.imageUrl.takeIf(::trustedActorImageUrl).orEmpty()

        // A direct cast portrait is more reliable than guessing by name.
        // Cache it by person for reuse in other movies/series and future sessions.
        lock.withLock {
            val cached = repository.cachedArtwork(key)?.let {
                runCatching { JSONObject(it).optString("url") }.getOrNull()
            }.orEmpty()
            if (provided.isNotBlank()) {
                if (cached.isBlank()) {
                    repository.saveArtwork(key,JSONObject().put("url",provided).toString(),180L*86400000)
                }
                return@withLock provided
            }
            if (cached.isNotBlank()) return@withLock cached
            if (repository.cachedArtwork(key)!=null) return@withLock ""

            val result = try { wikipediaPhoto(name,readJson) }
                catch(e: CancellationException) {throw e}
                catch(_:Exception) { "" }
            repository.saveArtwork(key,JSONObject().put("url",result).toString(),
                if(result.isNotBlank())180L*86400000 else 24L*3600000)
            result
        }
    }
}

internal fun trustedActorImageUrl(value: String): Boolean {
    val u=runCatching {java.net.URI(value)}.getOrNull() ?: return false
    return u.scheme == "https" && !u.host.isNullOrBlank() &&
        u.userInfo==null && (u.port==-1 || u.port==443) &&
        u.host !in setOf("localhost","127.0.0.1","::1") &&
        !u.host.endsWith(".local")
}

/** Exact identity and occupation checks avoid wrong portraits for namesakes. */
internal fun wikipediaPhoto(name: String, readJson:(String)->JSONObject): String {
    val encoded=URLEncoder.encode(name.replace(' ','_'),"UTF-8")
    for (language in listOf("pt","en")) {
        val json=try { readJson("https://$language.wikipedia.org/api/rest_v1/page/summary/$encoded") }
            catch(e: CancellationException) {throw e}
            catch(_:Exception) {continue}
        if (json.optString("type")=="disambiguation") continue
        if(normalized(json.optString("title")) != normalized(name))continue
        val occupation = normalized(json.optString("description") + " " +
            json.optString("extract").take(180))
        if(listOf("ator","atriz","actor","actress","performer","comedian")
            .none(occupation::contains)) continue
        val image=json.optJSONObject("thumbnail")?.optString("source").orEmpty()
        if(image.startsWith("https://upload.wikimedia.org/"))return image
    }
    return ""
}
