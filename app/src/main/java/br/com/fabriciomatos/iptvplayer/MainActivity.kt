package br.com.fabriciomatos.iptvplayer

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.compose.itemContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Bg: Color @Composable get() = LocalAuroraColors.current.background
private val Panel: Color @Composable get() = LocalAuroraColors.current.panel
private val Elevated: Color @Composable get() = LocalAuroraColors.current.elevated
private val Cyan: Color @Composable get() = LocalAuroraColors.current.cyan
private val Mint: Color @Composable get() = LocalAuroraColors.current.mint
private val Violet: Color @Composable get() = LocalAuroraColors.current.violet
private val Txt: Color @Composable get() = LocalAuroraColors.current.text
private val Muted: Color @Composable get() = LocalAuroraColors.current.muted
private val Gold: Color @Composable get() = LocalAuroraColors.current.gold
private val Shape = RoundedCornerShape(20.dp)

class MainActivity : AppCompatActivity() {
    private lateinit var model: AuroraViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        val tag=AppSettings(this).language()
        if(android.os.Build.VERSION.SDK_INT>=33) {
            val manager=getSystemService(android.app.LocaleManager::class.java)
            val locales=android.os.LocaleList.forLanguageTags(tag)
            if(manager.applicationLocales!=locales)manager.applicationLocales=locales
        } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 25)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 25)
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        model = ViewModelProvider(this)[AuroraViewModel::class.java]
        setContent { AuroraApp(model) }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.foreground(true) }
    override fun onStop() { if (::model.isInitialized && !isChangingConfigurations) model.foreground(false); super.onStop() }
}

@Composable
internal fun AuroraApp(model: AuroraViewModel) {
    val context = LocalContext.current
    DisposableEffect(model.fullscreen) {
        val window = (context as? ComponentActivity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (model.fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = model.settingsOpen || model.fullscreen || model.page != Page.HOME || model.current != null) {
        when { model.settingsOpen -> if(model.settingsSection!=0)model.settingsSection=0 else model.settingsOpen = false
            model.fullscreen -> model.fullscreen = false
            model.page == Page.LIBRARY -> model.backFromLibrary()
            model.page != Page.HOME -> model.page = Page.HOME
            else -> model.stop() }
    }
    AuroraTheme(model.appearance) { dark ->
        val barColor=Bg.toArgb()
        SideEffect {
            (context as? ComponentActivity)?.window?.let { window ->
                @Suppress("DEPRECATION")
                window.statusBarColor=if(model.fullscreen)android.graphics.Color.BLACK else barColor
                @Suppress("DEPRECATION")
                window.navigationBarColor=if(model.fullscreen)android.graphics.Color.BLACK else barColor
                WindowInsetsControllerCompat(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!dark && !model.fullscreen
                    isAppearanceLightNavigationBars=!dark && !model.fullscreen
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (model.fullscreen && model.current != null) {
                Box(Modifier.fillMaxSize()) {
                    VideoPane(model, Modifier.fillMaxSize(), fullscreen = true)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                    when(model.page){Page.HOME->Home(model,wide);Page.FOOTBALL->FootballScreen(model);Page.LIBRARY->Library(model,wide)}
                }
            }
            if (model.settingsOpen) SettingsPanel(model)
            if(model.parentalDialog)key(model.parentalRequestId){ParentalDialog(model)}
            // Do not interrupt fullscreen playback, parental confirmation or other settings.
            if (!model.settingsOpen && !model.parentalDialog && !model.fullscreen && model.current == null) {
                StartupAppUpdateDialog(model.onlineUpdate) { model.openSettings(6) }
            }
        }
    }
}

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    AsyncImage(R.drawable.aurora_brand, tr(R.string.logo),
        modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
        contentScale = ContentScale.Fit)
}

@Composable
private fun BrandHeader(model: AuroraViewModel, wide: Boolean) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60000) } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (wide) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(Modifier.size(if (wide) 54.dp else 60.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("AURORA IPTV", fontWeight = FontWeight.Black, fontSize = if (wide) 19.sp else 21.sp, letterSpacing = 1.sp)
            Text(if (model.updating) model.progressText ?: tr(R.string.loading) else if (model.playlistUrl.isBlank()) tr(R.string.your_content) else model.progressText ?: model.playlistName,
                modifier=Modifier.testTag("catalog_progress_text"), color = if (model.updating) Mint else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide) {
            Column(horizontalAlignment = Alignment.End) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text(SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date(now)), fontSize = 11.sp, color = Muted)
            }
        }
        IconButton(onClick = { model.openSettings() },modifier=Modifier.landscapeFocusRing().testTag("settings_gear")) { Icon(Icons.Default.Settings, tr(R.string.settings), tint = Muted) }
    }
}

@Composable
private fun Home(model: AuroraViewModel, wide: Boolean) {
    val aurora=LocalAuroraColors.current
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(aurora.glow, Bg, aurora.sky)))) {
        BrandHeader(model, wide)
        if (model.updating) CatalogLoadingIndicator(model)
        if (wide) {
            // Projectors and phones in landscape can have far less vertical space than a tablet.
            // Keep the spacious three-column dashboard only when both dimensions allow it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compactLandscape = maxWidth < 980.dp || maxHeight < 470.dp
                if (compactLandscape) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("home_landscape_compact"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            RecentCard(model, Modifier.fillMaxWidth().height(136.dp)
                                .testTag("home_recent"))
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CategoryCard(tr(R.string.live), tr(R.string.live), Icons.Default.LiveTv, listOf(Cyan, Mint),
                                    model.catalog.live, Modifier.weight(1f).heightIn(min = 104.dp).testTag("home_live")) { model.browse(MediaKind.LIVE) }
                                CategoryCard(tr(R.string.movies), tr(R.string.movies), Icons.Default.Movie, listOf(Violet, Cyan),
                                    model.catalog.movies, Modifier.weight(1f).heightIn(min = 104.dp).testTag("home_movies")) { model.browse(MediaKind.MOVIES) }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CategoryCard(tr(R.string.series), tr(R.string.series), Icons.Default.VideoLibrary, listOf(Mint, Cyan),
                                    model.catalog.series, Modifier.weight(1f).heightIn(min = 104.dp).testTag("home_series")) { model.browse(MediaKind.SERIES) }
                                FootballSummary(model, Modifier.weight(1f).heightIn(min = 104.dp).testTag("home_football"))
                            }
                        }
                        item { HomeTotal(model, Modifier.height(28.dp)) }
                        model.message?.let { message ->
                            item { Text(message, color = Gold, fontSize = 11.sp, maxLines = 2) }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecentCard(model, Modifier.weight(1f).fillMaxHeight()
                            .testTag("home_recent"))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CategoryCard(tr(R.string.live), tr(R.string.live), Icons.Default.LiveTv, listOf(Cyan, Mint),
                                model.catalog.live, Modifier.weight(1f).fillMaxWidth().testTag("home_live")) { model.browse(MediaKind.LIVE) }
                            CategoryCard(tr(R.string.movies), tr(R.string.movies), Icons.Default.Movie, listOf(Violet, Cyan),
                                model.catalog.movies, Modifier.weight(1f).fillMaxWidth().testTag("home_movies")) { model.browse(MediaKind.MOVIES) }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CategoryCard(tr(R.string.series), tr(R.string.series), Icons.Default.VideoLibrary, listOf(Mint, Cyan),
                                model.catalog.series, Modifier.weight(1f).fillMaxWidth().testTag("home_series")) { model.browse(MediaKind.SERIES) }
                            FootballSummary(model, Modifier.weight(1f).fillMaxWidth().testTag("home_football"))
                            HomeTotal(model)
                        }
                    }
                }
            }

        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val scale=LocalDensity.current.fontScale
                val gap=8.dp
                val totalHeight=(26*scale).dp
                val recentHeight=(maxHeight*.24f).coerceIn(92.dp,148.dp)
                val messageHeight=if(model.message!=null)(40*scale).dp else 0.dp
                val minimumCard=(68*scale.coerceAtLeast(1f)).dp
                val cardHeight=((maxHeight-recentHeight-totalHeight-messageHeight-24.dp-gap*5)/4)
                    .coerceIn(minimumCard,112.dp.coerceAtLeast(minimumCard))
                val fits=recentHeight+totalHeight+messageHeight+cardHeight*4+24.dp+gap*5<=maxHeight
                LazyColumn(Modifier.fillMaxSize().testTag("home_content"),userScrollEnabled=!fits,
                    contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(gap)) {
                    item {RecentCard(model,Modifier.fillMaxWidth().height(recentHeight).testTag("home_recent"))}
                    item {CategoryCard(tr(R.string.live),tr(R.string.live),Icons.Default.LiveTv,listOf(Cyan,Mint),model.catalog.live,
                        Modifier.fillMaxWidth().height(cardHeight).testTag("home_live")) {model.browse(MediaKind.LIVE)}}
                    item {CategoryCard(tr(R.string.movies),tr(R.string.movies),Icons.Default.Movie,listOf(Violet,Cyan),model.catalog.movies,
                        Modifier.fillMaxWidth().height(cardHeight).testTag("home_movies")) {model.browse(MediaKind.MOVIES)}}
                    item {CategoryCard(tr(R.string.series),tr(R.string.series),Icons.Default.VideoLibrary,listOf(Mint,Cyan),model.catalog.series,
                        Modifier.fillMaxWidth().height(cardHeight).testTag("home_series")) {model.browse(MediaKind.SERIES)}}
                    item {FootballSummary(model,Modifier.fillMaxWidth().height(cardHeight).testTag("home_football"))}
                    item {HomeTotal(model,Modifier.height(totalHeight))}
                    model.message?.let {message->item {Text(message,fontSize=12.sp,color=Gold,maxLines=2)}}
                }
            }
        }
        HomeNavigation(model)
    }
}

@Composable private fun HomeTotal(model: AuroraViewModel,modifier: Modifier=Modifier) {
    Box(modifier.fillMaxWidth().testTag("home_total"),contentAlignment=Alignment.Center) {
        Text(when {
            model.catalog.total>0 -> tr(R.string.total_count,formatCount(model.catalog.total))
            model.loading || !model.initialized -> model.progressText ?: tr(R.string.loading)
            model.playlistUrl.isNotBlank() -> tr(R.string.saved_text)
            else -> tr(R.string.playlist_add)
        },fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun CategoryCard(title: String, subtitle: String, icon: ImageVector, colors: List<Color>, count: Int, modifier: Modifier, large: Boolean = false, onClick: () -> Unit) {
    val card = modifier.clip(Shape).background(Brush.linearGradient(colors.map { it.copy(alpha = 0.23f) }))
        .border(1.dp, colors.first().copy(alpha = 0.32f), Shape).landscapeRemoteClickable(onClick).padding(horizontal=16.dp,vertical=10.dp)
    if (large) Column(card, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = colors.first(), modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 25.sp)
        Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        Text(if (count == 1) tr(R.string.one_channel) else tr(R.string.channel_count,formatCount(count)), color = Mint, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
    } else Row(card, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.first().copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.first(), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(if (count > 0) when (title) {
                tr(R.string.series) -> if (count == 1) tr(R.string.one_series) else tr(R.string.series_count,formatCount(count))
                tr(R.string.movies) -> if (count == 1) tr(R.string.one_movie) else tr(R.string.movie_count,formatCount(count))
                else -> if (count == 1) tr(R.string.one_channel) else tr(R.string.channel_count,formatCount(count))
            } else subtitle, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = colors.first())
    }
}

@Composable
private fun SmallAction(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Panel).landscapeRemoteClickable(onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FootballSummary(model: AuroraViewModel,modifier: Modifier=Modifier) {
    Row(modifier.clip(Shape).background(Panel).landscapeRemoteClickable { model.showFootball() }.padding(horizontal=16.dp,vertical=10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.EmojiEvents, null, tint = Mint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.sports_hub), fontWeight = FontWeight.SemiBold)
            Text(tr(R.string.sports_hub_help), color = Muted, fontSize = 12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun RecentCard(model: AuroraViewModel, modifier: Modifier) {
    val channel = (model.current ?: model.recent)?.takeIf { !it.adult || model.adultUnlocked }
    val saved = model.playlistUrl.isNotBlank()
    if(model.current!=null && channel!=null) {
        Box(modifier.clip(Shape)) {
            VideoPane(model,Modifier.fillMaxSize())
            TextButton(onClick={model.page=Page.LIBRARY},modifier=Modifier.align(Alignment.TopStart).padding(end=56.dp)) {
                Text(channel.name,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
            }
        }
        return
    }
    Box(modifier.clip(Shape).background(Panel).landscapeRemoteClickable {
        when { channel != null -> model.play(channel); saved -> model.refresh(); else -> model.openSettings() }
    }) {
        val cover = channel?.let { if(it.kind==MediaKind.SERIES)it.seriesImageUrl.ifBlank {it.imageUrl}else it.imageUrl }
        if (!cover.isNullOrBlank()) AsyncImage(catalogImage(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val photo=channel!=null
        val accent=if(photo)Color(0xFF51E5BE)else Mint
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,
            (if(photo)Color(0xFF080D19)else Panel).copy(alpha = 0.98f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow,null,tint=accent,modifier=Modifier.size(20.dp))
                Text(tr(if(channel!=null)R.string.last_content else R.string.playlist_add),fontSize=11.sp,color=accent)
            }
            Text(channel?.name ?: if(saved)tr(R.string.saved_list)else tr(R.string.add_m3u),color=if(photo)Color.White else Txt,
                fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp)
            Text(channel?.group ?: tr(R.string.saved_text),fontSize=11.sp,color=if(photo)Color(0xFFC6D6E9)else Muted,maxLines=1)
        }
    }
}

@Composable
private fun HomeNavigation(model: AuroraViewModel) {
    Row(Modifier.fillMaxWidth().background(Bg.copy(alpha = 0.7f)).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        NavigationItem(tr(R.string.home), Icons.Default.Home, true) { model.page = Page.HOME }
        NavigationItem(tr(R.string.search), Icons.Default.Search) { model.browse(null) }
        NavigationItem(tr(R.string.favorites), Icons.Default.Star) { model.browse(null, true) }
        NavigationItem(tr(R.string.refresh), Icons.Default.Refresh) { model.refresh() }
        NavigationItem(tr(R.string.settings), Icons.Default.Settings) { model.openSettings() }
    }
}

@Composable
private fun NavigationItem(title: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 48.dp).heightIn(min = 52.dp).landscapeRemoteClickable(onClick).padding(vertical = 7.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, title, tint = if (selected) Mint else Muted, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 10.sp, color = if (selected) Mint else Muted)
    }
}

@Composable
private fun Library(model: AuroraViewModel, wide: Boolean) {
    val live = model.selectedKind == MediaKind.LIVE
    val series = model.openedSeries
    val seriesRoot = model.selectedKind == MediaKind.SERIES && series == null
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscapeHeight = maxHeight
            val compactLandscape = maxWidth < 920.dp || landscapeHeight < 520.dp
            // Measure actual translated labels at the SAME typography used in RailItem,
            // including user font scaling. No fixed 112/124dp width can fit every locale.
            // Width covers the widest full label, icon, gap, padding and focus outline.
            val railLabels = listOf(
                tr(R.string.home), tr(R.string.search), tr(R.string.live),
                tr(R.string.movies), tr(R.string.series),
                tr(R.string.favorites), tr(R.string.settings)
            )
            val railMeasurer = rememberTextMeasurer()
            val widestLabel = railLabels.maxOf { label ->
                railMeasurer.measure(
                    text = label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1, softWrap = false
                ).size.width
            }
            val navigationWidth = (with(LocalDensity.current) { widestLabel.toDp() } + 62.dp)
                .coerceAtLeast(146.dp)
            val categoryWidth = (maxWidth * 0.27f).coerceIn(170.dp, 224.dp)
            Row(Modifier.fillMaxSize()) {
                NavigationRail(model, Modifier.width(navigationWidth).fillMaxHeight()
                    .testTag("landscape_navigation_rail"))
                VerticalDivider(color = Elevated)
                GroupPanel(model, Modifier.width(categoryWidth).fillMaxHeight()
                    .testTag("landscape_group_panel"))
                VerticalDivider(color = Elevated)
                Column(Modifier.weight(1f).fillMaxHeight()
                    .padding(horizontal = if (compactLandscape) 8.dp else 12.dp)) {
                    LibraryToolbar(model, compact = true)
                    if (model.current != null) {
                        if (compactLandscape) {
                            // Horizontal layouts with limited height keep the catalog usable
                            // instead of squeezing playback, guide and list into tiny rows.
                            VideoPane(model, Modifier.fillMaxWidth()
                                .height((landscapeHeight * 0.35f).coerceIn(110.dp, 230.dp)))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(model.current!!.name, Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                IconButton(onClick = model::closeVideo, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Close, tr(R.string.close_video))
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                VideoPane(model, Modifier.weight(1.7f).fillMaxHeight())
                                CurrentProgramme(model, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        Spacer(Modifier.height(if (compactLandscape) 4.dp else 8.dp))
                        if (live && !compactLandscape) GuideHeading()
                        else if (series != null) SeasonChips(model, series)
                        if(model.favoritesOnly && model.selectedKind==null)
                            FavoritesList(model, Modifier.weight(1.15f).fillMaxWidth())
                        else ChannelList(model, Modifier.weight(1.15f).fillMaxWidth(),
                            guideMode = live && !compactLandscape)
                    } else {
                        if (series != null) SeriesHeader(model,series)
                        SearchBox(model)
                        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                        if (seriesRoot) SeriesGrid(model, Modifier.weight(1f).fillMaxWidth())
                        else if(model.favoritesOnly && model.selectedKind==null)
                            FavoritesList(model, Modifier.weight(1f).fillMaxWidth())
                        else ChannelList(model, Modifier.weight(1f).fillMaxWidth(), guideMode = live)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            LibraryToolbar(model)
            if (model.current != null) {
                VideoPane(model, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.current!!.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if(!live)SynopsisAction(model,model.current,series)
                    if(series!=null)SeriesFavoriteButton(model,series)
                    if(model.current?.kind==MediaKind.MOVIES)ChannelFavoriteButton(model,model.current!!,"current_favorite")
                    IconButton(onClick = { model.closeVideo() }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, tr(R.string.close_video), tint = Muted) }
                }
            }
            SearchBox(model, Modifier.padding(horizontal = 16.dp))
            TypeChips(model)
            if (series != null) {
                if (model.current == null) SeriesHeader(model,series, Modifier.padding(horizontal = 16.dp))
                SeasonChips(model, series)
            }
            val groups = remember(model.catalog, model.selectedKind, model.favoritesOnly) { model.groups() }
            if (series == null && groups.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GroupChip(tr(R.string.all), model.group == null) { model.selectGroup(null) } }
                items(groups, key = { it }) { group ->
                    val source = model.playlistUrl
                    var image by remember(source, model.catalog, group) { mutableStateOf("") }
                    LaunchedEffect(source, model.catalog, group, model.selectedKind) {
                        image = if (model.selectedKind == MediaKind.LIVE) model.groupLogo(source, group) else ""
                    }
                    GroupChip(groupDisplayName(groupLabel(group)), model.group == group,
                        image, fallbackLogoUrl=groupBrandLogoUrl(group)) { model.selectGroup(group) }
                }
            }
            if (model.loading) CatalogLoadingIndicator(model)
            if (seriesRoot) SeriesGrid(model, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
            else if(model.favoritesOnly && model.selectedKind==null && series==null)FavoritesList(model,Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp))
            else ChannelList(model, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), guideMode = live && model.current != null)
        }
    }
}

@Composable
private fun SeriesGrid(model: AuroraViewModel, modifier: Modifier) {
    val entries = model.seriesPages.collectAsLazyPagingItems()
    val key=model.catalogPositionKey()
    val anchor=remember(key) {model.catalogAnchor(key)}
    val scroll=remember(key) {LazyGridState(anchor?.index ?: 0,anchor?.offset ?: 0)}
    var restored by remember(key) {mutableStateOf(anchor==null)}
    LaunchedEffect(key,entries) {
        restored=anchor==null
        if(anchor!=null)withTimeoutOrNull(5000) {
            while(true) {
                val index=entries.itemSnapshotList.items.indexOfFirst {it.id==anchor.item}
                if(!model.filtering && index>=0) {scroll.scrollToItem(index,anchor.offset);break}
                delay(40)
            }
        }
        restored=true
    }
    LaunchedEffect(key,entries,restored) {if(restored)snapshotFlow {
        if(model.filtering || entries.loadState.refresh is LoadState.Loading)null
        else Triple(scroll.firstVisibleItemIndex,scroll.firstVisibleItemScrollOffset,entries.itemSnapshotList.items)
    }.collectLatest {frame->frame?.let {(index,offset,items)->
        items.getOrNull(index)?.let {model.rememberCatalogAnchor(key,CatalogAnchor(it.id,offset,index))}
    }
    }}
    if (entries.itemCount == 0) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CatalogStatus(model, entries.loadState.refresh) { entries.retry() }
        }
        return
    }
    BoxWithConstraints(modifier) {
        val compact = maxWidth > maxHeight
        val landscape = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // Keep captions legible in projector mode: never pack five tiny posters
        // into a narrow content pane just because the viewport is short.
        // Preserve the existing portrait grid sizing exactly.
        val targetWidth = ((maxHeight - 82.dp).coerceAtLeast(48.dp) * (2f / 3f)).coerceAtMost(138.dp)
        val columns = when {
            landscape -> GridCells.Adaptive(if (maxWidth < 390.dp) 146.dp else 164.dp)
            compact -> GridCells.Fixed(kotlin.math.ceil((maxWidth.value + 12f) / (targetWidth.value + 12f)).toInt().coerceAtLeast(1))
            else -> GridCells.Adaptive(138.dp)
        }
        LazyVerticalGrid(columns = columns, state=scroll, modifier = Modifier.fillMaxSize().testTag("series_grid"),
            contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries.itemCount, key = entries.itemKey { it.id }) { index ->
                val series = entries[index] ?: return@items
                if(series.adult && !model.adultUnlocked)return@items
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable { model.showSeries(series) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Brush.linearGradient(listOf(Elevated, LocalAuroraColors.current.sky))),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideoLibrary, null, tint = Cyan, modifier = Modifier.size(36.dp))
                        }
                        AnimePoster(model,series,Modifier.fillMaxSize())
                        SeriesFavoriteButton(model,series,Modifier.align(Alignment.TopEnd).padding(4.dp).background(Panel.copy(alpha=.85f),RoundedCornerShape(24.dp)))
                    }
                    Column(Modifier.padding(if (compact) 8.dp else 10.dp)) {
                        Text(series.title, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 12.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${seasonCount(series.seasons.size)} • ${episodeCount(series.episodeCount)}", fontSize = if (compact) 9.sp else 10.sp, color = Muted,
                            modifier = Modifier.padding(top = 4.dp), maxLines = if (landscape) 2 else if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(model: AuroraViewModel, series: SeriesEntry, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp, 76.dp).clip(RoundedCornerShape(8.dp)).background(Elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.VideoLibrary, null, tint = Cyan)
            AnimePoster(model,series,Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(tr(R.string.select_episode), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(tr(R.string.episode_in_season,episodeCount(series.episodeCount),seasonCount(series.seasons.size)), color = Muted, fontSize = 12.sp)
            SynopsisAction(model,series=series)
        }
        SeriesFavoriteButton(model,series)
    }
}

@Composable private fun SeriesFavoriteButton(model: AuroraViewModel,series: SeriesEntry,modifier: Modifier = Modifier) {
    val selected=series.id in model.seriesFavorites
    IconButton(onClick={model.toggleSeriesFavorite(series)},modifier=modifier.size(48.dp).testTag("series_favorite_${series.id}")) {
        Icon(if(selected)Icons.Default.Star else Icons.Default.StarBorder,
            tr(if(selected)R.string.remove_favorite else R.string.add_favorite),tint=if(selected)Gold else Muted,modifier=Modifier.size(21.dp))
    }
}

@Composable private fun FavoritesList(model: AuroraViewModel,modifier: Modifier) {
    val channels=model.channelPages.collectAsLazyPagingItems()
    val shows=model.seriesPages.collectAsLazyPagingItems()
    val scroll=remember(model.catalogPositionKey()) {LazyListState()}
    if(channels.itemCount==0 && shows.itemCount==0) {
        val state=when {
            channels.loadState.refresh is LoadState.Loading || shows.loadState.refresh is LoadState.Loading->LoadState.Loading
            channels.loadState.refresh is LoadState.Error->channels.loadState.refresh
            else->shows.loadState.refresh
        }
        Box(modifier,contentAlignment=Alignment.Center) {CatalogStatus(model,state) {channels.retry();shows.retry()}}
        return
    }
    val now=guideClock()
    LazyColumn(modifier.testTag("favorites_list"),state=scroll,contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        items(shows.itemCount,key=shows.itemKey {"series:${it.id}"},contentType={"series"}) {index->
            shows[index]?.takeIf {!it.adult || model.adultUnlocked}?.let {series->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable {model.showSeries(series)}.padding(start=8.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp,68.dp).clip(RoundedCornerShape(8.dp)).background(Elevated),contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.VideoLibrary,null,tint=Cyan)
                        AnimePoster(model,series,Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(start=9.dp)) {
                        Text(series.title,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("${seasonCount(series.seasons.size)} • ${episodeCount(series.episodeCount)}",fontSize=11.sp,color=Muted)
                    }
                    SeriesFavoriteButton(model,series)
                }
            }
        }
        items(channels.itemCount,key=channels.itemKey {"channel:${it.url}"},contentType={"channel"}) {index->
            channels[index]?.takeIf {!it.adult || model.adultUnlocked}?.let {ChannelRow(it,model,false,now)}
        }
        if(shows.loadState.append is LoadState.Loading || channels.loadState.append is LoadState.Loading)item {
            Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {CircularProgressIndicator(Modifier.size(22.dp),color=Mint)}
        }
        if(shows.loadState.append is LoadState.Error || channels.loadState.append is LoadState.Error)item {
            TextButton(onClick={shows.retry();channels.retry()}) {Text(tr(R.string.retry))}
        }
    }
}

@Composable
private fun SeasonChips(model: AuroraViewModel, series: SeriesEntry) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(series.seasons, key = { it }) { season ->
            GroupChip(tr(R.string.season,season), model.selectedSeason == season) { model.selectSeason(season) }
        }
    }
}

@Composable
private fun LibraryToolbar(model: AuroraViewModel, compact: Boolean = false) {
    val series = model.openedSeries
    val seriesRoot = model.selectedKind == MediaKind.SERIES && series == null
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = if (compact) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact || series != null || model.current?.kind==MediaKind.MOVIES || model.fromFootball) IconButton(onClick = {model.backFromLibrary()}) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, if(model.fromFootball)tr(R.string.back_football)else if (series != null) tr(R.string.back_series) else if(model.current?.kind==MediaKind.MOVIES)tr(R.string.back)else tr(R.string.back_home))
        }
        Column(Modifier.weight(1f)) {
            Text(series?.title ?: if (model.favoritesOnly) tr(R.string.favorites) else model.selectedKind?.let { kindLabel(it) } ?: tr(R.string.all_content),
                fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (seriesRoot) if (model.visibleCount == 1) tr(R.string.one_series) else tr(R.string.series_count,formatCount(model.visibleCount))
                else if (series != null) episodeCount(model.visibleCount) else tr(R.string.item_count,formatCount(model.visibleCount)), color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = { model.refresh() }) { Icon(Icons.Default.Refresh, tr(R.string.refresh), tint = Cyan, modifier = Modifier.size(22.dp)) }
        if (!compact) IconButton(onClick = { model.openSettings() }) { Icon(Icons.Default.Settings, tr(R.string.playlist), tint = Muted) }
    }
}

@Composable
private fun NavigationRail(model: AuroraViewModel, modifier: Modifier) {
    // The brand belongs to the stationary rail header, not the scrollable
    // menu. Scrolling or navigating categories must never move the logo.
    Column(modifier.background(Panel.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxWidth().height(80.dp).testTag("landscape_rail_logo"),
            contentAlignment = Alignment.Center) {
            Logo(Modifier.size(54.dp))
        }
        HorizontalDivider(color = Elevated)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("landscape_rail_items"),
            contentPadding = PaddingValues(vertical = 6.dp)) {
            item { RailItem(tr(R.string.home), Icons.Default.Home, false) { model.page = Page.HOME } }
            item { RailItem(tr(R.string.search), Icons.Default.Search, model.selectedKind == null && !model.favoritesOnly) { model.browse(null) } }
            item { RailItem(tr(R.string.live), Icons.Default.LiveTv, model.selectedKind == MediaKind.LIVE && !model.favoritesOnly) { model.browse(MediaKind.LIVE) } }
            item { RailItem(tr(R.string.movies), Icons.Default.Movie, model.selectedKind == MediaKind.MOVIES && !model.favoritesOnly) { model.browse(MediaKind.MOVIES) } }
            item { RailItem(tr(R.string.series), Icons.Default.VideoLibrary, model.selectedKind == MediaKind.SERIES && !model.favoritesOnly) { model.browse(MediaKind.SERIES) } }
            item { RailItem(tr(R.string.favorites), Icons.Default.Star, model.favoritesOnly) { model.browse(null, true) } }
            item { RailItem(tr(R.string.settings), Icons.Default.Settings, false,
                labelTag = "landscape_settings_label") { model.openSettings() } }
        }
    }
}

@Composable
private fun RailItem(
    title: String, icon: ImageVector, selected: Boolean,
    labelTag: String? = null, onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .background(if (selected) Violet.copy(alpha = 0.2f) else Color.Transparent)
        .landscapeRemoteClickable(onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) Mint else Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = if (selected) Txt else Muted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            lineHeight = 17.sp, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = labelTag?.let { Modifier.testTag(it) } ?: Modifier)
    }
}

@Composable
private fun GroupPanel(model: AuroraViewModel, modifier: Modifier) {
    val series = model.openedSeries
    val groups = remember(model.catalog, model.selectedKind, model.favoritesOnly) { model.groups() }
    Column(modifier) {
        Text(if (series == null) tr(R.string.categories) else tr(R.string.seasons),
            color = Muted, fontSize = 11.sp, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("landscape_categories_list"),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (series == null) {
                item { GroupRow(tr(R.string.all), model.group == null) { model.selectGroup(null) } }
                items(groups, key = { it }) { category ->
                    GroupRow(groupDisplayName(groupLabel(category)), model.group == category) { model.selectGroup(category) }
                }
            } else {
                items(series.seasons, key = { it }) { season ->
                    GroupRow(tr(R.string.season, season), model.selectedSeason == season) { model.selectSeason(season) }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(title, color = if (selected) Mint else Txt, fontSize = 13.sp,
        lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Elevated else Color.Transparent)
            .landscapeRemoteClickable(onClick).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun SearchBox(model: AuroraViewModel, modifier: Modifier = Modifier) {
    val hint = when (model.selectedKind) {
        MediaKind.LIVE -> tr(R.string.search_channel)
        MediaKind.MOVIES -> tr(R.string.search_movie)
        MediaKind.SERIES -> if (model.openedSeries == null) tr(R.string.search_series) else tr(R.string.search_episode)
        else -> tr(R.string.search_all)
    }
    OutlinedTextField(value = model.search, onValueChange = model::search, modifier = modifier.fillMaxWidth(),
        placeholder = { Text(hint, fontSize = 13.sp, color = Muted) },
        leadingIcon = { Icon(Icons.Default.Search, tr(R.string.search), tint = Muted) },
        trailingIcon = { if (model.search.isNotEmpty()) IconButton(onClick = { model.search("") }) { Icon(Icons.Default.Close, tr(R.string.clear_search)) } },
        singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
}

@Composable
private fun TypeChips(model: AuroraViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf(MediaKind.LIVE, MediaKind.MOVIES, MediaKind.SERIES)) { kind ->
            GroupChip(kindLabel(kind), model.selectedKind == kind && !model.favoritesOnly) { model.browse(kind) }
        }
        item { GroupChip(tr(R.string.favorites), model.favoritesOnly) { model.browse(null, true) } }
    }
}

@Composable
private fun GroupChip(title: String, selected: Boolean, logoUrl: String = "",
    fallbackLogoUrl: String = "", onClick: () -> Unit) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    var fallbackFailed by remember(fallbackLogoUrl) { mutableStateOf(false) }
    val displayed=if(!logoFailed)logoUrl else fallbackLogoUrl.takeIf {it!=logoUrl && !fallbackFailed}.orEmpty()
    FilterChip(selected, onClick, label = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (displayed.isNotBlank()) {
                val brand=channelBrand(title)
                val imageModifier=if(brand != null) Modifier.size(27.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if(brand.darkBackground) Color(0xFF111218) else Color.White)
                    .padding(3.dp) else Modifier.size(20.dp)
                AsyncImage(catalogImage(displayed), null, imageModifier,
                    contentScale = ContentScale.Fit,
                    onError = { if(displayed==logoUrl)logoFailed=true else fallbackFailed=true })
            }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
    }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint.copy(alpha = 0.18f),
        selectedLabelColor = Mint, labelColor = Muted))
}

@Composable
private fun GuideHeading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(R.string.channels), fontSize = 9.sp, color = Muted, modifier = Modifier.weight(1.2f))
        Text(tr(R.string.now), fontSize = 9.sp, color = Mint, modifier = Modifier.weight(1f))
        Text(tr(R.string.next), fontSize = 9.sp, color = Muted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChannelList(model: AuroraViewModel, modifier: Modifier, guideMode: Boolean) {
    val now = guideClock()
    val entries = model.channelPages.collectAsLazyPagingItems()
    val key=model.catalogPositionKey()
    val anchor=remember(key) {model.catalogAnchor(key)}
    val scroll=remember(key) {LazyListState(anchor?.index ?: 0,anchor?.offset ?: 0)}
    var restored by remember(key) {mutableStateOf(anchor==null)}
    LaunchedEffect(key,entries) {
        restored=anchor==null
        if(anchor!=null)withTimeoutOrNull(5000) {
            while(true) {
                val index=entries.itemSnapshotList.items.indexOfFirst {it.url==anchor.item}
                if(!model.filtering && index>=0) {scroll.scrollToItem(index,anchor.offset);break}
                delay(40)
            }
        }
        restored=true
    }
    LaunchedEffect(key,entries,restored) {if(restored)snapshotFlow {
        if(model.filtering || entries.loadState.refresh is LoadState.Loading)null
        else Triple(scroll.firstVisibleItemIndex,scroll.firstVisibleItemScrollOffset,entries.itemSnapshotList.items)
    }.collectLatest {frame->frame?.let {(index,offset,items)->
        items.getOrNull(index)?.let {model.rememberCatalogAnchor(key,CatalogAnchor(it.url,offset,index))}
    }
    }}
    if (entries.itemCount == 0) {
        Box(modifier, contentAlignment = Alignment.Center) { CatalogStatus(model,entries.loadState.refresh) { entries.retry() } }
        return
    }
    LazyColumn(modifier.testTag("channel_list"), state = scroll, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items(entries.itemCount, key = entries.itemKey { it.url }, contentType = entries.itemContentType { it.kind }) { index ->
            entries[index]?.takeIf { !it.adult || model.adultUnlocked }?.let { ChannelRow(it,model,guideMode,now) }
        }
        if (entries.loadState.append is LoadState.Loading) item {
            Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp),color=Mint) }
        }
        if (entries.loadState.append is LoadState.Error) item { TextButton(onClick={entries.retry()}) { Text(tr(R.string.retry)) } }
    }
}

@Composable
private fun CatalogStatus(model: AuroraViewModel, state: LoadState, retry: () -> Unit) {
    Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        if (model.loading || !model.initialized || state is LoadState.Loading) {
            CircularProgressIndicator(Modifier.size(26.dp),color=Mint)
            Text(model.progressText ?: tr(R.string.loading),Modifier.padding(top=12.dp),fontSize=13.sp)
        } else {
            Icon(Icons.Default.VideoLibrary,null,tint=Muted,modifier=Modifier.size(36.dp))
            Text(when {
                state is LoadState.Error -> tr(R.string.load_failed)
                model.catalog.total == 0 -> if(model.playlistUrl.isBlank()) tr(R.string.playlist_add) else tr(R.string.saved_text)
                model.favoritesOnly -> tr(R.string.star_hint)
                else -> tr(R.string.no_content)
            },Modifier.padding(top=8.dp),fontSize=14.sp)
            if(state is LoadState.Error) TextButton(onClick=retry) { Text(tr(R.string.retry)) }
            else if(model.catalog.total==0) TextButton(onClick={if(model.playlistUrl.isBlank())model.settingsOpen=true else model.refresh(force=true)}) { Text(if(model.playlistUrl.isBlank()) tr(R.string.playlist) else tr(R.string.refresh)) }
        }
        model.message?.let { Text(it,fontSize=11.sp,color=Gold) }
    }
}

@Composable
private fun ChannelRow(channel: Channel, model: AuroraViewModel, guideMode: Boolean, now: Long) {
    val selected = channel.url == model.current?.url
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Mint.copy(alpha = 0.12f) else Panel)
        .landscapeRemoteClickable { model.play(channel) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val isLive = channel.kind == MediaKind.LIVE
        Box(Modifier.size(if (guideMode || isLive) 38.dp else 52.dp, if (guideMode || isLive) 38.dp else 68.dp)
            .clip(RoundedCornerShape(8.dp)).background(Elevated), contentAlignment = Alignment.Center) {
            Icon(if (isLive) Icons.Default.LiveTv else Icons.Default.Movie, null, tint = Cyan, modifier = Modifier.size(20.dp))
            val cover = if (channel.kind == MediaKind.SERIES) channel.seriesImageUrl.ifBlank { channel.imageUrl } else channel.imageUrl
            // Respect a provider's channel logo, but recover a known broadcaster logo
            // when the provider did not supply one or its URL can no longer load.
            var coverFailed by remember(cover) { mutableStateOf(false) }
            val brand=if(isLive)channelBrand(channel.name) else null
            val logo=if(cover.isNotBlank() && !coverFailed) cover else brand?.logoUrl.orEmpty()
            if (logo.isNotBlank()) {
                val logoModifier=if(brand!=null && (cover.isBlank() || coverFailed))
                    Modifier.fillMaxSize().background(if(brand.darkBackground) Color(0xFF111218) else Color.White)
                        .padding(4.dp) else Modifier.fillMaxSize()
                AsyncImage(catalogImage(logo), null, logoModifier,
                    contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                    onError = { if(logo==cover) coverFailed=true })
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(if (guideMode) 1.2f else 1f)) {
            Text(channel.name, fontSize = if (guideMode) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) Mint else Txt)
            if (!guideMode) Text(if (channel.kind == MediaKind.SERIES) Episode(channel, channel.seasonNumber.coerceAtLeast(1), channel.episodeNumber, channel.name).label else groupDisplayName(groupLabel(if(channel.kind==MediaKind.MOVIES)movieCategory(channel.group)else channel.group)),
                fontSize = 11.sp, color = if (channel.kind == MediaKind.SERIES) Cyan else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (guideMode) {
            val shows = model.guide.forChannel(channel)
            val present = shows.firstOrNull { it.start <= now && it.end > now }
            val next = shows.firstOrNull { it.start > now }
            ProgrammeCell(present, Modifier.weight(1f), if (selected) Mint else Txt)
            ProgrammeCell(next, Modifier.weight(1f), Muted)
        }
        if(!isLive)SynopsisAction(model,channel,model.openedSeries,iconOnly=true)
        if(channel.kind!=MediaKind.SERIES)ChannelFavoriteButton(model,channel)
    }
}

@Composable
private fun ProgrammeCell(programme: Programme?, modifier: Modifier, color: Color) {
    Column(modifier.padding(horizontal = 5.dp)) {
        Text(programme?.title ?: tr(R.string.no_guide), fontSize = 10.sp, color = if (programme == null) Muted else color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (programme != null) Text(time(programme.start), fontSize = 9.sp, color = Muted)
    }
}

@Composable private fun ChannelFavoriteButton(model: AuroraViewModel,channel: Channel,tag: String="favorite") {
    val selected=model.isFavorite(channel)
    IconButton(onClick={model.toggleFavorite(channel)},modifier=Modifier.size(48.dp).testTag("${tag}_${channel.url}")) {
        Icon(if(selected)Icons.Default.Star else Icons.Default.StarBorder,
            tr(if(selected)R.string.remove_favorite else R.string.add_favorite),tint=if(selected)Gold else Muted,modifier=Modifier.size(19.dp))
    }
}

@Composable
private fun CurrentProgramme(model: AuroraViewModel, modifier: Modifier) {
    val channel = model.current ?: return
    val now = guideClock()
    val show = model.guide.forChannel(channel).firstOrNull { it.start <= now && it.end > now }
    LazyColumn(modifier.clip(RoundedCornerShape(12.dp)).background(Panel), contentPadding = PaddingValues(10.dp)) {
        item { Text(channel.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2) }
        item { Text(show?.let { "${time(it.start)} – ${time(it.end)}" } ?: channel.group, color = Mint, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
        item { Text(if (channel.kind == MediaKind.LIVE) show?.title ?: tr(R.string.no_guide) else model.openedSeries?.title ?: tr(R.string.movies),
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp)) }
        if(channel.kind!=MediaKind.LIVE)item {SynopsisAction(model,channel,model.openedSeries)}
        item {if(channel.kind==MediaKind.MOVIES)ChannelFavoriteButton(model,channel,"current_favorite")
            else model.openedSeries?.let {SeriesFavoriteButton(model,it)}}
    }
}

private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))
@Composable
private fun seasonCount(value: Int) = if (value == 1) tr(R.string.one_season) else tr(R.string.season_count,formatCount(value))
@Composable
private fun episodeCount(value: Int) = if (value == 1) tr(R.string.one_episode) else tr(R.string.episode_count,formatCount(value))
@Composable
private fun formatCount(value: Int) = java.text.NumberFormat.getIntegerInstance(LocalContext.current.resources.configuration.locales[0]).format(value)

@Composable
private fun guideClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60000); now = System.currentTimeMillis() } }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSettings(model: AuroraViewModel) {
    var url by rememberSaveable(model.playlistUrl) { mutableStateOf(model.playlistUrl) }
    var name by rememberSaveable(model.playlistName) { mutableStateOf(model.playlistName) }
    var reveal by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var fileMode by rememberSaveable(model.playlistUrl) { mutableStateOf(PlaylistRepository.isLocalSource(model.playlistUrl)) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf(false) }
    var updatingLocal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            val filename = runCatching {
                context.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { selected.lastPathSegment.orEmpty() }
            if (!filename.endsWith(".m3u", true) && !filename.endsWith(".m3u8", true)) {
                fileError = true
            } else {
                fileError = false
                if (updatingLocal) model.importLocalPlaylist(selected, model.playlistName, model.playlistUrl)
                else {
                    selectedFile = selected
                    selectedFileName = filename
                    if (name.isBlank()) name = filename.substringBeforeLast('.')
                }
            }
        }
        updatingLocal = false
    }
    ModalBottomSheet(onDismissRequest = { if (!model.importingLocal) model.settingsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel, contentColor = Txt) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick={model.settingsSection=0}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,null);Text(tr(R.string.settings))} }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(Modifier.size(48.dp)); Spacer(Modifier.width(12.dp))
                    Column { Text(tr(R.string.playlist), fontWeight = FontWeight.Bold, fontSize = 23.sp); Text(tr(R.string.playlist_help), color = Muted, fontSize = 12.sp) }
                }
            }
            if(model.savedPlaylists.isNotEmpty()) {
                item {Text(tr(R.string.saved_playlists),fontWeight=FontWeight.SemiBold,color=Mint)}
                items(model.savedPlaylists,key={it.url}) {playlist->
                    val active=playlist.url==model.playlistUrl
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Elevated)
                        .testTag("playlist_${PlaylistRepository.digest(playlist.url)}")
                        .clickable(enabled=!model.switchingPlaylist && model.initialized) {if(!active)model.switchPlaylist(playlist)}
                        .padding(start=8.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=active,onClick={if(!active)model.switchPlaylist(playlist)},enabled=!model.switchingPlaylist && model.initialized)
                        Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
                            Text(playlist.name,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(tr(if(active)R.string.active_playlist else R.string.switch_playlist),fontSize=11.sp,color=Muted)
                        }
                        IconButton(onClick={url=playlist.url;name=playlist.name;fileMode=PlaylistRepository.isLocalSource(playlist.url);selectedFile=null;selectedFileName="";fileError=false;reveal=false;invalid=false},enabled=!model.switchingPlaylist && !model.importingLocal) {
                            Icon(Icons.Default.Edit,tr(R.string.edit_playlist),tint=Muted)
                        }
                    }
                }
            }
            item {OutlinedButton(onClick={url="";name="";fileMode=false;selectedFile=null;selectedFileName="";fileError=false;reveal=false;invalid=false},modifier=Modifier.fillMaxWidth().testTag("add_playlist"),enabled=!model.switchingPlaylist && !model.importingLocal) {
                Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text(tr(R.string.add_playlist))
            }}
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.list_name)) }, singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !fileMode, onClick = { fileMode = false; fileError = false },
                        label = { Text("Por URL") }, leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(18.dp)) })
                    FilterChip(selected = fileMode, onClick = { fileMode = true; invalid = false },
                        label = { Text("Por arquivo") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)) })
                }
            }
            if (!fileMode) {
                item {
                    OutlinedTextField(url, { url = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.list_address)) },
                        placeholder = { Text("https://servidor.com/lista.m3u") }, singleLine = true, isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) tr(R.string.hide_address) else tr(R.string.show_address)) } },
                        supportingText = { Text(if (invalid) tr(R.string.invalid_url) else tr(R.string.saved_text), color = if (invalid) Gold else Muted) })
                }
            } else {
                item {
                    OutlinedButton(onClick = { updatingLocal = false; filePicker.launch(arrayOf("*/*")) },
                        enabled = !model.importingLocal && !model.switchingPlaylist,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedFile == null) "Selecionar arquivo M3U/M3U8" else "Trocar arquivo")
                    }
                    if (selectedFile != null) Text(selectedFileName, color = Mint, fontSize = 12.sp)
                    if (fileError) Text("Selecione um arquivo .m3u ou .m3u8.", color = Gold, fontSize = 12.sp)
                    Text("A lista será importada para o aparelho e ficará disponível sem o arquivo original.", color = Muted, fontSize = 12.sp)
                }
            }
            item {
                Button(onClick = {
                    if (fileMode) {
                        selectedFile?.let { model.importLocalPlaylist(it, name, url.takeIf(PlaylistRepository::isLocalSource)) }
                    } else {
                        invalid = !PlaylistRepository.validUrl(url.trim())
                        if (!invalid) model.savePlaylist(url, name)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                    enabled = (if (fileMode) selectedFile != null else url.isNotBlank()) && model.initialized && !model.switchingPlaylist && !model.importingLocal) {
                    Icon(Icons.Default.Save, null, Modifier.size(20.dp)); Spacer(Modifier.width(9.dp))
                    Text(if (fileMode) "Importar playlist" else tr(R.string.save_playlist), fontWeight = FontWeight.Bold)
                }
            }
            if (model.playlistUrl.isNotBlank()) item {
                Text(tr(R.string.saved_count,formatCount(model.catalog.total)) + if (model.updated > 0) " · " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(model.updated)) else "",
                    color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    if (PlaylistRepository.isLocalSource(model.playlistUrl)) {
                        updatingLocal = true
                        filePicker.launch(arrayOf("*/*"))
                    } else {
                        model.settingsOpen = false
                        model.refresh(force = true)
                    }
                }, enabled = !model.loading && !model.importingLocal, modifier = Modifier.fillMaxWidth()) {
                    Text(if (PlaylistRepository.isLocalSource(model.playlistUrl)) "Reimportar arquivo local" else tr(R.string.refresh_now))
                }
            }
        }
    }
}
