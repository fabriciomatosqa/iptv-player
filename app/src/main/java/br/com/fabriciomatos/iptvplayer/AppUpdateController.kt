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
    private var job: Job? = null

    init {
        job = scope.launch {
            repository.restorePrepared()?.let { state = AppUpdateState(UpdatePhase.READY, it) }
        }
    }

    fun check() {
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
        if (state.phase == UpdatePhase.INSTALLER) state = state.copy(phase = UpdatePhase.READY)
    }
    fun permissionUnavailable() { state = state.copy(phase = UpdatePhase.ERROR, error = R.string.app_update_install_error) }
    private fun fail(error: Exception, update: AppUpdate? = null) {
        state = AppUpdateState(UpdatePhase.ERROR, update, error = (error as? UpdateFailure)?.text ?: R.string.app_update_network_error)
    }
}
