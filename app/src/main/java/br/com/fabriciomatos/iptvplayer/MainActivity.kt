package br.com.fabriciomatos.iptvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Bg = Color(0xFF080D19)
private val Panel = Color(0xFF131D2D)
private val Elevated = Color(0xFF1B293E)
private val Cyan = Color(0xFF56D8F9)
private val Mint = Color(0xFF51E5BE)
private val Violet = Color(0xFFA28AFF)
private val Txt = Color(0xFFF1F6FE)
private val Muted = Color(0xFF99ACC6)
private val Gold = Color(0xFFFFD378)
private val Shape = RoundedCornerShape(20.dp)

class MainActivity : ComponentActivity() {
    private lateinit var model: AuroraViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
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
        when { model.settingsOpen -> model.settingsOpen = false
            model.fullscreen -> model.fullscreen = false
            model.page != Page.HOME -> model.page = Page.HOME
            else -> model.stop() }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Cyan, tertiary = Violet,
        background = Bg, surface = Panel, onPrimary = Bg, onBackground = Txt, onSurface = Txt, outline = Elevated)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (model.fullscreen && model.current != null) {
                Box(Modifier.fillMaxSize()) {
                    VideoPane(model, Modifier.fillMaxSize(), fullscreen = true)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                    if (model.page == Page.HOME) Home(model, wide) else Library(model, wide)
                }
            }
            if (model.settingsOpen) PlaylistSettings(model)
        }
    }
}

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Image(painterResource(R.mipmap.aurora_launcher), "Logo do AURORA IPTV", modifier.clip(RoundedCornerShape(16.dp)))
}

@Composable
private fun BrandHeader(model: AuroraViewModel, wide: Boolean) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60000) } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (wide) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(Modifier.size(if (wide) 50.dp else 54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("AURORA IPTV", fontWeight = FontWeight.Black, fontSize = if (wide) 19.sp else 21.sp, letterSpacing = 1.sp)
            Text(if (model.loading) "Atualizando sua lista…" else if (model.playlistUrl.isBlank()) "Seu conteúdo, do seu jeito" else model.playlistName,
                color = if (model.loading) Mint else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide) {
            Column(horizontalAlignment = Alignment.End) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text(SimpleDateFormat("EEE, dd MMM", Locale("pt", "BR")).format(Date(now)), fontSize = 11.sp, color = Muted)
            }
        } else IconButton(onClick = { model.settingsOpen = true }) { Icon(Icons.Default.Settings, "Configurações", tint = Muted) }
    }
}

@Composable
private fun Home(model: AuroraViewModel, wide: Boolean) {
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF102A35), Bg, Color(0xFF19142E))))) {
        BrandHeader(model, wide)
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Mint, trackColor = Panel)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryCard(MediaKind.LIVE.title, "Assista aos seus canais", Icons.Default.LiveTv, listOf(Cyan, Mint),
                    model.catalog.byKind[MediaKind.LIVE].orEmpty().size, Modifier.weight(0.9f).fillMaxHeight(), large = true) { model.browse(MediaKind.LIVE) }
                Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryCard("Filmes", "Seu cinema, sempre perto", Icons.Default.Movie, listOf(Violet, Color(0xFF746EE8)),
                        model.catalog.byKind[MediaKind.MOVIES].orEmpty().size, Modifier.weight(1f).fillMaxWidth()) { model.browse(MediaKind.MOVIES) }
                    CategoryCard("Séries", "Escolha o próximo episódio", Icons.Default.VideoLibrary, listOf(Mint, Cyan),
                        model.catalog.byKind[MediaKind.SERIES].orEmpty().size, Modifier.weight(1f).fillMaxWidth()) { model.browse(MediaKind.SERIES) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("Favoritos", Icons.Default.Star, Modifier.weight(1f)) { model.browse(null, true) }
                        SmallAction("Minha playlist", Icons.AutoMirrored.Filled.List, Modifier.weight(1f)) { model.settingsOpen = true }
                    }
                }
                RecentCard(model, Modifier.weight(1f).fillMaxHeight())
            }
            model.message?.let { Text(it, color = Gold, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp)) }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Logo(Modifier.size(90.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("O que vamos assistir?", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(if (model.catalog.channels.isEmpty()) "Cadastre sua playlist para começar" else "${model.catalog.channels.size} conteúdos disponíveis",
                            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item { CategoryCard("Ao vivo", "Canais em tempo real", Icons.Default.LiveTv, listOf(Cyan, Mint), model.catalog.byKind[MediaKind.LIVE].orEmpty().size, Modifier.fillMaxWidth().height(94.dp)) { model.browse(MediaKind.LIVE) } }
                item { CategoryCard("Filmes", "Seu cinema, sempre perto", Icons.Default.Movie, listOf(Violet, Color(0xFF746EE8)), model.catalog.byKind[MediaKind.MOVIES].orEmpty().size, Modifier.fillMaxWidth().height(94.dp)) { model.browse(MediaKind.MOVIES) } }
                item { CategoryCard("Séries", "Escolha o próximo episódio", Icons.Default.VideoLibrary, listOf(Mint, Cyan), model.catalog.byKind[MediaKind.SERIES].orEmpty().size, Modifier.fillMaxWidth().height(94.dp)) { model.browse(MediaKind.SERIES) } }
                item { PlaylistSummary(model) }
                if (model.current != null || model.recent != null) item { RecentCard(model, Modifier.fillMaxWidth().height(130.dp)) }
                model.message?.let { message -> item { Text(message, fontSize = 12.sp, color = Gold) } }
            }
        }
        HomeNavigation(model)
    }
}

@Composable
private fun CategoryCard(title: String, subtitle: String, icon: ImageVector, colors: List<Color>, count: Int, modifier: Modifier, large: Boolean = false, onClick: () -> Unit) {
    val card = modifier.clip(Shape).background(Brush.linearGradient(colors.map { it.copy(alpha = 0.23f) }))
        .border(1.dp, colors.first().copy(alpha = 0.32f), Shape).clickable(onClick = onClick).padding(18.dp)
    if (large) Column(card, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = colors.first(), modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 25.sp)
        Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        Text("$count canais", color = Mint, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
    } else Row(card, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.first().copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.first(), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(subtitle, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = colors.first())
    }
}

@Composable
private fun SmallAction(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Panel).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistSummary(model: AuroraViewModel) {
    Row(Modifier.fillMaxWidth().clip(Shape).background(Panel).clickable { model.settingsOpen = true }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.List, null, tint = Mint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Minha playlist", fontWeight = FontWeight.SemiBold)
            Text(if (model.playlistUrl.isBlank()) "Cadastrar endereço M3U" else "Endereço salvo neste aparelho", color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun RecentCard(model: AuroraViewModel, modifier: Modifier) {
    val channel = model.current ?: model.recent
    Box(modifier.clip(Shape).background(Panel).clickable {
        if (channel == null) model.settingsOpen = true else model.play(channel)
    }) {
        if (channel?.imageUrl?.isNotBlank() == true) AsyncImage(channel.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.98f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Icon(if (channel == null) Icons.Default.PlayCircle else Icons.Default.PlayArrow, null, tint = Mint, modifier = Modifier.size(30.dp))
            Text(if (channel == null) "Tudo pronto para assistir" else if (model.current != null) "Em reprodução" else "Último conteúdo", fontSize = 11.sp, color = Mint)
            Text(channel?.name ?: "Adicione sua M3U", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            Text(channel?.group ?: "Sua lista fica salva aqui", fontSize = 11.sp, color = Muted, maxLines = 1)
        }
    }
}

@Composable
private fun HomeNavigation(model: AuroraViewModel) {
    Row(Modifier.fillMaxWidth().background(Bg.copy(alpha = 0.7f)).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        NavigationItem("Início", Icons.Default.Home, true) { model.page = Page.HOME }
        NavigationItem("Buscar", Icons.Default.Search) { model.browse(null) }
        NavigationItem("Favoritos", Icons.Default.Star) { model.browse(null, true) }
        NavigationItem("Atualizar", Icons.Default.Refresh) { model.refresh() }
        NavigationItem("Playlist", Icons.AutoMirrored.Filled.List) { model.settingsOpen = true }
    }
}

@Composable
private fun NavigationItem(title: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 48.dp).heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, title, tint = if (selected) Mint else Muted, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 10.sp, color = if (selected) Mint else Muted)
    }
}

@Composable
private fun Library(model: AuroraViewModel, wide: Boolean) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(model, Modifier.width(102.dp).fillMaxHeight())
            VerticalDivider(color = Elevated)
            GroupPanel(model, Modifier.widthIn(min = 135.dp, max = 190.dp).weight(0.7f).fillMaxHeight())
            VerticalDivider(color = Elevated)
            Column(Modifier.weight(2.4f).fillMaxHeight().padding(horizontal = 12.dp)) {
                LibraryToolbar(model, compact = true)
                if (model.current != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VideoPane(model, Modifier.weight(1.7f).fillMaxHeight())
                        CurrentProgramme(model, Modifier.weight(1f).fillMaxHeight())
                    }
                    Spacer(Modifier.height(8.dp))
                    GuideHeading()
                    ChannelList(model, Modifier.weight(1.15f).fillMaxWidth(), guideMode = true)
                } else {
                    SearchBox(model)
                    if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                    ChannelList(model, Modifier.weight(1f).fillMaxWidth(), guideMode = true)
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
                    IconButton(onClick = { model.stop() }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Fechar vídeo", tint = Muted) }
                }
            }
            SearchBox(model, Modifier.padding(horizontal = 16.dp))
            TypeChips(model)
            val groups = remember(model.catalog, model.selectedKind, model.favoritesOnly) { model.groups() }
            if (groups.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { GroupChip("Todos", model.group == null) { model.selectGroup(null) } }
                items(groups, key = { it }) { group -> GroupChip(group, model.group == group) { model.selectGroup(group) } }
            }
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Mint)
            ChannelList(model, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), guideMode = model.current != null)
        }
    }
}

@Composable
private fun LibraryToolbar(model: AuroraViewModel, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = if (compact) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) IconButton(onClick = { model.page = Page.HOME }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar ao início") }
        Column(Modifier.weight(1f)) {
            Text(if (model.favoritesOnly) "Favoritos" else model.selectedKind?.title ?: "Todos os conteúdos", fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 19.sp)
            Text(if (model.visible.size == 1) "1 item" else "${model.visible.size} itens", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = { model.refresh() }) { Icon(Icons.Default.Refresh, "Atualizar lista", tint = Cyan, modifier = Modifier.size(22.dp)) }
        if (!compact) IconButton(onClick = { model.settingsOpen = true }) { Icon(Icons.Default.Settings, "Minha playlist", tint = Muted) }
    }
}

@Composable
private fun NavigationRail(model: AuroraViewModel, modifier: Modifier) {
    LazyColumn(modifier.background(Panel.copy(alpha = 0.5f)), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Logo(Modifier.size(48.dp)) } }
        item { RailItem("Início", Icons.Default.Home, false) { model.page = Page.HOME } }
        item { RailItem("Buscar", Icons.Default.Search, model.selectedKind == null && !model.favoritesOnly) { model.browse(null) } }
        item { RailItem("Ao vivo", Icons.Default.LiveTv, model.selectedKind == MediaKind.LIVE && !model.favoritesOnly) { model.browse(MediaKind.LIVE) } }
        item { RailItem("Filmes", Icons.Default.Movie, model.selectedKind == MediaKind.MOVIES && !model.favoritesOnly) { model.browse(MediaKind.MOVIES) } }
        item { RailItem("Séries", Icons.Default.VideoLibrary, model.selectedKind == MediaKind.SERIES && !model.favoritesOnly) { model.browse(MediaKind.SERIES) } }
        item { RailItem("Favoritos", Icons.Default.Star, model.favoritesOnly) { model.browse(null, true) } }
        item { RailItem("Playlist", Icons.Default.Settings, false) { model.settingsOpen = true } }
    }
}

@Composable
private fun RailItem(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) Violet.copy(alpha = 0.2f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) Mint else Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, color = if (selected) Txt else Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun GroupPanel(model: AuroraViewModel, modifier: Modifier) {
    val groups = remember(model.catalog, model.selectedKind, model.favoritesOnly) { model.groups() }
    LazyColumn(modifier, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("CATEGORIAS", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 10.dp)) }
        item { GroupRow("Todos", model.group == null) { model.selectGroup(null) } }
        items(groups, key = { it }) { category -> GroupRow(category, model.group == category) { model.selectGroup(category) } }
    }
}

@Composable
private fun GroupRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(title, color = if (selected) Mint else Txt, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Elevated else Color.Transparent).clickable(onClick = onClick).padding(10.dp))
}

@Composable
private fun SearchBox(model: AuroraViewModel, modifier: Modifier = Modifier) {
    OutlinedTextField(value = model.search, onValueChange = model::search, modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Buscar canal, filme ou série", fontSize = 13.sp, color = Muted) },
        leadingIcon = { Icon(Icons.Default.Search, "Buscar", tint = Muted) },
        trailingIcon = { if (model.search.isNotEmpty()) IconButton(onClick = { model.search("") }) { Icon(Icons.Default.Close, "Limpar busca") } },
        singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
}

@Composable
private fun TypeChips(model: AuroraViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { GroupChip("Todos", model.selectedKind == null && !model.favoritesOnly) { model.browse(null) } }
        items(listOf(MediaKind.LIVE, MediaKind.MOVIES, MediaKind.SERIES)) { kind ->
            GroupChip(kind.title, model.selectedKind == kind && !model.favoritesOnly) { model.browse(kind) }
        }
        item { GroupChip("★ Favoritos", model.favoritesOnly) { model.browse(null, true) } }
    }
}

@Composable
private fun GroupChip(title: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint.copy(alpha = 0.18f), selectedLabelColor = Mint, labelColor = Muted))
}

@Composable
private fun GuideHeading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CANAIS", fontSize = 9.sp, color = Muted, modifier = Modifier.weight(1.2f))
        Text("AGORA", fontSize = 9.sp, color = Mint, modifier = Modifier.weight(1f))
        Text("A SEGUIR", fontSize = 9.sp, color = Muted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChannelList(model: AuroraViewModel, modifier: Modifier, guideMode: Boolean) {
    val now = guideClock()
    val scroll = rememberLazyListState()
    LaunchedEffect(model.selectedKind, model.favoritesOnly, model.group, model.search) { scroll.scrollToItem(0) }
    if (model.visible.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (model.favoritesOnly) Icons.Default.StarBorder else Icons.Default.LiveTv, null, tint = Muted, modifier = Modifier.size(36.dp))
                Text(when { !model.initialized || model.loading -> "Preparando sua lista…"
                    model.catalog.channels.isEmpty() -> "Cadastre sua playlist M3U"
                    model.favoritesOnly -> "Marque seus conteúdos com a estrela"
                    else -> "Nenhum conteúdo encontrado" }, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp)
                if (model.catalog.channels.isEmpty() && model.initialized) TextButton(onClick = { model.settingsOpen = true }) { Text("Minha playlist") }
                model.message?.let { Text(it, fontSize = 11.sp, color = Gold) }
            }
        }
        return
    }
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Parser/cache de-duplicate URLs, so lazy keys are stable even for repeated provider entries.
        items(model.visible, key = { it.url }, contentType = { it.kind }) { channel ->
            ChannelRow(channel, model, guideMode, now)
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, model: AuroraViewModel, guideMode: Boolean, now: Long) {
    val selected = channel.url == model.current?.url
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Mint.copy(alpha = 0.12f) else Panel)
        .clickable { model.play(channel) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val isLive = channel.kind == MediaKind.LIVE
        Box(Modifier.size(if (guideMode || isLive) 38.dp else 52.dp, if (guideMode || isLive) 38.dp else 68.dp)
            .clip(RoundedCornerShape(8.dp)).background(Elevated), contentAlignment = Alignment.Center) {
            Icon(if (isLive) Icons.Default.LiveTv else Icons.Default.Movie, null, tint = Cyan, modifier = Modifier.size(20.dp))
            if (channel.imageUrl.isNotBlank()) AsyncImage(channel.imageUrl, null, Modifier.fillMaxSize(), contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(if (guideMode) 1.2f else 1f)) {
            Text(channel.name, fontSize = if (guideMode) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (selected) Mint else Txt)
            if (!guideMode) Text(channel.group, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (guideMode) {
            val shows = model.guide.forChannel(channel)
            val present = shows.firstOrNull { it.start <= now && it.end > now }
            val next = shows.firstOrNull { it.start > now }
            ProgrammeCell(present, Modifier.weight(1f), if (selected) Mint else Txt)
            ProgrammeCell(next, Modifier.weight(1f), Muted)
        }
        IconButton(onClick = { model.toggleFavorite(channel) }, modifier = Modifier.size(48.dp)) {
            Icon(if (channel.url in model.favorites) Icons.Default.Star else Icons.Default.StarBorder,
                if (channel.url in model.favorites) "Remover dos favoritos" else "Adicionar aos favoritos",
                tint = if (channel.url in model.favorites) Gold else Muted, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ProgrammeCell(programme: Programme?, modifier: Modifier, color: Color) {
    Column(modifier.padding(horizontal = 5.dp)) {
        Text(programme?.title ?: "Sem programação", fontSize = 10.sp, color = if (programme == null) Muted else color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (programme != null) Text(time(programme.start), fontSize = 9.sp, color = Muted)
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
        item { Text(show?.title ?: "Programação não informada pela lista", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp)) }
    }
}

private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))

@Composable
private fun guideClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60000); now = System.currentTimeMillis() } }
    return now
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoPane(model: AuroraViewModel, modifier: Modifier, fullscreen: Boolean = false) {
    val player = model.player
    Box(modifier.clip(RoundedCornerShape(if (fullscreen) 0.dp else 12.dp)).background(Color.Black)) {
        if (player != null) AndroidView(
            factory = { context -> PlayerView(context).apply {
                this.player = player
                keepScreenOn = true
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                controllerShowTimeoutMs = 3500
            } },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null; view.keepScreenOn = false },
            modifier = Modifier.fillMaxSize()
        )
        if (model.buffering && model.playbackError == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Mint, strokeWidth = 2.dp)
        model.playbackError?.let { error ->
            Column(Modifier.align(Alignment.Center).background(Bg.copy(alpha = 0.88f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, fontSize = 12.sp, maxLines = 3)
                TextButton(onClick = model::retry) { Text("Tentar novamente", color = Mint) }
            }
        }
        IconButton(onClick = { model.fullscreen = !model.fullscreen }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fullscreen) "Sair da tela cheia" else "Tela cheia", tint = Txt)
        }
        if (fullscreen) IconButton(onClick = { model.fullscreen = false }, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Txt)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSettings(model: AuroraViewModel) {
    var url by rememberSaveable(model.playlistUrl) { mutableStateOf(model.playlistUrl) }
    var name by rememberSaveable(model.playlistName) { mutableStateOf(model.playlistName) }
    var reveal by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { model.settingsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel, contentColor = Txt) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(Modifier.size(48.dp)); Spacer(Modifier.width(12.dp))
                    Column { Text("Minha playlist", fontWeight = FontWeight.Bold, fontSize = 23.sp); Text("Cadastre uma vez. Assista quando quiser.", color = Muted, fontSize = 12.sp) }
                }
            }
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome da lista") }, singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item {
                OutlinedTextField(url, { url = it; invalid = false }, Modifier.fillMaxWidth(), label = { Text("Endereço M3U / M3U8") },
                    placeholder = { Text("https://servidor.com/lista.m3u") }, singleLine = true, isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) "Ocultar endereço" else "Mostrar endereço") } },
                    supportingText = { Text(if (invalid) "Informe um endereço HTTP ou HTTPS válido." else "O endereço e a lista ficam salvos neste aparelho.", color = if (invalid) Gold else Muted) })
            }
            item {
                Button(onClick = {
                    invalid = !PlaylistRepository.validUrl(url.trim())
                    if (!invalid) model.savePlaylist(url, name)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp), enabled = url.isNotBlank()) {
                    Icon(Icons.Default.Save, null, Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Text("Salvar playlist", fontWeight = FontWeight.Bold)
                }
            }
            if (model.playlistUrl.isNotBlank()) item {
                Text("${model.catalog.channels.size} conteúdos salvos" + if (model.updated > 0) " • Atualização: ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(model.updated))}" else "",
                    color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = { model.settingsOpen = false; model.refresh(force = true) }, enabled = !model.loading, modifier = Modifier.fillMaxWidth()) { Text("Atualizar lista agora") }
            }
        }
    }
}
