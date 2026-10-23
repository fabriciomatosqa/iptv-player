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
    var chosenSeries by remember(model.playlistUrl,kind) { mutableStateOf<SeriesEntry?>(null) }
    BackHandler(enabled = chosenMovie != null || chosenSeries != null) {
        chosenMovie = null; chosenSeries = null
    }
    if (chosenMovie != null || chosenSeries != null) {
        LandscapeContentDetails(
            model = model, movie = chosenMovie, series = chosenSeries,
            onBack = {chosenMovie = null; chosenSeries = null},
            onEpisodes = { entry ->
                chosenSeries = null
                model.showSeries(entry)
            }
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
                            SeriesGrid(model,Modifier.weight(1f).fillMaxWidth()) {chosenSeries=it}
                        model.favoritesOnly && kind==null ->
                            FavoritesList(model,Modifier.weight(1f).fillMaxWidth())
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

/** Backdrop, synopsis, optional cast and playback controls without starting video on selection. */
@Composable internal fun LandscapeContentDetails(
    model: AuroraViewModel, movie: Channel?, series: SeriesEntry?,
    onBack: ()->Unit, onEpisodes: (SeriesEntry)->Unit
) {
    val title = movie?.name ?: series?.title.orEmpty()
    var synopsis by remember(model.playlistUrl,movie?.url,series?.id) {
        mutableStateOf<Synopsis?>(null)
    }
    LaunchedEffect(model.playlistUrl,movie?.url,series?.id) {
        synopsis = model.synopsis(movie,series,includeCast=true)
    }
    val fallback = movie?.imageUrl ?: series?.imageUrl.orEmpty()
    val backdrop = synopsis?.backdropUrl?.takeIf(String::isNotBlank) ?: fallback
    val mainText=Color(0xFFF5F8FF)
    Box(Modifier.fillMaxSize().background(Color(0xFF070D17))
        .testTag("landscape_title_details")) {
        if(backdrop.isNotBlank()) {
            AsyncImage(catalogImage(backdrop),null,Modifier.fillMaxWidth().fillMaxHeight(0.9f)
                .align(Alignment.TopCenter),contentScale=ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color(0xF2080D17),Color(0xDD080D17),
                Color(0x77080D17),Color(0x22080D17)))))
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0x22080D17),Color.Transparent,Color(0xF5080D17)))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=30.dp,vertical=12.dp)) {
            IconButton(onClick=onBack,modifier=Modifier.testTag("landscape_details_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back),tint=mainText)
            }
            Spacer(Modifier.height(9.dp))
            Text(title,color=mainText,fontSize=32.sp,fontWeight=FontWeight.Bold,
                maxLines=2,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                if(!synopsis?.rating.isNullOrBlank()) {
                    Icon(Icons.Default.Star,null,tint=Color(0xFFFFCD60),modifier=Modifier.size(18.dp))
                    Text(synopsis!!.rating,color=mainText,fontSize=13.sp)
                }
                listOf(synopsis?.year,synopsis?.duration,synopsis?.genre)
                    .filterNotNull().filter(String::isNotBlank).forEach {
                        Text(it,color=Color(0xFFD9E4F2),fontSize=13.sp,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
            }
            Spacer(Modifier.height(12.dp))
            Text(when {
                synopsis==null -> tr(R.string.loading)
                synopsis!!.plot.isNotBlank() -> synopsis!!.plot
                else -> tr(R.string.synopsis_unavailable)
            }, color=Color(0xFFE1E8F1),fontSize=14.sp,lineHeight=20.sp,
                maxLines=5,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.fillMaxWidth(0.72f).testTag("landscape_details_synopsis"))
            if (!synopsis?.metadataSource.isNullOrBlank()) {
                val context = LocalContext.current
                val sourceName = synopsis!!.metadataSource
                val sourceUrl = synopsis!!.metadataUrl
                Text("Fonte da sinopse: $sourceName",
                    color=Color(0xFF80DCCF),fontSize=11.sp,
                    modifier=Modifier.padding(top=8.dp)
                        .clickable(enabled=sourceUrl.startsWith("https://")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(sourceUrl)))
                            }
                        })
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Button(onClick={
                    if(movie!=null) {
                        model.play(movie)
                        model.fullscreen=true
                    } else if(series!=null) onEpisodes(series)
                },modifier=Modifier.defaultMinSize(minWidth=156.dp).testTag("landscape_details_play"),
                    colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF55D9BB),
                        contentColor=Color(0xFF0B1922))) {
                    Icon(if(movie!=null) Icons.Default.PlayArrow else Icons.Default.List,null)
                    Spacer(Modifier.width(6.dp))
                    Text(if(movie!=null) "Reproduzir" else "Episódios",fontWeight=FontWeight.Bold)
                }
                if(movie!=null) {
                    OutlinedButton(onClick={model.toggleFavorite(movie)},
                        modifier=Modifier.testTag("landscape_details_favorite")) {
                        Icon(if(model.isFavorite(movie)) Icons.Default.Star else Icons.Default.StarBorder,
                            null,tint=Color(0xFFFFD05C))
                        Spacer(Modifier.width(6.dp))
                        Text(tr(R.string.favorites),color=mainText)
                    }
                } else if(series!=null) {
                    OutlinedButton(onClick={model.toggleSeriesFavorite(series)},
                        modifier=Modifier.testTag("landscape_details_favorite")) {
                        Icon(if(series.id in model.seriesFavorites) Icons.Default.Star else Icons.Default.StarBorder,
                            null,tint=Color(0xFFFFD05C))
                        Spacer(Modifier.width(6.dp))
                        Text(tr(R.string.favorites),color=mainText)
                    }
                }
            }
            val actors=synopsis?.cast.orEmpty()
            Spacer(Modifier.height(22.dp))
            if(actors.isNotEmpty()) {
                Text("Elenco",color=mainText,fontSize=19.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),
                    modifier=Modifier.fillMaxWidth().testTag("landscape_details_cast")) {
                    items(actors,key={it.name}) {actor ->
                        var portrait by remember(actor.name,actor.imageUrl) { mutableStateOf(actor.imageUrl) }
                        LaunchedEffect(actor.name,actor.imageUrl) {
                            portrait = model.actorPhoto(actor)
                        }
                        Column(Modifier.width(105.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.width(105.dp).height(116.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0xFF293A4F)),contentAlignment=Alignment.Center) {
                                Icon(Icons.Default.Person,null,tint=Color(0xFFA8BDD3),
                                    modifier=Modifier.size(43.dp))
                                if(portrait.isNotBlank())
                                    AsyncImage(actorImageRequest(portrait,actor.name),null,
                                        Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                            }
                            Text(actor.name,Modifier.padding(top=5.dp),color=mainText,
                                fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            } else if(synopsis!=null) {
                Text("Elenco não informado pelo provedor",color=Color(0xFF93A5B8),fontSize=12.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
