package br.com.fabriciomatos.iptvplayer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal const val UPDATE_REPOSITORY = "fabriciomatosqa/iptv-player"
internal const val UPDATE_RELEASES_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=100"
internal const val UPDATE_APK_NAME = "aurora-iptv.apk"
internal const val UPDATE_MANIFEST_NAME = "aurora-update.json"
private const val MAX_APK_BYTES = 512L * 1024 * 1024

internal data class AndroidRelease(val tag: String, val version: String, val manifestUrl: String, val apkUrl: String, val size: Long, val digest: String?)
internal data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int, val apkUrl: String, val size: Long, val sha256: String, val notes: String) {
    fun json(): String = JSONObject().put("schemaVersion", 1).put("versionCode", versionCode)
        .put("versionName", versionName).put("minSdk", minSdk).put("apkUrl", apkUrl)
        .put("size", size).put("sha256", sha256).put("notes", notes).toString()
}

internal class UpdateFailure(val text: Int, detail: String? = null) : Exception(detail)
private fun invalidUpdate(): Nothing = throw UpdateFailure(R.string.app_update_invalid)

internal fun updateAssetUrl(url: String, tag: String, name: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.path == "/$UPDATE_REPOSITORY/releases/download/$tag/$name"
}.getOrDefault(false)

internal fun latestAndroidRelease(json: String): AndroidRelease? {
    val releases = JSONArray(json)
    return (0 until releases.length()).mapNotNull { i ->
        val release = releases.getJSONObject(i)
        val tag = release.optString("tag_name")
        val match = Regex("android-v(\\d+\\.\\d+\\.\\d+)").matchEntire(tag) ?: return@mapNotNull null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null
        val assets = release.optJSONArray("assets") ?: return@mapNotNull null
        val uploaded = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("state") == "uploaded" }
        val manifest = uploaded.singleOrNull { it.optString("name") == UPDATE_MANIFEST_NAME } ?: return@mapNotNull null
        val apk = uploaded.singleOrNull { it.optString("name") == UPDATE_APK_NAME } ?: return@mapNotNull null
        val manifestUrl = manifest.optString("browser_download_url")
        val apkUrl = apk.optString("browser_download_url")
        if (!updateAssetUrl(manifestUrl, tag, UPDATE_MANIFEST_NAME) || !updateAssetUrl(apkUrl, tag, UPDATE_APK_NAME)) invalidUpdate()
        val size = apk.getLong("size")
        if (size !in 1..MAX_APK_BYTES) invalidUpdate()
        AndroidRelease(tag, match.groupValues[1], manifestUrl, apkUrl, size,
            apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"))
    }.maxWithOrNull { a, b -> compareAppVersions(a.version, b.version) }
}

internal fun compareAppVersions(a: String, b: String): Int {
    val left = a.split('.').map { it.toLong() }; val right = b.split('.').map { it.toLong() }
    return left.zip(right).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
}

internal fun parseAppUpdate(json: String, release: AndroidRelease? = null): AppUpdate {
    val obj = JSONObject(json)
    if (obj.getInt("schemaVersion") != 1) invalidUpdate()
    val update = AppUpdate(obj.getLong("versionCode"), obj.getString("versionName"), obj.getInt("minSdk"),
        obj.getString("apkUrl"), obj.getLong("size"), obj.getString("sha256").lowercase(), obj.optString("notes").take(8000))
    if (update.versionCode <= 0 || !Regex("\\d+\\.\\d+\\.\\d+").matches(update.versionName) || update.minSdk < 24 ||
        update.size !in 1..MAX_APK_BYTES || !Regex("[0-9a-f]{64}").matches(update.sha256) ||
        !updateAssetUrl(update.apkUrl, "android-v${update.versionName}", UPDATE_APK_NAME)) invalidUpdate()
    if (release != null && (release.version != update.versionName || release.apkUrl != update.apkUrl ||
            release.size != update.size || (release.digest != null && !release.digest.equals(update.sha256, true)))) invalidUpdate()
    return update
}

internal class UpdateResponse(val stream: InputStream, val length: Long, private val disconnect: () -> Unit = {}) : Closeable {
    override fun close() { try { stream.close() } finally { disconnect() } }
}
internal fun interface UpdateTransport { fun open(url: String): UpdateResponse }

/** Only public GitHub metadata/assets. No account token, playlist address or credentials are sent. */
internal class GitHubUpdateTransport : UpdateTransport {
    override fun open(url: String): UpdateResponse {
        var next = url
        repeat(6) {
            val uri = URI(next)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.host !in setOf(
                    "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) invalidUpdate()
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "AURORA-IPTV/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", if (uri.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                if (uri.host == "api.github.com") connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                when (connection.responseCode) {
                    200 -> return UpdateResponse(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: invalidUpdate()
                        next = uri.resolve(location).toString(); connection.disconnect()
                    }
                    403, 429 -> throw UpdateFailure(R.string.app_update_rate_limit)
                    else -> throw UpdateFailure(R.string.app_update_network_error)
                }
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        invalidUpdate()
    }
}

internal class AppUpdateRepository(
    private val context: Context,
    private val transport: UpdateTransport = GitHubUpdateTransport(),
    internal val currentCode: Long = BuildConfig.VERSION_CODE.toLong()
) {
    private val directory get() = File(context.cacheDir, "app-updates").also { it.mkdirs() }
    private val prepared get() = File(directory, "prepared.json")
    internal val apkFile get() = File(directory, UPDATE_APK_NAME)

    private suspend fun readJson(url: String): String {
        coroutineContext.ensureActive()
        return transport.open(url).use { response ->
            val bytes = response.stream.readBytesBounded(2 * 1024 * 1024)
            coroutineContext.ensureActive()
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val release = latestAndroidRelease(readJson(UPDATE_RELEASES_URL)) ?: throw UpdateFailure(R.string.app_update_no_release)
        val update = parseAppUpdate(readJson(release.manifestUrl), release)
        if (update.versionCode <= currentCode) { discardPrepared(); return@withContext null }
        if (update.minSdk > Build.VERSION.SDK_INT) throw UpdateFailure(R.string.app_update_incompatible)
        update
    }

    suspend fun download(update: AppUpdate, progress: suspend (Int) -> Unit, verifying: suspend () -> Unit): File = withContext(Dispatchers.IO) {
        if (update.versionCode <= currentCode) invalidUpdate()
        // PackageManager implementations expect APK archives, even before the verified file is promoted.
        val temporary = File.createTempFile("aurora-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L; var lastPercent = -1
            transport.open(update.apkUrl).use { response ->
                if (response.length > 0 && response.length != update.size) invalidUpdate()
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = response.stream.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > update.size) invalidUpdate()
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        val percent = (received * 100 / update.size).toInt().coerceIn(0, 100)
                        if (lastPercent != percent) { progress(percent); lastPercent = percent }
                    }
                    output.fd.sync()
                }
            }
            if (received != update.size || digest.digest().hex() != update.sha256) throw UpdateFailure(R.string.app_update_integrity_error)
            coroutineContext.ensureActive(); verifying()
            validateArchive(temporary, update)
            coroutineContext.ensureActive()
            discardPrepared()
            if (!temporary.renameTo(apkFile)) throw UpdateFailure(R.string.app_update_storage_error)
            prepared.writeText(update.json())
            apkFile
        } finally { temporary.delete() }
    }

    suspend fun restorePrepared(): AppUpdate? = withContext(Dispatchers.IO) {
        if (!prepared.exists()) return@withContext null
        try {
            val update = parseAppUpdate(prepared.readText())
            validateReady(update); update
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { discardPrepared(); null }
    }

    suspend fun validateReady(update: AppUpdate) = withContext(Dispatchers.IO) {
        if (update.versionCode <= currentCode || !apkFile.isFile || apkFile.length() != update.size) invalidUpdate()
        val digest = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(bytes); if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        if (digest.digest().hex() != update.sha256) throw UpdateFailure(R.string.app_update_integrity_error)
        validateArchive(apkFile, update)
    }

    @Suppress("DEPRECATION")
    internal fun validateArchive(file: File, update: AppUpdate) {
        val flags = PackageManager.GET_SIGNATURES or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: invalidUpdate()
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(archive) != update.versionCode ||
            update.versionCode <= currentCode || archive.versionName != update.versionName ||
            (Build.VERSION.SDK_INT >= 24 && (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT)) invalidUpdate()
        fun certificates(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners ?: info.signatures else info.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
        val expected = certificates(installed)
        val received = certificates(archive)
        if (expected.isEmpty() || received != expected) throw UpdateFailure(R.string.app_update_signature_error,
            "Installed signing certificates: $expected; archive signing certificates: $received")
    }

    fun canInstall() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("AURORA IPTV", uri) }
    }

    private fun discardPrepared() { prepared.delete(); apkFile.delete() }
}

private fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val bytes = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        if (bytes.size() + count > limit) invalidUpdate()
        bytes.write(buffer, 0, count)
    }
    return bytes.toByteArray()
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
