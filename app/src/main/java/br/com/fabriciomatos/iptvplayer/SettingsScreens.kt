package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import coil.compose.AsyncImage

@Composable internal fun tr(id: Int, vararg args: Any) = stringResource(id, *args)
@Composable internal fun kindLabel(kind: MediaKind): String = tr(when(kind) {
    MediaKind.LIVE -> R.string.live; MediaKind.MOVIES -> R.string.movies
    MediaKind.SERIES -> R.string.series; else -> R.string.other
})
@Composable internal fun groupLabel(group: String) = when(group) {
    ADULT_GROUP->tr(R.string.adult_category);RECENT_GROUP->tr(R.string.recently_added)
    RELEASES_2026_GROUP->tr(R.string.top_releases,2026);UHD_GROUP->"4K";else->group
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsPanel(model: AuroraViewModel) {
    if(model.settingsSection==1) { PlaylistSettings(model);return }
    var guideAddress by rememberSaveable(model.epgOverride) { mutableStateOf(model.epgOverride) }
    ModalBottomSheet(onDismissRequest={model.settingsOpen=false},containerColor=MaterialTheme.colorScheme.surface,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if(model.settingsSection!=0) IconButton(onClick={model.settingsSection=0}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back))
                    }
                    Text(tr(when(model.settingsSection){2->R.string.language;4->R.string.programming;5->R.string.appearance;6->R.string.app_update_title;else->R.string.settings}),fontSize=23.sp,fontWeight=FontWeight.Bold)
                }
            }
            when(model.settingsSection) {
                0 -> {
                    item { SettingsItem(tr(R.string.playlist),tr(R.string.playlists_help),Icons.Default.PlaylistPlay) {model.settingsSection=1} }
                    item { SettingsItem(tr(R.string.appearance),appearanceLabel(model.appearance),Icons.Default.Palette) {model.settingsSection=5} }
                    item { SettingsItem(tr(R.string.language),appLanguages.first {it.tag==model.language}.label,Icons.Default.Language) {model.settingsSection=2} }
                    item { SettingsItem(tr(R.string.parental),tr(if(model.parentalConfigured)R.string.change_password else R.string.set_password),Icons.Default.Lock) {model.editParental()} }
                    item { SettingsItem(tr(R.string.programming),tr(R.string.sports),Icons.Default.EmojiEvents) {model.settingsSection=4} }
                    item { SettingsItem(tr(R.string.app_update_title),tr(R.string.app_update_installed,BuildConfig.VERSION_NAME),Icons.Default.SystemUpdate) {model.settingsSection=6;if(model.onlineUpdate.state.phase==UpdatePhase.IDLE)model.onlineUpdate.check()} }
                    item { Text("AURORA IPTV · ${BuildConfig.VERSION_NAME}",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp) }
                }
                2 -> items(appLanguages,key={it.tag}) {language ->
                    Row(Modifier.fillMaxWidth().clickable {model.chooseLanguage(language.tag)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=model.language==language.tag,onClick={model.chooseLanguage(language.tag)})
                        Text(language.label,Modifier.weight(1f).padding(start=8.dp),fontSize=16.sp)
                    }
                }
                4 -> {
                    item { Text(tr(R.string.guide_help),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(guideAddress,{guideAddress=it},Modifier.fillMaxWidth(),label={Text(tr(R.string.epg_address))},
                        singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri)) }
                    item { Button(onClick={model.setGuideAddress(guideAddress)},modifier=Modifier.fillMaxWidth()) {Text(tr(R.string.save))} }
                    item { OutlinedButton(onClick={model.settingsOpen=false;model.showFootball()},modifier=Modifier.fillMaxWidth()) {Text(tr(R.string.sports))} }
                }
                6 -> item { AppUpdateContent(model.onlineUpdate) }
                5 -> {
                    item {Text(tr(R.string.aurora_theme_help),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(AppAppearance.entries,key={it.name}) {appearance->
                        Row(Modifier.fillMaxWidth().testTag("theme_${appearance.name}").clickable {model.chooseAppearance(appearance)}
                            .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=model.appearance==appearance,onClick={model.chooseAppearance(appearance)})
                            Text(appearanceLabel(appearance),Modifier.padding(start=8.dp),fontSize=16.sp)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Card(onClick=click,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal=16.dp)) {Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Icon(Icons.Default.ChevronRight,null)
        }
    }
}
@Composable internal fun ParentalDialog(model: AuroraViewModel) {
    var password by remember {mutableStateOf("")}
    var newPassword by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    val setup = model.changePassword || !model.parentalConfigured
    AlertDialog(onDismissRequest={if(!model.parentalBusy)model.dismissParental()},
        icon={Icon(Icons.Default.Lock,null)},title={Text(tr(if(setup)R.string.set_password else R.string.adult_category))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(tr(if(setup)R.string.password_help else R.string.unlock_help))
            if(model.parentalConfigured) PasswordField(password,{password=it},tr(R.string.password))
            if(setup) {
                PasswordField(newPassword,{newPassword=it},tr(R.string.new_password))
                PasswordField(confirmation,{confirmation=it},tr(R.string.confirm_password))
            }
            model.parentalError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(model.parentalBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        confirmButton={TextButton(onClick={model.submitParental(password,newPassword,confirmation)},enabled=!model.parentalBusy) {Text(tr(if(setup)R.string.save else R.string.unlock))}},
        dismissButton={TextButton(onClick=model::dismissParental,enabled=!model.parentalBusy) {Text(tr(R.string.cancel))}})
}
@Composable private fun PasswordField(value: String, change: (String)->Unit, label: String) {
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),singleLine=true,label={Text(label)},visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
}
