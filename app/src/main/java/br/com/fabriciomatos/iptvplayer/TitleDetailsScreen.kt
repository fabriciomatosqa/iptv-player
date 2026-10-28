package br.com.fabriciomatos.iptvplayer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val DetailText=Color(0xFFF5F8FF)
private val DetailMuted=Color(0xFFADBDCF)
private val DetailAccent=Color(0xFF55D9BB)

/** The title remains in place; Episodes replaces the cast with vertically stacked season rows. */
@Composable internal fun LandscapeContentDetails(
    model: AuroraViewModel, movie: Channel?, series: SeriesEntry?,
    onBack: ()->Unit, onEpisodes: (SeriesEntry)->Unit
) {
    val source=model.playlistUrl
    val restricted=(movie?.adult==true || series?.adult==true) && !model.adultUnlocked
    LaunchedEffect(restricted) {if(restricted)onBack()}
    if(restricted)return
    val navigation=model.seriesDetails
    val episodesVisible=series!=null && navigation.episodesVisible
    BackHandler(enabled=episodesVisible) {navigation.episodesVisible=false}
    var synopsis by remember(source,movie?.url,series?.id) {mutableStateOf<Synopsis?>(null)}
    var episodeDetails by remember(source,series?.id) {mutableStateOf(emptyList<EpisodeSynopsis>())}
    LaunchedEffect(source,movie?.url,series?.id) {synopsis=model.synopsis(movie,series)}
    LaunchedEffect(source,series?.id,episodesVisible) {
        if(episodesVisible)episodeDetails=model.episodeDetails(series)
    }
    val photos=(LocalContext.current.applicationContext as AuroraApplication).castPhotos
    val actors=synopsis?.cast.orEmpty()
    var indexedCast by remember(source,movie?.url,series?.id) {mutableStateOf(emptyList<CastMember>())}
    LaunchedEffect(actors) {
        photos.remember(model.cachedCastPhotos(actors))
        photos.remember(actors)
        indexedCast=actors
    }
    val fallback=movie?.imageUrl ?: series?.imageUrl.orEmpty()
    val backdrop=synopsis?.backdropUrl?.takeIf(String::isNotBlank) ?: fallback
    val scroll=if(series!=null)navigation.scroll else rememberLazyListState()
    val scope=rememberCoroutineScope()
    val seasons=series?.seasons.orEmpty().distinct().sorted()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF070D17))
        .testTag("landscape_title_details")) {
        val compact=maxWidth<600.dp
        val side=if(compact)16.dp else 30.dp
        if(backdrop.isNotBlank())AsyncImage(catalogImage(backdrop),null,
            Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            Color(0xF2080D17),Color(0xDD080D17),Color(0x99080D17),Color(0x77080D17)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0x22080D17),Color(0xCC080D17),Color(0xFF080D17)))))
        LazyColumn(state=scroll,modifier=Modifier.fillMaxSize().testTag("title_details_scroll"),
            contentPadding=PaddingValues(top=12.dp,bottom=32.dp),
            verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item(key="header") {
                Column(Modifier.fillMaxWidth().padding(horizontal=side)) {
                    IconButton(onClick={if(episodesVisible)navigation.episodesVisible=false else onBack()},
                        modifier=Modifier.testTag("landscape_details_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back),tint=DetailText)
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(movie?.name ?: series?.title.orEmpty(),color=DetailText,
                        fontSize=if(compact)27.sp else 32.sp,fontWeight=FontWeight.Bold,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(14.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(!synopsis?.rating.isNullOrBlank())Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Star,null,tint=Color(0xFFFFCD60),modifier=Modifier.size(18.dp))
                            Text(synopsis!!.rating,color=DetailText,fontSize=13.sp)
                        }
                        listOf(synopsis?.year,synopsis?.duration,synopsis?.genre)
                            .filterNotNull().filter(String::isNotBlank).forEach {
                                Text(it,color=Color(0xFFD9E4F2),fontSize=13.sp,maxLines=2,
                                    overflow=TextOverflow.Ellipsis)
                            }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(when {
                        synopsis==null->tr(R.string.loading)
                        synopsis!!.plot.isNotBlank()->synopsis!!.plot
                        else->tr(R.string.synopsis_unavailable)
                    },color=Color(0xFFE1E8F1),fontSize=14.sp,lineHeight=20.sp,maxLines=5,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth(if(compact)1f else .76f)
                            .testTag("landscape_details_synopsis"))
                    if(!synopsis?.metadataSource.isNullOrBlank())MetadataCredit(
                        tr(R.string.synopsis_source,synopsis!!.metadataSource),synopsis!!.metadataUrl)
                    Spacer(Modifier.height(18.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={
                            if(movie!=null)model.requestPlay(movie,fullscreen=true)
                            else if(series!=null) {
                                onEpisodes(series)
                                scope.launch {scroll.animateScrollToItem(1)}
                            }
                        },modifier=Modifier.defaultMinSize(minWidth=156.dp).testTag("landscape_details_play"),
                            colors=ButtonDefaults.buttonColors(containerColor=DetailAccent,contentColor=Color(0xFF0B1922))) {
                            Icon(if(movie!=null)Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.List,null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr(if(movie!=null)R.string.play_content else R.string.episodes),fontWeight=FontWeight.Bold)
                        }
                        if(episodesVisible)OutlinedButton(onClick={navigation.episodesVisible=false},
                            modifier=Modifier.testTag("series_show_cast")) {Text(tr(R.string.cast),color=DetailText)}
                        if(series!=null)SynopsisAction(model,series=series)
                        OutlinedButton(onClick={
                            if(movie!=null)model.toggleFavorite(movie)
                            else if(series!=null)model.toggleSeriesFavorite(series)
                        },modifier=Modifier.testTag("landscape_details_favorite")) {
                            val favorite=if(movie!=null)model.isFavorite(movie) else series?.id in model.seriesFavorites
                            Icon(if(favorite)Icons.Default.Star else Icons.Default.StarBorder,null,tint=Color(0xFFFFD05C))
                            Spacer(Modifier.width(6.dp));Text(tr(R.string.favorites),color=DetailText)
                        }
                    }
                }
            }
            if(episodesVisible) {
                item(key="season_navigation") {
                    Column(Modifier.fillMaxWidth()) {
                        Text(tr(R.string.seasons),Modifier.padding(horizontal=side),color=DetailText,
                            fontSize=19.sp,fontWeight=FontWeight.Bold)
                        LazyRow(contentPadding=PaddingValues(horizontal=side),horizontalArrangement=Arrangement.spacedBy(8.dp),
                            modifier=Modifier.fillMaxWidth().testTag("episode_season_navigation")) {
                            items(seasons,key={it}) {season ->
                                SuggestionChip(onClick={scope.launch {scroll.animateScrollToItem(seasons.indexOf(season)+2)}},
                                    label={Text(tr(R.string.season,season),color=DetailText)},
                                    modifier=Modifier.testTag("episode_season_jump_$season"))
                            }
                        }
                    }
                }
                items(seasons,key={"season:$it"}) {season ->
                    SeasonEpisodeRow(model,series,season,episodeDetails.ifEmpty {synopsis?.episodes.orEmpty()},side,compact)
                }
                if(seasons.isEmpty())item {Text(tr(R.string.no_content),Modifier.padding(side),color=DetailMuted)}
            } else item(key="cast") {CastCarousel(actors,photos,synopsis!=null,indexedCast==actors,side)}
        }
        if(model.current!=null && !model.fullscreen) {
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp).width(if(compact)210.dp else 260.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF101927)).testTag("episode_video_preview")) {
                VideoPane(model,Modifier.fillMaxWidth().aspectRatio(16f/9f))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(model.current!!.name,Modifier.weight(1f).padding(8.dp),color=DetailText,fontSize=11.sp,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=model::closeVideo) {Icon(Icons.Default.Close,tr(R.string.close_video),tint=DetailText)}
                }
            }
        }
    }
}

@Composable private fun SeasonEpisodeRow(
    model: AuroraViewModel, series: SeriesEntry, season: Int, details: List<EpisodeSynopsis>,
    side: androidx.compose.ui.unit.Dp, compact: Boolean
) {
    val source=model.playlistUrl
    val flow=remember(source,series.id,season,model.adultUnlocked,model.catalog) {model.episodeBrowserPages(series,season)}
    val entries=flow.collectAsLazyPagingItems()
    val channels=entries.itemSnapshotList.items
    var progress by remember(source,series.id,season) {mutableStateOf(emptyMap<String,PlaybackProgress>())}
    LaunchedEffect(source,channels,model.playbackProgressRevision) {progress=model.playbackProgress(channels)}
    val metadata=remember(details) {ContentDetails(episodes=details)}
    Column(Modifier.fillMaxWidth().testTag("episode_season_$season")) {
        Text(tr(R.string.season,season),Modifier.padding(horizontal=side,vertical=8.dp),
            color=DetailText,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        LazyRow(state=model.seriesDetails.row(season),contentPadding=PaddingValues(horizontal=side),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
            modifier=Modifier.fillMaxWidth().testTag("episode_row_$season")) {
            items(entries.itemCount,key=entries.itemKey {it.url}) {index ->
                val channel=entries[index] ?: return@items
                val episode=remember(channel) {seriesIdentity(channel).episode}
                val info=remember(metadata,channel) {selectEpisodeSynopsis(metadata,channel)}
                EpisodeSceneCard(episode,info,progress[channel.url],if(compact)238.dp else 218.dp,
                    detailsAction={SynopsisAction(model,channel,series,iconOnly=true)}) {
                    model.requestPlay(channel,fullscreen=true)
                }
            }
            if(entries.loadState.refresh is LoadState.Loading || entries.loadState.append is LoadState.Loading)
                item {CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp),color=DetailAccent)}
            if(entries.loadState.refresh is LoadState.Error || entries.loadState.append is LoadState.Error)
                item {TextButton(onClick={entries.retry()}) {Text(tr(R.string.retry))}}
        }
    }
}

@Composable internal fun EpisodeSceneCard(
    episode: Episode, metadata: EpisodeSynopsis?, saved: PlaybackProgress?,
    width: androidx.compose.ui.unit.Dp=218.dp, detailsAction: @Composable ()->Unit = {}, onClick: ()->Unit
) {
    val progress=saved ?: PlaybackProgress(0,metadata?.durationMs ?: 0)
    val display=if(progress.durationMs<=0 && (metadata?.durationMs ?: 0)>0)
        progress.copy(durationMs=metadata!!.durationMs) else progress
    val complete=progress.watchState==PlaybackWatchState.COMPLETED
    // Provider runtimes are estimates; only actual playback can mark an episode finished.
    val fraction=if(!complete && progress.durationMs<=0)display.fraction.coerceAtMost(.99f) else display.fraction
    val flag=if(complete)Color(0xFF62E8A7) else Color(0xFFFFCF58)
    val status=tr(if(complete)R.string.episode_completed else R.string.episode_unfinished)
    val description=tr(R.string.episode_progress,(fraction*100).toInt())
    Column(Modifier.width(width).clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF152333)).border(1.dp,Color(0xFF334355),RoundedCornerShape(12.dp))
        .landscapeRemoteClickable(onClick).testTag("episode_card_${episode.season}_${episode.number}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color(0xFF24354A)),contentAlignment=Alignment.Center) {
            Icon(Icons.Default.Movie,null,tint=DetailMuted,modifier=Modifier.size(36.dp))
            if(!metadata?.imageUrl.isNullOrBlank())AsyncImage(catalogImage(metadata!!.imageUrl),
                tr(R.string.episode_scene,episode.number),Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xAA030910)))))
            Icon(Icons.Default.PlayCircle,null,tint=Color.White.copy(alpha=.9f),modifier=Modifier.size(34.dp))
            Box(Modifier.align(Alignment.BottomEnd).size(36.dp).background(Color(0xAA09111D),RoundedCornerShape(topStart=8.dp))) {detailsAction()}
            Row(Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xEC09111D)).padding(horizontal=6.dp,vertical=4.dp)
                .testTag("episode_flag_${episode.season}_${episode.number}"),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Flag,status,tint=flag,modifier=Modifier.size(15.dp))
                Text(status,color=flag,fontSize=10.sp,modifier=Modifier.padding(start=3.dp))
            }
        }
        LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(4.dp)
            .semantics {contentDescription=description}.testTag("episode_progress_${episode.season}_${episode.number}"),
            color=flag,trackColor=Color(0xFF354456))
        Column(Modifier.fillMaxWidth().padding(9.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(episode.label,color=DetailAccent,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
            Text(metadata?.title?.takeIf(String::isNotBlank) ?: episode.title,color=DetailText,fontSize=13.sp,
                maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
            val runtime=display.durationMs.takeIf {it>0}?.let {tr(R.string.episode_minutes,(it/60000).coerceAtLeast(1))}.orEmpty()
            Text(if(saved!=null && saved.positionMs>0)"${(fraction*100).toInt()}% • $runtime".trim(' ','•') else runtime,
                color=DetailMuted,fontSize=10.sp,maxLines=1)
            if(metadata?.imageSource=="TVmaze")MetadataCredit("TVmaze",metadata.imageSourceUrl)
        }
    }
}

@Composable private fun CastCarousel(actors: List<CastMember>, photos: CastPhotoRepository, loaded: Boolean, indexed: Boolean,
    side: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth()) {
        if(actors.isNotEmpty()) {
            Text(tr(R.string.cast),Modifier.padding(horizontal=side),color=DetailText,fontSize=19.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding=PaddingValues(horizontal=side),horizontalArrangement=Arrangement.spacedBy(12.dp),
                modifier=Modifier.fillMaxWidth().testTag("landscape_details_cast")) {
                items(actors,key={it.name}) {actor ->
                    var photo by remember(actor) {mutableStateOf(CastPhoto(actor.imageUrl))}
                    var failed by remember(actor) {mutableStateOf<String?>(null)}
                    LaunchedEffect(actor,failed,indexed) {if(indexed)photo=photos.resolve(actor,failed)}
                    Column(Modifier.width(105.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.width(105.dp).height(116.dp).clip(RoundedCornerShape(11.dp))
                            .background(Color(0xFF293A4F)),contentAlignment=Alignment.Center) {
                            Icon(Icons.Default.Person,null,tint=Color(0xFFA8BDD3),modifier=Modifier.size(43.dp))
                            if(photo.imageUrl.isNotBlank() && photo.imageUrl!=failed)
                                AsyncImage(catalogImage(photo.imageUrl),actor.name,Modifier.fillMaxSize(),
                                    contentScale=ContentScale.Crop,onError={failed=photo.imageUrl})
                        }
                        Text(actor.name,Modifier.padding(top=5.dp),color=DetailText,fontSize=11.sp,
                            maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(photo.sourceUrl.startsWith("https://www.tvmaze.com/"))MetadataCredit("TVmaze",photo.sourceUrl)
                    }
                }
            }
        } else if(loaded)Text(tr(R.string.cast_unavailable),Modifier.padding(horizontal=side),color=DetailMuted,fontSize=12.sp)
    }
}

@Composable private fun MetadataCredit(label: String, url: String) {
    val context=LocalContext.current
    Text(label,color=Color(0xFF80DCCF),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
        modifier=Modifier.padding(vertical=5.dp).clickable(enabled=url.startsWith("https://")) {
            runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
        })
}
