package br.com.fabriciomatos.iptvplayer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * Aurora-branded startup offer. Declining never downloads or installs an APK;
 * accepting navigates to the existing details, notes and signed download flow.
 */
@Composable internal fun StartupAppUpdateDialog(controller: AppUpdateController, onAccept: () -> Unit) {
    val update = controller.startupOffer ?: return
    val mint = LocalAuroraColors.current.mint
    val cyan = LocalAuroraColors.current.cyan
    val violet = LocalAuroraColors.current.violet
    val textColor = LocalAuroraColors.current.text
    val muted = LocalAuroraColors.current.muted
    val panel = LocalAuroraColors.current.panel
    val dismiss = controller::dismissStartupOffer
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 22.dp)
            .testTag("update_startup_prompt"), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 430.dp
            val cardShape = RoundedCornerShape(28.dp)
            Column(
                Modifier.widthIn(max = 430.dp)
                    .clip(cardShape)
                    .background(
                        Brush.linearGradient(
                            listOf(panel, panel.copy(alpha = 0.97f), violet.copy(alpha = 0.16f))
                        )
                    )
                    .border(1.dp, Brush.linearGradient(listOf(cyan.copy(alpha = 0.55f),
                        violet.copy(alpha = 0.45f), mint.copy(alpha = 0.4f))), cardShape)
                    .verticalScroll(rememberScrollState())
                    .padding(if (compact) 20.dp else 26.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(17.dp))
                            .background(Brush.linearGradient(listOf(cyan.copy(alpha = 0.18f),
                                violet.copy(alpha = 0.22f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null,
                            tint = cyan, modifier = Modifier.size(30.dp))
                    }
                    Column {
                        Text("AURORA IPTV", color = mint,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(tr(R.string.app_update_available, update.versionName),
                            color = textColor, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Surface(color = violet.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, violet.copy(alpha = 0.36f))) {
                    Text("v${update.versionName}", modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        color = cyan, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                }
                Text(tr(R.string.app_update_startup_question), color = muted,
                    style = MaterialTheme.typography.bodyLarge)
                if (!update.notes.isNullOrBlank()) {
                    Text(update.notes, color = muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)
                        .testTag("update_startup_no"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, muted.copy(alpha = 0.4f))) {
                        Text(tr(R.string.app_update_startup_no), maxLines = 1)
                    }
                    Button(onClick = {
                        controller.dismissStartupOffer()
                        onAccept()
                    }, modifier = Modifier.weight(1f).testTag("update_startup_yes"),
                        colors = ButtonDefaults.buttonColors(containerColor = mint,
                            contentColor = Color(0xFF091722))) {
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(tr(R.string.app_update_startup_yes), maxLines = 1)
                    }
                }
            }
        }
    }
}
