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
import kotlinx.coroutines.launch

@Composable internal fun SynopsisAction(model: AuroraViewModel, channel: Channel? = null, series: SeriesEntry? = null, iconOnly: Boolean = false) {
    val scope = rememberCoroutineScope()
    var open by remember(model.playlistUrl,channel?.url,series?.id) { mutableStateOf(false) }
    var result by remember(model.playlistUrl,channel?.url,series?.id) { mutableStateOf<Synopsis?>(null) }
    var requestNumber by remember(model.playlistUrl,channel?.url,series?.id) { mutableIntStateOf(0) }
    fun load(force: Boolean) {
        val request = ++requestNumber
        result = null
        // Start work on the actual click, so native dialog composition cannot delay
        // loading cached synopsis data on slower Android projectors/emulators.
        scope.launch {
            val loaded = model.synopsis(channel, series, force)
            if (request == requestNumber) result = loaded
        }
    }
    val label = tr(R.string.synopsis)
    val tag = if (channel == null) "synopsis_series_action" else "synopsis_content_action"
    val show = { open = true; load(false) }
    if (iconOnly) IconButton(onClick = show, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(Icons.Outlined.Info, label, Modifier.size(20.dp))
    } else TextButton(onClick = show, modifier = Modifier.testTag(tag)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
    if (open) SynopsisDialog(model, channel, series, result, onRetry = {load(true)}) {open = false}
}

@Composable private fun SynopsisDialog(
    model: AuroraViewModel, channel: Channel?, series: SeriesEntry?,
    result: Synopsis?, onRetry: () -> Unit, dismiss: () -> Unit
) {
    val movie=channel?.kind==MediaKind.MOVIES
    val restricted=(channel?.adult==true || series?.adult==true) && !model.adultUnlocked
    LaunchedEffect(restricted) {if(restricted)dismiss()}
    if(restricted)return
    val title=if(movie)channel!!.name else series?.title ?: channel?.let {seriesIdentity(it).title}.orEmpty()
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("synopsis_dialog"),
        title={Text(title,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.fillMaxWidth().heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val details=result
            when {
                details==null->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp));Text(tr(R.string.loading))
                }
                details.failed->{Text(tr(R.string.synopsis_failed));TextButton(onClick=onRetry) {Text(tr(R.string.retry))}}
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
