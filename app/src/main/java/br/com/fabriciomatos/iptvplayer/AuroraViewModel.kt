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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Page { HOME, LIBRARY }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuroraViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PlaylistRepository(application)
    var catalog by mutableStateOf(CatalogOverview(), referentialEqualityPolicy()); private set
    var playlistUrl by mutableStateOf(""); private set
    var playlistName by mutableStateOf("Minha playlist"); private set
    var loading by mutableStateOf(false); private set
    var initialized by mutableStateOf(false); private set
    var updated by mutableStateOf(0L); private set
    var message by mutableStateOf<String?>(null); private set
    var progressText by mutableStateOf<String?>(null); private set
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
    var visibleCount by mutableStateOf(0); private set
    var filtering by mutableStateOf(false); private set
    var openedSeriesId by mutableStateOf<String?>(null); private set
    var openedSeries by mutableStateOf<SeriesEntry?>(null); private set
    var selectedSeason by mutableStateOf<Int?>(null); private set
    var settingsOpen by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    private var refreshJob: Job? = null
    private var filterJob: Job? = null
    private var guideJob: Job? = null
    private val favoriteWrites = Mutex()
    private var pendingFavorites: Set<String>? = null
    private var epgUrl = ""
    private var foreground = false
    private var resumePlayback = false
    private var playerInstance: ExoPlayer? = null
    val player: ExoPlayer? get() = playerInstance
    private val browseQuery = MutableStateFlow(BrowseQuery())
    private val pagingConfig = PagingConfig(pageSize = 60, initialLoadSize = 60, maxSize = 240, enablePlaceholders = false)
    val channelPages = browseQuery.flatMapLatest { query -> Pager(pagingConfig) { repository.channelPages(query) }.flow }.cachedIn(viewModelScope)
    val seriesPages = browseQuery.flatMapLatest { query -> Pager(pagingConfig) { repository.seriesPages(query) }.flow }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { Triple(repository.savedUrl(), repository.savedName(), repository.lastUrl()) }
                playlistUrl = saved.first; playlistName = saved.second
                progressText = if (playlistUrl.isBlank()) null else "Abrindo catálogo salvo…"
                val source = playlistUrl
                withContext(Dispatchers.IO) { repository.restore(source, ::showProgress) }
                if (playlistUrl != source) return@launch
                favorites = withContext(Dispatchers.IO) { repository.favorites() }
                reloadOverview()
                recent = withContext(Dispatchers.IO) {
                    repository.channel(source, saved.third) ?: repository.channels(BrowseQuery(source, MediaKind.MOVIES),limit=1).firstOrNull()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Não consegui abrir o catálogo salvo. Seu endereço continua salvo; tente Atualizar." }
            finally { initialized = true; progressText = null }
            if (foreground && playlistUrl.isNotBlank()) refreshDaily()
        }
    }
    private fun showProgress(text: String) { viewModelScope.launch { progressText = text } }
    private suspend fun reloadOverview() {
        val source = playlistUrl
        val data = withContext(Dispatchers.IO) {
            Triple(repository.overview(source), repository.state(source), openedSeriesId?.let { repository.seriesDetail(source,it) })
        }
        if (source != playlistUrl) return
        catalog = data.first; updated = data.second.updated; epgUrl = data.second.epgUrl
        if (openedSeriesId != null) openedSeries = data.third
        if (recent == null && catalog.total > 0) recent = withContext(Dispatchers.IO) {
            repository.channels(BrowseQuery(source,if(catalog.movies>0)MediaKind.MOVIES else MediaKind.LIVE),limit=1).firstOrNull()
        }
        updateFilter()
        refreshGuide()
    }
    fun savePlaylist(url: String, name: String) {
        val clean = url.trim()
        if (!PlaylistRepository.validUrl(clean)) { message = "Use um endereço M3U HTTP ou HTTPS válido."; return }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.saveSource(clean,name.trim()) } }
            catch (_: Exception) { message = "Não consegui salvar o endereço. Verifique o espaço disponível."; return@launch }
            refreshJob?.cancelAndJoin()
            val changed = clean != playlistUrl
            playlistUrl = clean; playlistName = name.trim().ifBlank { "Minha playlist" }
            settingsOpen = false; message = null
            if (changed) {
                stop(); openedSeries = null; openedSeriesId = null; selectedSeason = null; group = null; search = ""
                withContext(Dispatchers.IO) { repository.restore(clean,::showProgress) }
                reloadOverview()
            }
            refresh(force = changed)
        }
    }
    private fun refreshDaily() = refresh(silent = true,daily = true)
    fun refresh(force: Boolean = false, silent: Boolean = false, daily: Boolean = false) {
        if (playlistUrl.isBlank()) { settingsOpen = true; return }
        if (refreshJob?.isActive == true) return
        val source = playlistUrl
        refreshJob = viewModelScope.launch {
            val quiet = silent && catalog.total > 0
            val progress: (String) -> Unit = if (quiet) ({ _ -> }) else ::showProgress
            try {
                val pending = withContext(Dispatchers.IO) { !force && repository.hasPending(source) }
                val claimed = if (pending) true else withContext(Dispatchers.IO) {
                    if (daily) repository.claimDailyAttempt(source) else { repository.recordAttempt(source); true }
                }
                if (!claimed) {
                    if (withContext(Dispatchers.IO) { repository.needsMetadata(source) }) {
                        val metadata = withContext(Dispatchers.IO) { repository.fetchMetadata(source) }
                        withContext(NonCancellable + Dispatchers.IO) { repository.repairMetadata(source,metadata) }
                        coroutineContext.ensureActive()
                        reloadOverview()
                    }
                    return@launch
                }
                if (!quiet) { loading = true; message = null; progressText = if(pending) "Retomando a lista já baixada…" else "Carregando informações da lista…" }
                val metadata = withContext(Dispatchers.IO) {
                    if(pending) repository.metadata(source) else repository.fetchMetadata(source)
                }
                val downloaded = pending || withContext(Dispatchers.IO) { repository.downloadFile(source,!force && catalog.total>0,progress) }
                if (downloaded) {
                    progress("Salvando catálogo completo…")
                    withContext(NonCancellable + Dispatchers.IO) { repository.importPending(source,metadata,progress) }
                } else withContext(NonCancellable + Dispatchers.IO) { repository.repairMetadata(source,metadata) }
                coroutineContext.ensureActive()
                if (playlistUrl == source) reloadOverview()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!quiet) message = if(catalog.total>0) "Não consegui atualizar agora. Sua lista salva continua disponível."
                    else "Endereço salvo. Não consegui concluir a lista; toque em Atualizar para tentar novamente."
            } finally { if(!quiet) { loading = false; progressText = null } }
        }
    }
    fun browse(kind: MediaKind?, favorites: Boolean = false) {
        if(kind!=null && current!=null && (current?.kind!=kind || kind==MediaKind.SERIES))stop()
        openedSeriesId = null; openedSeries = null; selectedSeason = null
        selectedKind = kind; favoritesOnly = favorites; group = null; search = ""; page = Page.LIBRARY
        updateFilter()
    }
    fun showSeries(series: SeriesEntry) {
        openedSeriesId = series.id; openedSeries = series; selectedSeason = series.seasons.firstOrNull(); group = null; search = ""
        updateFilter()
    }
    fun closeSeries() { stop(); openedSeriesId = null; openedSeries = null; selectedSeason = null; search = ""; updateFilter() }
    fun selectSeason(season: Int) { selectedSeason = season; updateFilter() }
    fun selectGroup(value: String?) { group = value; updateFilter() }
    fun search(value: String) { search = value; updateFilter(debounce = true) }
    private fun updateFilter(debounce: Boolean = false) {
        filterJob?.cancel()
        val query = BrowseQuery(playlistUrl,selectedKind,favoritesOnly,group,search,openedSeriesId,selectedSeason)
        filterJob = viewModelScope.launch {
            filtering = true
            if(debounce)delay(180)
            browseQuery.value = query
            visibleCount = withContext(Dispatchers.IO) { repository.matchingCount(query) }
            filtering = false
        }
    }
    fun groups(): List<String> = if(selectedKind==null)catalog.groups.values.flatten().distinct().sorted() else catalog.groups[selectedKind].orEmpty()
    fun toggleFavorite(channel: Channel) {
        val before = pendingFavorites ?: favorites
        val next = if(channel.url in before)before-channel.url else before+channel.url
        pendingFavorites = next
        viewModelScope.launch {
            try {
                favoriteWrites.withLock {
                    withContext(NonCancellable + Dispatchers.IO) { repository.saveFavorites(next) }
                    favorites = next
                    if(pendingFavorites==next)pendingFavorites = null
                    if(favoritesOnly) { repository.invalidatePages(); updateFilter() }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(pendingFavorites==next)pendingFavorites=null;message="Não consegui salvar o favorito. Verifique o espaço disponível." }
        }
    }
    private fun refreshGuide() {
        guideJob?.cancel()
        val source = epgUrl; val playlist = playlistUrl
        if(source.isBlank()) { guide = Guide(); return }
        guideJob = viewModelScope.launch {
            try {
                guide = withContext(Dispatchers.IO) { loadGuide(source,repository.liveIds(playlist)) }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { }
        }
    }

    fun play(channel: Channel) {
        if (selectedKind != channel.kind) { group = null; search = ""; favoritesOnly = false }
        selectedKind = channel.kind
        if (channel.kind == MediaKind.SERIES) {
            selectedSeason = channel.seasonNumber.coerceAtLeast(1)
            viewModelScope.launch {
                val detail = withContext(Dispatchers.IO) {
                    repository.seriesId(playlistUrl,channel.url)?.let { repository.seriesDetail(playlistUrl,it) }
                }
                if (current?.url == channel.url && detail != null) {
                    openedSeriesId = detail.id; openedSeries = detail; updateFilter()
                }
            }
        } else { openedSeriesId = null; openedSeries = null; selectedSeason = null }
        updateFilter()
        if (current?.url == channel.url && playbackError == null && playerInstance?.playbackState != Player.STATE_ENDED && playerInstance?.playbackState != Player.STATE_IDLE) {
            page = Page.LIBRARY; return
        }
        playbackError = null; buffering = true; current = channel; recent = channel; page = Page.LIBRARY
        viewModelScope.launch(Dispatchers.IO) { repository.saveLast(channel) }
        try {
            val instance = playerInstance ?: createPlayer().also { playerInstance = it }
            val http = DefaultHttpDataSource.Factory().setUserAgent(channel.userAgent.ifBlank { "AuroraIPTV/1.3 Android" })
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
    override fun onCleared() {
        playerInstance?.release()
        val pending = viewModelScope.coroutineContext[Job]
        CoroutineScope(Dispatchers.IO).launch { pending?.join(); repository.close() }
    }
}
