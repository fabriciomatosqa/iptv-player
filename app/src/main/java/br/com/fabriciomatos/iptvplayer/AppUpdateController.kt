package br.com.fabriciomatos.iptvplayer

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, PERMISSION, INSTALLER, ERROR }
internal data class AppUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val update: AppUpdate? = null, val percent: Int = 0, val error: Int? = null) {
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

internal class AppUpdateController(private val repository: AppUpdateRepository, private val scope: CoroutineScope) {
    var state by mutableStateOf(AppUpdateState()); private set
    /** Only the foreground entry can offer an update; manual checks never open a popup. */
    var startupOffer by mutableStateOf<AppUpdate?>(null); private set
    private var job: Job? = null
    private var startupJob: Job? = null
    private var foreground = false
    private var allowStartupPrompt = false

    init {
        job = scope.launch {
            repository.restorePrepared()?.let { state = AppUpdateState(UpdatePhase.READY, it) }
        }
    }

    /**
     * Check once per foreground entry, asynchronously and without blocking catalog loading.
     * Recreated activities keep the same ViewModel and do not trigger duplicate requests.
     * The existing update screen remains the only place that starts a download.
     */
    fun onForeground() {
        if (foreground) return
        foreground = true
        allowStartupPrompt = true
        if (startupJob?.isActive == true || state.busy || state.phase in setOf(UpdatePhase.PERMISSION, UpdatePhase.INSTALLER)) return
        val preceding = job
        startupJob = scope.launch {
            preceding?.join() // Restore a verified APK before comparing remote versions.
            if (!foreground || state.busy || state.phase in setOf(UpdatePhase.PERMISSION, UpdatePhase.INSTALLER)) return@launch
            val ready = state.update?.takeIf { state.phase == UpdatePhase.READY }
            state = AppUpdateState(UpdatePhase.CHECKING)
            try {
                val available = repository.check()
                state = when {
                    available == null -> AppUpdateState(UpdatePhase.CURRENT)
                    ready != null && ready.versionCode == available.versionCode && ready.sha256 == available.sha256 ->
                        AppUpdateState(UpdatePhase.READY, available, 100)
                    else -> AppUpdateState(UpdatePhase.AVAILABLE, available)
                }
                if (foreground && allowStartupPrompt && available != null) startupOffer = available
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A failed background check must not hide a previously verified download.
                if (ready != null) state = AppUpdateState(UpdatePhase.READY, ready, 100)
                else fail(e)
            }
        }
    }

    fun onBackground() {
        foreground = false
        startupOffer = null
    }

    fun dismissStartupOffer() {
        allowStartupPrompt = false
        startupOffer = null
    }

    fun check() {
        // Entering the manual update flow supersedes the startup invitation.
        dismissStartupOffer()
        if (state.busy) return
        job?.cancel()
        state = AppUpdateState(UpdatePhase.CHECKING)
        job = scope.launch {
            try {
                val update = repository.check()
                state = AppUpdateState(if (update == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, update)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    fun download(onReady: () -> Unit) {
        val update = state.update ?: return
        if (state.busy) return
        job?.cancel()
        state = AppUpdateState(UpdatePhase.DOWNLOADING, update)
        job = scope.launch {
            try {
                repository.download(update, { percent -> withContext(Dispatchers.Main) {
                    state = AppUpdateState(UpdatePhase.DOWNLOADING, update, percent)
                } }, { withContext(Dispatchers.Main) { state = AppUpdateState(UpdatePhase.VERIFYING, update, 100) } })
                state = AppUpdateState(UpdatePhase.READY, update, 100)
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e, update) }
        }
    }

    fun cancel() {
        job?.cancel()
        state = AppUpdateState(if (state.update == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, state.update)
    }

    fun install(launch: (Intent) -> Boolean) {
        val update = state.update ?: return
        if (state.busy) return
        state = AppUpdateState(UpdatePhase.VERIFYING, update, 100)
        job = scope.launch {
            try {
                repository.validateReady(update)
                if (!repository.canInstall()) state = AppUpdateState(UpdatePhase.PERMISSION, update, 100)
                else {
                    val opened = launch(repository.installIntent())
                    state = AppUpdateState(if (opened) UpdatePhase.INSTALLER else UpdatePhase.READY, update, 100)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(if(e is android.content.ActivityNotFoundException) UpdateFailure(R.string.app_update_install_error) else e, update) }
        }
    }

    fun installerReturned() {
        if (state.phase in setOf(UpdatePhase.PERMISSION, UpdatePhase.INSTALLER)) state = state.copy(phase = UpdatePhase.READY)
    }
    fun permissionUnavailable() { state = state.copy(phase = UpdatePhase.ERROR, error = R.string.app_update_install_error) }
    private fun fail(error: Exception, update: AppUpdate? = null) {
        state = AppUpdateState(UpdatePhase.ERROR, update, error = (error as? UpdateFailure)?.text ?: R.string.app_update_network_error)
    }
}
