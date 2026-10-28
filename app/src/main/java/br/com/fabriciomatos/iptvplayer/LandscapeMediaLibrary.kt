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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collectLatest

/**
 * Projector/landscape-only library: choose a section on Home, then use categories
 * and a spacious catalog. Switching media kind requires Back -> Home.
 * Portrait's original Library and state/persistence are unchanged.
 */
@Composable internal fun LandscapeMediaLibrary(model: AuroraViewModel) {
    val kind = model.selectedKind
    var chosenMovie by remember(model.playlistUrl,kind) { mutableStateOf<Channel?>(null) }
    val chosenSeries = model.seriesDetails.forSource(model.playlistUrl)
    BackHandler(enabled = chosenMovie != null || chosenSeries != null) {
        if(chosenSeries!=null && model.seriesDetails.episodesVisible)model.seriesDetails.episodesVisible=false
        else {chosenMovie = null; model.closeSeriesDetails()}
    }
    if (chosenMovie != null || chosenSeries != null) {
        LandscapeContentDetails(
            model = model, movie = chosenMovie, series = chosenSeries,
            onBack = {chosenMovie = null; model.closeSeriesDetails()},
            onEpisodes = {model.seriesDetails.episodesVisible=true}
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080E1A))
        .testTag("landscape_cinema_library")) {
        val small = maxWidth < 810.dp
        val categoryWidth = (maxWidth * 0.25f).coerceIn(156.dp, 264.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (model.current != null) model.closeVideo()
                    model.backFromLibrary()
                }, modifier = Modifier.testTag("landscape_back_home")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back_home))
                }
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        model.openedSeries != null -> model.openedSeries!!.title
                        model.favoritesOnly -> tr(R.string.favorites)
                        kind != null -> kindLabel(kind)
                        else -> tr(R.string.search)
                    }, fontSize = if(small) 18.sp else 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.playlistName, fontSize = 11.sp, color = Color(0xFF9EADBC),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { model.refresh() }) { Icon(Icons.Default.Refresh, tr(R.string.refresh)) }
            }
            HorizontalDivider(color = Color(0xFF243347))
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(categoryWidth).fillMaxHeight()
                    .background(Color(0xFF101927)).padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
                    OutlinedTextField(
                        value = model.search, onValueChange = model::search,
                        modifier = Modifier.fillMaxWidth().testTag("landscape_cinema_search"),
                        placeholder = {Text(tr(R.string.search), fontSize = 12.sp)},
                        leadingIcon = { Icon(Icons.Default.Search,null,Modifier.size(18.dp)) },
                        singleLine = true, shape = RoundedCornerShape(12.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(if(model.openedSeries!=null)tr(R.string.seasons) else tr(R.string.categories),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFA7B5CA), modifier = Modifier.padding(8.dp))
                    val categories = remember(model.catalog, kind, model.favoritesOnly) { model.groups() }
                    LazyColumn(Modifier.weight(1f).testTag("landscape_cinema_categories"),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if(model.openedSeries != null) {
                            items(model.openedSeries!!.seasons) { season ->
                                LandscapeCategoryRow(tr(R.string.season,season),
                                    model.selectedSeason==season) { model.selectSeason(season) }
                            }
                        } else {
                            item {
                                LandscapeCategoryRow(tr(R.string.all),
                                    model.group==null) { model.selectGroup(null) }
                            }
                            items(categories,key={it}) { category ->
                                LandscapeCategoryRow(groupDisplayName(groupLabel(category)),
                                    model.group==category) {model.selectGroup(category)}
                            }
                        }
                    }
                }
                VerticalDivider(color=Color(0xFF253246))
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if(small) 10.dp else 16.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 53.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.group?.let {groupDisplayName(groupLabel(it))}
                                ?: model.openedSeries?.title
                                ?: kind?.let { kindLabel(it) } ?: tr(R.string.favorites),
                                fontSize=19.sp,fontWeight=FontWeight.Bold,
                                maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(tr(R.string.item_count,formatCount(model.visibleCount)),
                                fontSize=11.sp,color=Color(0xFF97A8BD))
                        }
                        model.openedSeries?.let {entry ->
                            SeriesFavoriteButton(model,entry)
                        }
                    }
                    if(model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.current?.let { playing ->
                        // Keep an accessible playback preview when rotating from portrait
                        // or returning from fullscreen; existing player remains attached.
                        // VideoPane owns the video_surface semantics tag. Do not attach
                        // another testTag to its own Modifier: Compose may shadow the
                        // inner tag, breaking remote/player controls after rotation.
                        Box(Modifier.fillMaxWidth().height(if(small) 142.dp else 200.dp)
                            .testTag("landscape_video_preview")) {
                            VideoPane(model, Modifier.fillMaxSize())
                        }
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(playing.name,Modifier.weight(1f).padding(start=8.dp),
                                maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                            IconButton(onClick=model::closeVideo) {
                                Icon(Icons.Default.Close,tr(R.string.close_video))
                            }
                        }
                    }
                    when {
                        kind==MediaKind.MOVIES && model.openedSeries==null ->
                            LandscapeMovieGrid(model,Modifier.weight(1f).fillMaxWidth()) {chosenMovie=it}
                        kind==MediaKind.SERIES && model.openedSeries==null ->
                            SeriesGrid(model,Modifier.weight(1f).fillMaxWidth()) {model.seriesDetails.open(it,model.playlistUrl)}
                        model.favoritesOnly && kind==null ->
                            FavoritesList(model,Modifier.weight(1f).fillMaxWidth()) {model.seriesDetails.open(it,model.playlistUrl)}
                        else -> ChannelList(model,Modifier.weight(1f).fillMaxWidth(),
                            guideMode=kind==MediaKind.LIVE && model.current!=null)
                    }
                }
            }
        }
    }
}

@Composable private fun LandscapeCategoryRow(label:String, selected:Boolean, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if(selected) Color(0xFF1B3348) else Color.Transparent)
        .landscapeRemoteClickable(onClick)
        .padding(horizontal=12.dp,vertical=11.dp),
        verticalAlignment=Alignment.CenterVertically) {
        if(selected) Box(Modifier.size(5.dp,22.dp).clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF4EDDBE)))
        Text(label,Modifier.weight(1f).padding(start=if(selected)8.dp else 0.dp),
            fontSize=13.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,
            maxLines=2,overflow=TextOverflow.Ellipsis,color=if(selected)Color(0xFF73EFDA) else Color.White)
    }
}

/** Paging-backed; only visible posters are composed and images come from the playlist. */
@Composable private fun LandscapeMovieGrid(
    model: AuroraViewModel, modifier: Modifier, onSelect: (Channel)->Unit
) {
    val entries=model.channelPages.collectAsLazyPagingItems()
    val key=model.catalogPositionKey()
    val anchor=remember(key){model.catalogAnchor(key)}
    val state=remember(key){LazyGridState(anchor?.index ?: 0,anchor?.offset ?: 0)}
    LaunchedEffect(key,state,entries) {
        snapshotFlow {
            if(model.filtering || entries.loadState.refresh is LoadState.Loading) null
            else Triple(state.firstVisibleItemIndex,state.firstVisibleItemScrollOffset,entries.itemSnapshotList.items)
        }.collectLatest { value ->
            value?.let {(index,offset,items) ->
                items.getOrNull(index)?.let {
                    model.rememberCatalogAnchor(key,CatalogAnchor(it.url,offset,index))
                }
            }
        }
    }
    if(entries.itemCount==0){
        Box(modifier,contentAlignment=Alignment.Center) {
            if(model.loading || model.filtering || entries.loadState.refresh is LoadState.Loading)
                CircularProgressIndicator()
            else Text(tr(R.string.no_content),color=Color(0xFF99A9BC))
        }
        return
    }
    LazyVerticalGrid(
        columns=GridCells.Adaptive(204.dp),
        state=state,
        modifier=modifier.testTag("landscape_movie_grid"),
        contentPadding=PaddingValues(top=8.dp,bottom=24.dp),
        horizontalArrangement=Arrangement.spacedBy(13.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)
    ) {
        items(entries.itemCount,key=entries.itemKey{it.url}) { index ->
            val movie=entries[index] ?: return@items
            if(movie.adult && !model.adultUnlocked)return@items
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF182235))
                .landscapeRemoteClickable {onSelect(movie)}
                .testTag("landscape_movie_poster")) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f/3f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF26374A),Color(0xFF11182A)))),
                    contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.Movie, null, tint=Color(0xFF6CD5EB),
                        modifier=Modifier.size(44.dp))
                    if(movie.imageUrl.isNotBlank())
                        AsyncImage(catalogImage(movie.imageUrl),null,Modifier.fillMaxSize(),
                            contentScale=ContentScale.Crop)
                }
                Text(movie.name,Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=9.dp),
                    color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold,
                    maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
            }
        }
        if(entries.loadState.append is LoadState.Loading) item {
            CircularProgressIndicator(Modifier.padding(20.dp))
        }
    }
}
