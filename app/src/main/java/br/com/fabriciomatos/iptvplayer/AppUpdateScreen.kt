package br.com.fabriciomatos.iptvplayer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * Aurora-styled startup offer. This dialog never starts a download or installer:
 * accept goes to the existing update details screen, decline only dismisses it.
 * Long notes and short landscape screens remain scrollable.
 */
@Composable internal fun StartupAppUpdateDialog(controller: AppUpdateController, onAccept: () -> Unit) {
    val update = controller.startupOffer ?: return
    val aurora = LocalAuroraColors.current
    val rounding = RoundedCornerShape(28.dp)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(
        onDismissRequest = controller::dismissStartupOffer,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxWidth(0.92f).widthIn(max = 440.dp)
                .heightIn(max = screenHeight * 0.88f)
                .background(
                    Brush.linearGradient(listOf(
                        aurora.cyan.copy(alpha = 0.74f),
                        aurora.violet.copy(alpha = 0.48f),
                        aurora.mint.copy(alpha = 0.72f)
                    )), rounding
                ).padding(1.dp)
                .testTag("update_startup_prompt")
        ) {
            Column(
                Modifier.fillMaxWidth().clip(rounding).background(aurora.panel)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.aurora_brand),
                        contentDescription = tr(R.string.logo),
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AURORA IPTV", color = aurora.text,
                            fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                            letterSpacing = 0.8.sp
                        )
                        Text(tr(R.string.app_update_title), color = aurora.muted, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                            .background(aurora.mint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SystemUpdate, null, tint = aurora.mint, modifier = Modifier.size(24.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(R.string.app_update_available, update.versionName),
                        color = aurora.text, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 30.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(aurora.violet.copy(alpha = 0.17f))
                                .border(1.dp, aurora.violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 13.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "v" + update.versionName,
                                color = aurora.cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr(R.string.app_update_installed, BuildConfig.VERSION_NAME),
                            color = aurora.muted, fontSize = 11.sp, maxLines = 2
                        )
                    }
                }
                Text(
                    tr(R.string.app_update_startup_question),
                    color = aurora.text, fontSize = 14.sp, lineHeight = 21.sp
                )
                if (update.notes.isNotBlank()) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(aurora.elevated.copy(alpha = 0.72f))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AutoAwesome, null, tint = aurora.mint,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                tr(R.string.app_update_title), color = aurora.mint,
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                            )
                        }
                        Text(
                            update.notes.trim(), color = aurora.text.copy(alpha = 0.86f),
                            fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 4, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = controller::dismissStartupOffer,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("update_startup_no"),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, aurora.muted.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = aurora.text)
                    ) {
                        Text(tr(R.string.app_update_startup_no), maxLines = 1)
                    }
                    Button(
                        onClick = {
                            controller.dismissStartupOffer()
                            onAccept()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("update_startup_yes"),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = aurora.mint, contentColor = aurora.background
                        )
                    ) {
                        Icon(Icons.Default.SystemUpdate, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(tr(R.string.app_update_startup_yes), maxLines = 1)
                    }
                }
            }
        }
    }
}
