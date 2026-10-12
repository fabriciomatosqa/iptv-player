package br.com.fabriciomatos.iptvplayer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page { HOME, LIBRARY }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuroraViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PlaylistRepository(application)
    var catalog by mutableStateOf(Catalog(), referentialEqualityPolicy()); private set
    var playlistUrl by mutableStateOf(""); private set
    var playlistName by mutableStateOf("Minha playlist"); private set
    var loading by mutableStateOf(false); private set
    var initialized by mutableStateOf(false); private set
    var updated by mutableStateOf(0L); private set
    var message by mutableStateOf<String?>(null); private set
    var favorites by mutableStateOf(emptySet<String>()); private set
    var guide by mutableStateOf(Guide(), referentialEqualityPolicy()); private set
    var current by mutableStateOf<Channel?>(null); private set
    var recent by mutableStateOf<Channel?>(null); private set
    var playbackError by mutableStateOf<String?>(null); private set
    var buffering by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var page by mutableStateOf(Page.HOME)
    var selectedKind by mutableStateOf<MediaKind?>(MediaKind.LIVE); private set
    var favoritesOnly by mutableStateOf(false); private set
    var group by mutableStateOf<String?>(null); private set
    var search by mutableStateOf(""); private set
    var visible by mutableStateOf(emptyList<Channel>(), referentialEqualityPolicy()); private set
    var visibleSeries by mutableStateOf(emptyList<SeriesEntry>(), referentialEqualityPolicy()); private set
    var openedSeriesId by mutableStateOf<String?>(null); private set
    var selectedSeason by mutableStateOf<Int?>(null); private set
    val openedSeries: SeriesEntry? get() = catalog.series.firstOrNull { it.id == openedSeriesId }
    var settingsOpen by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    private var refreshJob: Job? = null
    private var filterJob: Job? = null
    private var guideJob: Job? = null
    private var epgUrl = ""
    private var foreground = false
    private var resumePlayback = false
    private var playerInstance: ExoPlayer? = null
    val player: ExoPlayer? get() = playerInstance

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val url = repository.savedUrl()
                listOf(url, repository.savedName(), repository.lastUrl()) to repository.readCache(url)
            }
            playlistUrl = saved.first[0]; playlistName = saved.first[1]
            favorites = withContext(Dispatchers.IO) { repository.favorites() }
            updated = withContext(Dispatchers.IO) { repository.state(playlistUrl).updated }
            installCatalog(saved.second, replace = true)
            recent = catalog.channels.firstOrNull { it.url == saved.first[2] }
            initialized = true
            if (foreground && playlistUrl.isNotBlank()) refreshDaily()
        }
    }

    private suspend fun installCatalog(playlist: Playlist, replace: Boolean = false) {
        val old = catalog.channels
        val result = withContext(Dispatchers.Default) { Catalog.build(if (replace) playlist.channels else mergePlaylist(old, playlist.channels)) }
        catalog = result
        if (replace || playlist.epgUrl.isNotBlank()) epgUrl = playlist.epgUrl
        updateFilter()
        refreshGuide()
    }

    fun savePlaylist(url: String, name: String) {
        val clean = url.trim()
        if (!PlaylistRepository.validUrl(clean)) { message = "Use um endereço M3U HTTP ou HTTPS válido."; return }
        viewModelScope.launch {
            val changed = clean != playlistUrl
            try { withContext(Dispatchers.IO) { repository.saveSource(clean, name.trim()) } }
            catch (_: java.io.IOException) { message = "Não consegui salvar o endereço. Verifique o espaço disponível."; return@launch }
            refreshJob?.cancelAndJoin()
            playlistUrl = clean; playlistName = name.trim().ifBlank { "Minha playlist" }
            settingsOpen = false; message = null
            if (changed) {
                guideJob?.cancelAndJoin()
                filterJob?.cancel()
                stop()
                guide = Guide(); group = null; search = ""; openedSeriesId = null; selectedSeason = null
                val restored = withContext(Dispatchers.IO) { repository.readCache(clean) }
                updated = withContext(Dispatchers.IO) { repository.state(clean).updated }
                installCatalog(restored, replace = true)
            }
            refresh(force = changed)
        }
    }

    private fun refreshDaily() = refresh(silent = true, daily = true)

    fun refresh(force: Boolean = false, silent: Boolean = false, daily: Boolean = false) {
        if (playlistUrl.isBlank()) { settingsOpen = true; return }
        if (refreshJob?.isActive == true) return
        val source = playlistUrl
        refreshJob = viewModelScope.launch {
            val quiet = silent && catalog.channels.isNotEmpty()
            try {
                val claimed = withContext(Dispatchers.IO) {
                    if (daily) repository.claimDailyAttempt(source) else { repository.recordAttempt(source); true }
                }
                if (!claimed) return@launch
                if (!quiet) { loading = true; message = null }
                val downloaded = withContext(Dispatchers.IO) { repository.download(source, !force && catalog.channels.isNotEmpty()) }
                if (downloaded != null) {
                    // Commit first. Leaving the app cannot cancel a partially written catalog.
                    val added = withContext(NonCancellable + Dispatchers.IO) { repository.appendCache(source, downloaded) }
                    coroutineContext.ensureActive()
                    if (playlistUrl != source) return@launch
                    updated = withContext(Dispatchers.IO) { repository.state(source).updated }
                    if (added > 0 || catalog.channels.isEmpty()) installCatalog(downloaded.playlist)
                    else if (downloaded.playlist.epgUrl.isNotBlank()) { epgUrl = downloaded.playlist.epgUrl; refreshGuide() }
                } else if (downloaded == null) {
                    refreshGuide()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!quiet) message = if (catalog.channels.isNotEmpty()) "Não consegui atualizar agora. Sua lista salva continua disponível."
                    else if (e is java.io.IOException && e.message?.startsWith("O servidor") == true) e.message
                    else "Endereço salvo. Não consegui carregar a lista; confira sua conexão e o link em Minha playlist."
            } finally { if (!quiet) loading = false }
        }
    }

    fun browse(kind: MediaKind?, favorites: Boolean = false) {
        if (kind != null && current != null && (current?.kind != kind || kind == MediaKind.SERIES)) stop()
        openedSeriesId = null; selectedSeason = null
        selectedKind = kind; favoritesOnly = favorites; group = null; search = ""; page = Page.LIBRARY
        updateFilter()
    }
    fun showSeries(series: SeriesEntry) {
        openedSeriesId = series.id; selectedSeason = series.seasons.firstOrNull(); group = null; search = ""
        updateFilter()
    }
    fun closeSeries() { stop(); openedSeriesId = null; selectedSeason = null; search = ""; updateFilter() }
    fun selectSeason(season: Int) { selectedSeason = season; updateFilter() }
    fun selectGroup(value: String?) { group = value; updateFilter() }
    fun search(value: String) { search = value; updateFilter(debounce = true) }
    private fun updateFilter(debounce: Boolean = false) {
        filterJob?.cancel()
        val data = catalog; val kind = selectedKind; val only = favoritesOnly; val favoriteUrls = favorites; val category = group; val query = search
        val seriesId = openedSeriesId; val season = selectedSeason
        filterJob = viewModelScope.launch {
            if (debounce) delay(180)
            val result = withContext(Dispatchers.Default) {
                if (kind == MediaKind.SERIES) {
                    if (seriesId == null) {
                        val shows = filterSeries(data, category, query).let { entries -> if (only) entries.filter { it.episodes.any { episode -> episode.channel.url in favoriteUrls } } else entries }
                        emptyList<Channel>() to shows
                    } else {
                        val episodes = data.series.firstOrNull { it.id == seriesId }?.episodes.orEmpty()
                        val words = normalized(query).split(' ').filter(String::isNotBlank)
                        episodes.filter { (season == null || it.season == season) && (!only || it.channel.url in favoriteUrls) && words.all(it.channel.searchText::contains) }.map { it.channel } to emptyList()
                    }
                } else filterChannels(data, kind, only, favoriteUrls, category, query) to emptyList<SeriesEntry>()
            }
            visible = result.first; visibleSeries = result.second
        }
    }
    fun groups(): List<String> = if (selectedKind == null) catalog.groups.values.flatten().distinct().sorted() else catalog.groups[selectedKind].orEmpty()
    fun toggleFavorite(channel: Channel) {
        favorites = if (channel.url in favorites) favorites - channel.url else favorites + channel.url
        // SharedPreferences.apply enqueues the small write in order, without disk IO here.
        repository.saveFavorites(favorites)
        if (favoritesOnly) updateFilter()
    }

    private fun refreshGuide() {
        guideJob?.cancel()
        val source = epgUrl
        if (source.isBlank()) { guide = Guide(); return }
        val ids = catalog.channels.map { it.tvgId }.filter { it.isNotBlank() }.toSet()
        guideJob = viewModelScope.launch {
            try { guide = withContext(Dispatchers.IO) { loadGuide(source, ids) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* EPG failure must not interfere with live playback. */ }
        }
    }

    fun play(channel: Channel) {
        if (selectedKind != channel.kind) { group = null; search = ""; favoritesOnly = false }
        selectedKind = channel.kind
        if (channel.kind == MediaKind.SERIES) {
            openedSeriesId = catalog.seriesByEpisodeUrl[channel.url]
            selectedSeason = openedSeries?.episodes?.firstOrNull { it.channel.url == channel.url }?.season
        } else { openedSeriesId = null; selectedSeason = null }
        updateFilter()
        if (current?.url == channel.url && playbackError == null && playerInstance?.playbackState != Player.STATE_ENDED && playerInstance?.playbackState != Player.STATE_IDLE) {
            page = Page.LIBRARY; return
        }
        playbackError = null; buffering = true; current = channel; recent = channel; page = Page.LIBRARY
        viewModelScope.launch(Dispatchers.IO) { repository.saveLast(channel) }
        try {
            val instance = playerInstance ?: createPlayer().also { playerInstance = it }
            val http = DefaultHttpDataSource.Factory().setUserAgent(channel.userAgent.ifBlank { "AuroraIPTV/1.2 Android" })
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                .setDefaultRequestProperties(if (channel.referrer.isBlank()) emptyMap() else mapOf("Referer" to channel.referrer))
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(getApplication(), http))
            val item = MediaItem.Builder().setUri(channel.url)
            val url = channel.url.lowercase()
            val mime = channel.mimeType.ifBlank {
                when { url.contains(".m3u8") || url.contains("type=m3u8") || url.contains("output=m3u8") -> MimeTypes.APPLICATION_M3U8
                    url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
                    else -> "" }
            }
            if (mime.isNotBlank()) item.setMimeType(mime)
            instance.setMediaSource(factory.createMediaSource(item.build()))
            instance.prepare(); instance.playWhenReady = true
        } catch (_: Exception) { buffering = false; playbackError = "Não consegui abrir este vídeo. Tente outro canal ou tente novamente." }
    }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(getApplication<Application>())
        .setRenderersFactory(DefaultRenderersFactory(getApplication()).setEnableDecoderFallback(true))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1000, 2000).setTargetBufferBytes(32 * 1024 * 1024).build())
        .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
                override fun onIsPlayingChanged(playing: Boolean) { this@AuroraViewModel.isPlaying = playing }
                override fun onPlayerError(error: PlaybackException) {
                    buffering = false
                    playbackError = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sem conexão com o canal. Confira sua internet e tente novamente."
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "O servidor não liberou este canal. Tente outro ou atualize a lista."
                        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Este vídeo usa um formato que o aparelho não conseguiu reproduzir."
                        else -> "Não foi possível reproduzir este canal. Tente novamente ou escolha outro."
                    }
                }
            })
        }

    fun retry() { current?.let(::play) }
    fun stop() { playerInstance?.release(); playerInstance = null; current = null; fullscreen = false; playbackError = null; buffering = false; isPlaying = false; resumePlayback = false }
    fun foreground(active: Boolean) {
        foreground = active
        if (active && initialized && playlistUrl.isNotBlank()) refreshDaily()
        if (!active) { resumePlayback = playerInstance?.playWhenReady == true; playerInstance?.pause() }
        else if (resumePlayback) { playerInstance?.play(); resumePlayback = false }
    }
    override fun onCleared() { playerInstance?.release() }
}
