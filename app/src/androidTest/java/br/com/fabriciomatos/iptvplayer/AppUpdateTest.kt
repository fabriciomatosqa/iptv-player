package br.com.fabriciomatos.iptvplayer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Hash large APK fixtures without allocating the entire APK in the emulator's heap. */
private fun File.updateFixtureSha256(): String {
    val digest=MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer)
            if(count<0)break
            digest.update(buffer,0,count)
        }
    }
    return digest.digest().hex()
}

private fun releaseEntry(version: String, size: Long, draft: Boolean = false, prerelease: Boolean = false) = JSONObject()
    .put("tag_name", "android-v$version").put("draft", draft).put("prerelease", prerelease)
    .put("assets", JSONArray().also { assets -> listOf(UPDATE_MANIFEST_NAME, UPDATE_APK_NAME).forEach { name ->
        assets.put(JSONObject().put("name", name).put("state", "uploaded").put("size", size)
            .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/android-v$version/$name"))
    } })

private class UpdateFixture(val context: Context) {
    val apk = File(context.applicationInfo.sourceDir)
    val update = AppUpdate(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME, 24,
        "https://github.com/$UPDATE_REPOSITORY/releases/download/android-v${BuildConfig.VERSION_NAME}/$UPDATE_APK_NAME",
        apk.length(), apk.updateFixtureSha256(), "Atualização de teste")
    var advertised = update
    var payload = apk
    var releases = JSONArray().put(releaseEntry(update.versionName, update.size)).toString()
    var manifest: String? = null
    var failNetwork = false
    val requested = mutableListOf<String>()
    val transport = UpdateTransport { url ->
        requested += url
        if (failNetwork) throw java.io.IOException("offline")
        when (url) {
            UPDATE_RELEASES_URL -> response(releases)
            update.apkUrl -> UpdateResponse(payload.inputStream(), payload.length())
            else -> response(manifest ?: advertised.json())
        }
    }
    val repository get() = AppUpdateRepository(context, transport, update.versionCode - 1)
    private fun response(value: String) = value.toByteArray().let { UpdateResponse(ByteArrayInputStream(it), it.size.toLong()) }
}

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun reset() { File(context.cacheDir, "app-updates").deleteRecursively() }

    @Test fun choosesNewestStableAndroidReleaseAndIgnoresDesktopDraftAndPreview() {
        val json = JSONArray().put(releaseEntry("1.9.0", 100)).put(releaseEntry("1.10.0", 100))
            .put(releaseEntry("2.0.0", 100, draft = true)).put(releaseEntry("3.0.0", 100, prerelease = true))
            .put(JSONObject().put("tag_name", "desktop-v9.0.0"))
        assertEquals("1.10.0", latestAndroidRelease(json.toString())?.version)
        assertFalse(updateAssetUrl("http://github.com/$UPDATE_REPOSITORY/releases/download/android-v1.0.0/$UPDATE_APK_NAME", "android-v1.0.0", UPDATE_APK_NAME))
        assertFalse(updateAssetUrl("https://github.com.attacker.test/$UPDATE_REPOSITORY/releases/download/android-v1.0.0/$UPDATE_APK_NAME", "android-v1.0.0", UPDATE_APK_NAME))
        assertFalse(updateAssetUrl("https://github.com/other/repo/releases/download/android-v1.0.0/$UPDATE_APK_NAME", "android-v1.0.0", UPDATE_APK_NAME))
    }

    @Test fun currentOlderNewerIncompatibleAndNoPublishedReleaseAreDistinct() = runBlocking {
        val fixture = UpdateFixture(context)
        assertEquals(fixture.update, fixture.repository.check())
        assertNull(AppUpdateRepository(context, fixture.transport, fixture.update.versionCode).check())
        assertNull(AppUpdateRepository(context, fixture.transport, fixture.update.versionCode + 1).check())
        fixture.advertised = fixture.update.copy(minSdk = Build.VERSION.SDK_INT + 1)
        failure(R.string.app_update_incompatible) { fixture.repository.check() }
        fixture.releases = "[]"
        failure(R.string.app_update_no_release) { fixture.repository.check() }
        fixture.failNetwork = true
        try { fixture.repository.check(); fail("Expected offline error") } catch (_: java.io.IOException) { }
    }

    @Test fun downloadsVerifiesRestoresAndSharesOnlyTheApkWithoutChangingSavedContent() = runBlocking {
        resetCatalog(context)
        val source = "https://example.test/kept.m3u"
        val movie = Channel("Filme salvo", "https://example.test/movie/1", "Filmes")
        PlaylistRepository(context).also { it.saveSource(source, "Minha lista"); it.appendCache(source, Download(Playlist(listOf(movie)), "", "")); it.saveFavorites(setOf(movie.url)); it.close() }
        val fixture = UpdateFixture(context); val repository = fixture.repository
        val progress = mutableListOf<Int>(); var verified = false
        val file = repository.download(repository.check()!!, { progress += it }, { verified = true })
        assertTrue(verified); assertEquals(100, progress.last()); assertEquals(progress.sorted().distinct(), progress)
        assertEquals(fixture.update.size, file.length()); assertEquals(fixture.update, fixture.repository.restorePrepared())
        val intent = repository.installIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.updates", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.resolveActivity(context.packageManager))
        context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals('P'.code, it.read()); assertEquals('K'.code, it.read()) }
        PlaylistRepository(context).also { assertEquals(source, it.savedUrl()); assertEquals(1, it.overview(source).total); assertEquals(setOf(movie.url), it.favorites()); it.close() }
        assertTrue(fixture.requested.all { it == UPDATE_RELEASES_URL || it.startsWith("https://github.com/$UPDATE_REPOSITORY/releases/") })
        file.appendBytes(byteArrayOf(1)); assertNull(fixture.repository.restorePrepared()); assertFalse(file.exists())
    }

    @Test fun rejectsCorruptedIncompleteWrongPackageAndWrongSignatureApks() = runBlocking {
        val fixture = UpdateFixture(context); val repository = fixture.repository
        failure(R.string.app_update_integrity_error) { repository.download(fixture.update.copy(sha256 = "0".repeat(64)), {}, {}) }
        failure(R.string.app_update_invalid) { repository.download(fixture.update.copy(size = fixture.update.size + 1), {}, {}) }
        val testApk = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val wrongPackage = fixture.update.copy(size = testApk.length(), sha256 = testApk.updateFixtureSha256())
        fixture.payload = testApk
        failure(R.string.app_update_invalid) { repository.download(wrongPackage, {}, {}) }
        val wrongSigner = File(context.cacheDir, "wrong-signer.apk")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("wrong-signer.apk").use { input -> wrongSigner.outputStream().use { input.copyTo(it) } }
            fixture.payload = wrongSigner
            val wrongSignature = fixture.update.copy(size = wrongSigner.length(), sha256 = wrongSigner.updateFixtureSha256())
            failure(R.string.app_update_signature_error) { repository.download(wrongSignature, {}, {}) }
        } finally { wrongSigner.delete() }
        assertFalse(repository.apkFile.exists()); assertNull(repository.restorePrepared())
        assertTrue(File(context.cacheDir, "app-updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationRemovesPartialDownloadAndNeverStartsInstallation() = runBlocking {
        val fixture = UpdateFixture(context); val repository = fixture.repository
        val started = CompletableDeferred<Unit>()
        val download = launch { repository.download(fixture.update, { started.complete(Unit); awaitCancellation() }, { fail("Unexpected verification") }) }
        started.await(); download.cancelAndJoin()
        assertFalse(repository.apkFile.exists()); assertNull(repository.restorePrepared())
        assertTrue(File(context.cacheDir, "app-updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun publicGitHubMetadataIsReachableFromAndroidWithoutAuthentication() {
        GitHubUpdateTransport().open(UPDATE_RELEASES_URL).use { assertNotNull(JSONArray(it.stream.bufferedReader().readText())) }
    }

    private suspend fun failure(expected: Int, block: suspend () -> Unit) {
        try { block(); fail("Expected update rejection") } catch (error: UpdateFailure) { assertEquals(expected, error.text) }
    }
}

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlineUpdateShowsCurrentAndNewVersionsProgressAndAndroidInstaller() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        File(context.cacheDir, "app-updates").deleteRecursively()
        val fixture = UpdateFixture(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var controller: AppUpdateController
        instrumentation.runOnMainSync { controller = AppUpdateController(fixture.repository, scope) }
        try {
            compose.setContent { AuroraTheme(AppAppearance.DARK) { Surface { AppUpdateContent(controller) } } }
            compose.onNodeWithTag("update_check").performClick()
            compose.waitUntil(15000) { controller.state.phase == UpdatePhase.AVAILABLE }
            compose.onNodeWithTag("update_download").assertIsDisplayed()
            instrumentation.runOnMainSync { controller.download {} }
            compose.waitUntil(30000) { controller.state.phase == UpdatePhase.READY }
            compose.onNodeWithTag("update_install").assertIsDisplayed()
            val device = UiDevice.getInstance(instrumentation)
            device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES deny")
            instrumentation.runOnMainSync { controller.install { fail("Permission must be requested first"); false } }
            compose.waitUntil(15000) { controller.state.phase == UpdatePhase.PERMISSION }
            compose.onNodeWithTag("update_permission").assertIsDisplayed()
            device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
            // Leaving settings during verification keeps the verified APK ready rather than pretending the installer opened.
            instrumentation.runOnMainSync { controller.install { false } }
            compose.waitUntil(15000) { controller.state.phase == UpdatePhase.READY }
            var intent: Intent? = null
            instrumentation.runOnMainSync { controller.install { intent = it; true } }
            compose.waitUntil(15000) { intent != null }
            // Open the real platform confirmation, then cancel rather than kill instrumentation by reinstalling itself.
            val target = intent!!.resolveActivity(context.packageManager)!!
            context.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(target.packageName).depth(0)), 10000))
            device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
            device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/online-update-installer.png")
            device.pressBack()
            instrumentation.runOnMainSync { controller.installerReturned(); assertEquals(UpdatePhase.READY, controller.state.phase) }
            fixture.failNetwork = true
            instrumentation.runOnMainSync { controller.check() }
            compose.waitUntil(15000) { controller.state.phase == UpdatePhase.ERROR }
            compose.onNodeWithTag("update_error").assertExists()
            fixture.failNetwork = false
            fixture.advertised = fixture.update.copy(versionCode = fixture.update.versionCode - 1)
            compose.onNodeWithTag("update_check").performClick()
            compose.waitUntil(15000) { controller.state.phase == UpdatePhase.CURRENT }
            compose.onNodeWithTag("update_current").assertExists()
        } finally {
            scope.cancel()
            UiDevice.getInstance(instrumentation).executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES default")
            File(context.cacheDir, "app-updates").deleteRecursively()
        }
    }
}
