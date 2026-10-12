package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable internal fun CatalogLoadingIndicator(model: AuroraViewModel) {
    val modifier=Modifier.fillMaxWidth().height(3.dp).testTag("catalog_progress")
    val percent=model.progressPercent
    if(percent==null)LinearProgressIndicator(modifier)
    else LinearProgressIndicator(progress={percent/100f},modifier=modifier)
}
@Composable internal fun AnimePoster(model: AuroraViewModel, series: SeriesEntry, modifier: Modifier) {
    var image by remember(series.id,series.imageUrl) {mutableStateOf(series.imageUrl)}
    var failed by remember(series.id,series.imageUrl) {mutableStateOf(false)}
    LaunchedEffect(series.id,series.imageUrl,failed) {
        if(series.anime && (image.isBlank() || failed)) {
            model.animeCover(series)?.let {image=it}
        }
    }
    if(image.isNotBlank())AsyncImage(catalogImage(image),null,modifier,contentScale=ContentScale.Crop,onError={failed=true})
}

@Composable internal fun catalogImage(image: String): ImageRequest {
    val context=LocalContext.current
    return remember(context,image) {ImageRequest.Builder(context).data(image)
        .setHeader("User-Agent","AuroraIPTV/1.6 Android").apply {
            if(image.startsWith("https://media.kitsu.app/") || image.startsWith("https://media.kitsu.io/"))setHeader("Referer","https://kitsu.io/")
        }.build()}
}
