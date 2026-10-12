package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun tr(id: Int, vararg args: Any) = stringResource(id, *args)
@Composable internal fun kindLabel(kind: MediaKind): String = tr(when(kind) {
    MediaKind.LIVE -> R.string.live; MediaKind.MOVIES -> R.string.movies
    MediaKind.SERIES -> R.string.series; else -> R.string.other
})
@Composable internal fun groupLabel(group: String) = if(group==ADULT_GROUP)tr(R.string.adult_category)else group

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
                    Text(tr(when(model.settingsSection){2->R.string.language;4->R.string.programming;else->R.string.settings}),fontSize=23.sp,fontWeight=FontWeight.Bold)
                }
            }
            when(model.settingsSection) {
                0 -> {
                    item { SettingsItem(tr(R.string.playlist),tr(R.string.saved_text),Icons.Default.PlaylistPlay) {model.settingsSection=1} }
                    item { SettingsItem(tr(R.string.language),appLanguages.first {it.tag==model.language}.label,Icons.Default.Language) {model.settingsSection=2} }
                    item { SettingsItem(tr(R.string.parental),tr(if(model.parentalConfigured)R.string.change_password else R.string.set_password),Icons.Default.Lock) {model.editParental()} }
                    item { SettingsItem(tr(R.string.programming),tr(R.string.football),Icons.Default.SportsSoccer) {model.settingsSection=4} }
                    item { Text("AURORA IPTV · 1.4.0",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp) }
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
                    item { OutlinedButton(onClick={model.settingsOpen=false;model.showFootball()},modifier=Modifier.fillMaxWidth()) {Text(tr(R.string.football))} }
                }
            }
        }
    }
}
@Composable private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Card(onClick=click,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) {
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
@Composable internal fun FootballScreen(model: AuroraViewModel) {
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true){kotlinx.coroutines.delay(60000);now=System.currentTimeMillis()}}
    val matches=model.football.filter {it.programme.end>now}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={model.page=Page.HOME}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back_home))}
            Text(tr(R.string.football),Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
            IconButton(onClick=model::refreshFootball){Icon(Icons.Default.Refresh,tr(R.string.refresh))}
            IconButton(onClick={model.openSettings()}){Icon(Icons.Default.Settings,tr(R.string.settings))}
        }
        Text(tr(R.string.football_help),Modifier.padding(horizontal=20.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(model.guideLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(matches.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SportsSoccer,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                Text(tr(if(model.guideLoading)R.string.loading else if(model.guideError)R.string.no_guide else R.string.no_games))
                if(!model.guideLoading)OutlinedButton(onClick=model::refreshFootball){Text(tr(R.string.retry))}
            }
        } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(matches,key={it.id}) {match ->
                var expanded by remember {mutableStateOf(false)}
                Card(onClick={model.play(match.channels.first())},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsSoccer,null,tint=MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                            Text(match.programme.title,fontWeight=FontWeight.SemiBold)
                            val live=match.programme.start<=now
                            Text(if(live)tr(R.string.now)else SimpleDateFormat("EEE, dd MMM · HH:mm",Locale.getDefault()).format(Date(match.programme.start)),
                                color=MaterialTheme.colorScheme.primary,fontSize=12.sp)
                            Text(match.channels.first().name,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                        }
                        if(match.channels.size==1) Icon(Icons.Default.PlayArrow,tr(R.string.watch))
                        else Box {
                            IconButton(onClick={expanded=true}) {Icon(Icons.Default.MoreVert,tr(R.string.channels))}
                            DropdownMenu(expanded,{expanded=false}) {match.channels.forEach {channel ->
                                DropdownMenuItem(text={Text(channel.name)},onClick={expanded=false;model.play(channel)})
                            }}
                        }
                    }
                }
            }
        }
    }
}
