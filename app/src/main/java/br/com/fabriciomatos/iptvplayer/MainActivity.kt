package br.com.fabriciomatos.iptvplayer

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Bg = Color(0xFF050B12)
private val Panel = Color(0xFF101B26)
private val Panel2 = Color(0xFF182A38)
private val Accent = Color(0xFF45E6C4)
private val AuroraCyan = Color(0xFF53C8FF)
private val AuroraViolet = Color(0xFF9B7BFF)
private val Muted = Color(0xFF9AAFC0)
private val Txt = Color(0xFFF2FBFF)

data class Channel(val name: String, val url: String, val group: String = "Canais") {
    val contentType: String get() = classifyContentType(name, group)
}

private fun classifyContentType(name: String, group: String): String {
    val text = "$group $name".lowercase()
    val seriesTerms = listOf("série", "series", "seriados", "temporada", "episódio", "episodio", "novela", "anime", "desenho")
    val movieTerms = listOf("filme", "filmes", "movie", "movies", "cinema", "vod", "lançamento", "lancamento", "film")
    val liveTerms = listOf("ao vivo", "canais", "canal", "live", "iptv", "tv ", "televis", "esporte", "sport", "notícia", "noticia", "news", "rádio", "radio")
    return when {
        seriesTerms.any { it in text } -> "Séries"
        movieTerms.any { it in text } -> "Filmes"
        liveTerms.any { it in text } -> "Ao vivo"
        else -> "Ao vivo"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 11, 18)
        window.navigationBarColor = android.graphics.Color.rgb(5, 11, 18)
        setContent { AuroraApp() }
    }
}

@Composable
private fun AuroraApp() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var playlistUrl by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var activeGroup by remember { mutableStateOf("Todos") }
    var activeType by remember { mutableStateOf("Todos") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var editPlaylist by remember { mutableStateOf(true) }
    var activePlaylistUrl by remember { mutableStateOf("") }

    // Show the local copy immediately. A server refresh must never block the cached list.
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("aurora_iptv_preferences", android.content.Context.MODE_PRIVATE)
        val savedUrl = preferences.getString("playlist_url", "").orEmpty()
        if (savedUrl.isNotBlank()) {
            playlistUrl = savedUrl
            activePlaylistUrl = savedUrl
            val cachedChannels = withContext(Dispatchers.IO) { readCachedChannels(context) }
            if (cachedChannels.isNotEmpty()) {
                channels = cachedChannels
                editPlaylist = false
            } else {
                loading = true
            }
            try {
                val freshChannels = withContext(Dispatchers.IO) { loadM3u(savedUrl) }
                if (freshChannels.isNotEmpty()) {
                    channels = freshChannels
                    withContext(Dispatchers.IO) { saveCachedChannels(context, freshChannels) }
                    editPlaylist = false
                } else if (cachedChannels.isEmpty()) {
                    error = "A lista não trouxe canais válidos. Confira o endereço em Editar lista."
                    editPlaylist = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (cachedChannels.isEmpty()) {
                    error = "Não consegui atualizar a lista. Confira sua conexão ou o link em Editar lista."
                    editPlaylist = true
                }
            } finally {
                loading = false
            }
        }
    }

    // Periodic background refresh, separate from startup and manual loading.
    LaunchedEffect(activePlaylistUrl) {
        if (activePlaylistUrl.isNotBlank()) {
            while (true) {
                delay(15 * 60 * 1000L)
                try {
                    val freshChannels = withContext(Dispatchers.IO) { loadM3u(activePlaylistUrl) }
                    if (freshChannels.isNotEmpty()) {
                        withContext(Dispatchers.IO) { saveCachedChannels(context, freshChannels) }
                        channels = freshChannels
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the existing list and playback unchanged on refresh errors.
                }
            }
        }
    }

    val groups = remember(channels) { listOf("Todos") + channels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted() }
    val typeFilters = listOf("Todos", "Ao vivo", "Filmes", "Séries")
    val visible = remember(channels, search, activeGroup, activeType) {
        channels.filter { (activeType == "Todos" || it.contentType == activeType) &&
            (activeGroup == "Todos" || it.group == activeGroup) &&
            (search.isBlank() || it.name.contains(search, true) || it.group.contains(search, true)) }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel, onBackground = Txt, onSurface = Txt)) {
        Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF102C35), Color(0xFF101426), Color(0xFF201735)))).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(AuroraCyan, Accent, AuroraViolet))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LiveTv, null, tint = Color(0xFF061019), modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AURORA", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = Txt)
                    Text("IPTV", fontSize = 10.sp, letterSpacing = 3.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
                Text("BOREAL", color = AuroraCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(18.dp))
                Text("Sua TV. Do seu jeito.", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Txt)
                Spacer(Modifier.height(5.dp))
                Text("Sua diversão sob as cores da aurora.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                if (channels.isEmpty() || editPlaylist) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("SUA PLAYLIST M3U", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = playlistUrl, onValueChange = { playlistUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("https://servidor.com/lista.m3u", color = Muted, fontSize = 13.sp) },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                shape = RoundedCornerShape(13.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF303B4E), focusedTextColor = Txt, unfocusedTextColor = Txt, cursorColor = Accent)
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    error = null
                                    loading = true
                                    scope.launch {
                                        try {
                                            val enteredUrl = playlistUrl.trim()
                                            val loaded = withContext(Dispatchers.IO) { loadM3u(enteredUrl) }
                                            if (loaded.isEmpty()) {
                                                error = "O endereço respondeu, mas não encontrei canais válidos na M3U."
                                            } else {
                                                channels = loaded
                                                withContext(Dispatchers.IO) { saveCachedChannels(context, loaded) }
                                                context.getSharedPreferences("aurora_iptv_preferences", android.content.Context.MODE_PRIVATE)
                                                    .edit().putString("playlist_url", enteredUrl).apply()
                                                activePlaylistUrl = enteredUrl
                                                activeGroup = "Todos"
                                                activeType = "Todos"
                                                editPlaylist = false
                                                error = null
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = e.message?.takeIf { it.isNotBlank() } ?: "Não consegui carregar. Confira o link e tente novamente."
                                        } finally { loading = false }
                                    }
                                },
                                enabled = !loading && playlistUrl.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(49.dp),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Txt)
                                    Spacer(Modifier.width(9.dp))
                                    Text("Carregando…")
                                } else {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Carregar playlist", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (error != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(error!!, color = Color(0xFFFF8585), fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${channels.size} itens disponíveis", color = Txt, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Playlist salva neste aparelho", color = Accent, fontSize = 12.sp)
                        }
                        TextButton(onClick = { editPlaylist = true }) { Text("Editar lista", color = AuroraCyan) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar canal…", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF273246), focusedTextColor = Txt, unfocusedTextColor = Txt)
                )
                if (channels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(typeFilters) { type ->
                            FilterChip(
                                selected = activeType == type,
                                onClick = { activeType = type; activeGroup = "Todos" },
                                label = { Text(type, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color(0xFF061019), containerColor = Panel, labelColor = Muted)
                            )
                        }
                    }
                }
                if (groups.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups) { group ->
                            FilterChip(
                                selected = activeGroup == group,
                                onClick = { activeGroup = group },
                                label = { Text(group, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Txt, containerColor = Panel, labelColor = Muted)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (channels.isEmpty() && error == null && !loading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LiveTv, null, tint = Color(0xFF38465F), modifier = Modifier.size(62.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Sua lista vai aparecer aqui", color = Muted, fontWeight = FontWeight.Medium)
                            Text("Use um link M3U válido para carregar os canais.", color = Color(0xFF65738A), fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible) { channel ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel)
                                    .clickable { current = channel }.padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Panel2), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.LiveTv, null, tint = AuroraCyan, modifier = Modifier.size(23.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(channel.name, color = Txt, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
                                    Spacer(Modifier.height(3.dp))
                                    Text(channel.group, color = Muted, fontSize = 11.sp, maxLines = 1)
                                }
                                Icon(Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
        current?.let { channel -> PlayerDialog(channel) { current = null } }
    }
}

private fun saveCachedChannels(context: android.content.Context, channels: List<Channel>) {
    // Store one JSON object per line. Never build one enormous JSON string in memory.
    val target = java.io.File(context.filesDir, "aurora_playlist_cache.jsonl")
    val temp = java.io.File(context.filesDir, "aurora_playlist_cache.tmp")
    try {
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            channels.forEach { channel ->
                writer.append(JSONObject()
                    .put("name", channel.name)
                    .put("url", channel.url)
                    .put("group", channel.group)
                    .toString())
                writer.newLine()
            }
        }
        if (target.exists() && !target.delete()) {
            throw java.io.IOException("Não foi possível substituir o cache antigo.")
        }
        if (!temp.renameTo(target)) {
            // Same-directory copy fallback for devices/filesystems where rename fails.
            temp.inputStream().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            temp.delete()
        }
        // Remove the old giant SharedPreferences value after a successful file save.
        context.getSharedPreferences("aurora_iptv_preferences", android.content.Context.MODE_PRIVATE)
            .edit().remove("playlist_cache").apply()
    } catch (_: Exception) {
        temp.delete()
        // A cache failure must not prevent playback of the freshly loaded playlist.
    }
}

private fun readCachedChannels(context: android.content.Context): List<Channel> {
    val file = java.io.File(context.filesDir, "aurora_playlist_cache.jsonl")
    if (file.exists() && file.length() > 0L) {
        return try {
            val result = ArrayList<Channel>()
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val item = JSONObject(line)
                            val name = item.optString("name")
                            val url = item.optString("url")
                            if (name.isNotBlank() && url.isNotBlank()) {
                                result.add(Channel(name, url, item.optString("group", "Canais")))
                            }
                        } catch (_: Exception) {
                            // Ignore a malformed cache line and continue with the remaining entries.
                        }
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Compatibility with older releases: only parse a legacy cache of manageable size.
    return try {
        val preferences = context.getSharedPreferences("aurora_iptv_preferences", android.content.Context.MODE_PRIVATE)
        val raw = preferences.getString("playlist_cache", null)
        if (!raw.isNullOrBlank() && raw.length <= 8 * 1024 * 1024) {
            val array = JSONArray(raw)
            val result = ArrayList<Channel>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name")
                val url = item.optString("url")
                if (name.isNotBlank() && url.isNotBlank()) {
                    result.add(Channel(name, url, item.optString("group", "Canais")))
                }
            }
            result
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadM3u(source: String): List<Channel> {
    require(source.startsWith("http://", true) || source.startsWith("https://", true)) {
        "Use um link HTTP ou HTTPS."
    }
    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 25000
        setRequestProperty("User-Agent", "AuroraIPTV/1.0 Android")
        instanceFollowRedirects = true
    }
    try {
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = mutableListOf<Channel>()
            var name = "Canal"
            var group = "Canais"
            var pending = false
            reader.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", true) -> {
                        // Keep the original parser behavior from the previously working version.
                        name = line.substringAfterLast(",").trim().ifBlank { "Canal" }
                        group = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "Canais" }
                        pending = true
                    }
                    line.isNotEmpty() && !line.startsWith("#") && pending &&
                        (line.startsWith("http://", true) || line.startsWith("https://", true)) -> {
                        result.add(Channel(name, line, group))
                        pending = false
                    }
                }
            }
            return result
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun PlayerDialog(channel: Channel, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(isLandscape) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (isLandscape) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(channel.url)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize()
                .padding(if (isLandscape) 0.dp else 12.dp)
                .clip(RoundedCornerShape(if (isLandscape) 0.dp else 20.dp))
                .background(Color(0xFF0B1019))
        ) {
            if (!isLandscape) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LiveTv, null, tint = Accent)
                    Spacer(Modifier.width(9.dp))
                    Text(channel.name, color = Txt, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Fechar", tint = Txt) }
                }
            }
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                } },
                update = { view -> view.player = player },
                modifier = if (isLandscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            if (!isLandscape) {
                Text(channel.group, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
            }
        }
    }
}
