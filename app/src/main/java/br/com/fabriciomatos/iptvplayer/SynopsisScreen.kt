package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SynopsisAction(model: AuroraViewModel, channel: Channel? = null, series: SeriesEntry? = null, iconOnly: Boolean = false) {
    var open by remember(model.playlistUrl,channel?.url,series?.id) {mutableStateOf(false)}
    val label=tr(R.string.synopsis)
    if(iconOnly)IconButton(onClick={open=true},Modifier.size(48.dp).testTag("synopsis_action")) {Icon(Icons.Outlined.Info,label,Modifier.size(20.dp))}
    else TextButton(onClick={open=true},modifier=Modifier.testTag("synopsis_action")) {Icon(Icons.Outlined.Info,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(label)}
    if(open)SynopsisDialog(model,channel,series) {open=false}
}

@Composable private fun SynopsisDialog(model: AuroraViewModel, channel: Channel?, series: SeriesEntry?, dismiss: ()->Unit) {
    val movie=channel?.kind==MediaKind.MOVIES
    val restricted=(channel?.adult==true || series?.adult==true) && !model.adultUnlocked
    LaunchedEffect(restricted) {if(restricted)dismiss()}
    if(restricted)return
    var result by remember {mutableStateOf<Synopsis?>(null)}
    var attempt by remember {mutableIntStateOf(0)}
    LaunchedEffect(attempt) {result=null;result=model.synopsis(channel,series,attempt>0)}
    val title=if(movie)channel!!.name else series?.title ?: channel?.let {seriesIdentity(it).title}.orEmpty()
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("synopsis_dialog"),
        title={Text(title,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.fillMaxWidth().heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val details=result
            when {
                details==null->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp));Text(tr(R.string.loading))
                }
                details.failed->{Text(tr(R.string.synopsis_failed));TextButton(onClick={attempt++}) {Text(tr(R.string.retry))}}
                else->{
                    Text(tr(if(movie)R.string.synopsis else R.string.series_synopsis),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                    Text(details.plot.ifBlank {tr(R.string.synopsis_unavailable)},Modifier.testTag("content_synopsis"))
                    if(!movie && channel!=null) {
                        HorizontalDivider()
                        Text(tr(R.string.episode_synopsis),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                        Text(details.episode?.title?.takeIf(String::isNotBlank) ?: channel.name,fontWeight=FontWeight.SemiBold)
                        Text(details.episode?.plot?.takeIf(String::isNotBlank) ?: tr(R.string.episode_synopsis_unavailable),Modifier.testTag("episode_synopsis"))
                    }
                }
            }
        }},confirmButton={TextButton(onClick=dismiss) {Text(tr(R.string.close_details))}})
}
