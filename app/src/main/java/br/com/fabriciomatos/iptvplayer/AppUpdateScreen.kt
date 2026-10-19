package br.com.fabriciomatos.iptvplayer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun AppUpdateContent(controller: AppUpdateController) {
    val context = LocalContext.current
    val state = controller.state
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.installerReturned() }
    val install = { if (active) controller.install { if (active) { installer.launch(it); true } else false } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { install() }
    Column(Modifier.fillMaxWidth().testTag("online_update_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        Text(tr(R.string.app_update_installed, BuildConfig.VERSION_NAME), fontWeight = FontWeight.SemiBold)
        Text(tr(R.string.app_update_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (state.phase) {
            UpdatePhase.IDLE -> Text(tr(R.string.app_update_prompt))
            UpdatePhase.CHECKING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(R.string.app_update_checking)) }
            UpdatePhase.CURRENT -> Text(tr(R.string.app_update_current), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("update_current"))
            UpdatePhase.AVAILABLE -> Text(tr(R.string.app_update_available, state.update!!.versionName), fontWeight = FontWeight.Bold)
            UpdatePhase.DOWNLOADING -> {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(tr(R.string.app_update_downloading, state.percent), Modifier.testTag("update_download_progress"))
            }
            UpdatePhase.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(R.string.app_update_verifying)) }
            UpdatePhase.READY -> Text(tr(R.string.app_update_ready, state.update!!.versionName))
            UpdatePhase.PERMISSION -> Text(tr(R.string.app_update_permission_help))
            UpdatePhase.INSTALLER -> Text(tr(R.string.app_update_installing))
            UpdatePhase.ERROR -> Text(tr(state.error ?: R.string.app_update_network_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("update_error"))
        }
        if (!state.busy && !state.update?.notes.isNullOrBlank()) Text(state.update!!.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR) && state.update != null) {
            Button(onClick = { controller.download(install) }, modifier = Modifier.fillMaxWidth().testTag("update_download")) { Text(tr(R.string.app_update_download_install)) }
        }
        if (state.phase == UpdatePhase.READY) {
            Button(onClick = install, modifier = Modifier.fillMaxWidth().testTag("update_install")) { Text(tr(R.string.app_update_install)) }
        }
        if (state.phase == UpdatePhase.PERMISSION) {
            Button(onClick = {
                runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { controller.permissionUnavailable() }
            }, modifier = Modifier.fillMaxWidth().testTag("update_permission")) { Text(tr(R.string.app_update_permission)) }
        }
        if (state.busy) {
            TextButton(onClick = controller::cancel, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.cancel)) }
        } else if (state.phase != UpdatePhase.INSTALLER) {
            OutlinedButton(onClick = controller::check, modifier = Modifier.fillMaxWidth().testTag("update_check")) { Text(tr(R.string.app_update_check)) }
        }
    }
}

/**
 * Lightweight startup question. Declining never downloads or installs an APK.
 * Accepting simply navigates to the existing update details/download screen.
 */
@Composable internal fun StartupAppUpdateDialog(controller: AppUpdateController, onAccept: () -> Unit) {
    val update = controller.startupOffer ?: return
    AlertDialog(
        onDismissRequest = controller::dismissStartupOffer,
        modifier = Modifier.testTag("update_startup_prompt"),
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = { Text(tr(R.string.app_update_available, update.versionName)) },
        text = { Text(tr(R.string.app_update_startup_question)) },
        confirmButton = {
            TextButton(onClick = {
                controller.dismissStartupOffer()
                onAccept()
            }, modifier = Modifier.testTag("update_startup_yes")) {
                Text(tr(R.string.app_update_startup_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = controller::dismissStartupOffer, modifier = Modifier.testTag("update_startup_no")) {
                Text(tr(R.string.app_update_startup_no))
            }
        }
    )
}
