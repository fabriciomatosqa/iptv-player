package br.com.fabriciomatos.iptvplayer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as WriteQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Page { HOME, LIBRARY, FOOTBALL }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AuroraViewModel(application: Application) : AndroidViewModel(application) {
    internal val onlineUpdate = AppUpdateController(AppUpdateRepository(application), viewModelScope)
    private val repository = PlaylistRepository(application)
    private val artwork = ArtworkResolver(repository)
    private val scores = FootballScoreRepository(repository)
    private val synopses = SynopsisRepository(repository)
    private val scoreRefresh = Mutex()
    private val coverRequests = mutableMapOf<String,Deferred<String?>>()
    private val preferences = AppSettings(application)
    var language by mutableStateOf(preferences.language()); private set
    var appearance by mutableStateOf(preferences.appearance()); private set
    var epgOverride by mutableStateOf(""); private set
    var savedPlaylists by mutableStateOf(emptyList<SavedPlaylist>()); private set
    var switchingPlaylist by mutableStateOf(false); private set
    private val playlistSwitch = Mutex()
    var parentalConfigured by mutableStateOf(preferences.hasPassword()); private set
    var adultUnlocked by mutableStateOf(false); private set
    var parentalDialog by mutableStateOf(false); private set
    var parentalRequestId by mutableStateOf(0); private set
    var changePassword by mutableStateOf(false); private set
    var parentalError by mutableStateOf<String?>(null); private set
    var parentalBusy by mutableStateOf(false); private set
    private var protectedAction: (() -> Unit)? = null
    var football by mutableStateOf(emptyList<FootballMatch>()); private set
    var footballFeed by mutableStateOf(FootballFeed()); private set
    var footballScoresLoading by mutableStateOf(false); private set
    var footballSearch by mutableStateOf("")
    var footballTab by mutableStateOf(FOOTBALL_GAMES)
    var footballPosition: Pair<Pair<String,String>,Pair<Int,Int>>? = null
    var fromFootball by mutableStateOf(false); private set
    var guideLoading by mutableStateOf(false); private set
    var guideError by mutableStateOf(false); private set
    var catalog by mutableStateOf(CatalogOverview(), referentialEqualityPolicy()); private set
    var playlistUrl by mutableStateOf(""); private set
    var playlistName by mutableStateOf("Minha playlist"); private set
    var loading by mutableStateOf(false); private set
    var updating by mutableStateOf(false); private set
    var initialized by mutableStateOf(false); private set
    var updated by mutableStateOf(0L); private set
    var message by mutableStateOf<String?>(null); private set
    var progressText by mutableStateOf<String?>(null); private set
    var progressPercent by mutableStateOf<Int?>(null); private set
    var favorites by mutableStateOf(emptySet<String>()); private set
    var seriesFavorites by mutableStateOf(emptySet<String>()); private set
    var guide by mutableStateOf(Guide(), referentialEqualityPolicy()); private set
    var current by mutableStateOf<Channel?>(null); private set
    var recent by mutableStateOf<Channel?>(null); private set
    var playbackError by mutableStateOf<String?>(null); private set
    var playbackErrorDetails by mutableStateOf<String?>(null); private set
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
    var settingsSection by mutableStateOf(0)
    fun openSettings(section: Int = 0) {settingsSection=section;settingsOpen=true}
    var fullscreen by mutableStateOf(false)
    private var refreshJob: Job? = null
    private var filterJob: Job? = null
    private var guideJob: Job? = null
    private val favoriteWrites = Mutex()
    private var pendingFavorites: Set<String>? = null
    private var pendingSeriesFavorites: Pair<String,Set<String>>? = null
    private var epgUrl = ""
    private var foreground by mutableStateOf(false)
    private var resumePlayback = false
    private var playerInstance: ExoPlayer? = null
    private var playerSource = ""
    private var playJob: Job? = null
    private val playbackPositions = LinkedHashMap<Pair<String,String>,PlaybackProgress>()
    private val playbackWrites = WriteQueue<PlaybackCheckpoint>(WriteQueue.UNLIMITED)
    private val playbackPersistence = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val playbackWriteJob = playbackPersistence.launch {
        for(checkpoint in playbackWrites)try {
            repository.savePlaybackProgress(checkpoint.source,checkpoint.url,checkpoint.progress)
        } catch(_: Exception) { withContext(Dispatchers.Main) { message=preferences.text(R.string.load_failed) } }
    }
    private var seriesOrigin: CatalogLocation? = null
    private var playbackOrigin: CatalogLocation? = null
    private val catalogAnchors = LinkedHashMap<CatalogPositionKey,CatalogAnchor>()
    fun catalogPositionKey() = CatalogPositionKey(playlistUrl,selectedKind,favoritesOnly,group,search,openedSeriesId,selectedSeason)
    fun catalogAnchor(key: CatalogPositionKey) = catalogAnchors[key]
    fun rememberCatalogAnchor(key: CatalogPositionKey, anchor: CatalogAnchor) {
        catalogAnchors[key]=anchor
        while(catalogAnchors.size>100)catalogAnchors.remove(catalogAnchors.keys.first())
    }
    private fun catalogLocation() = CatalogLocation(page,selectedKind,favoritesOnly,group,search)
    private fun restoreLocation(location: CatalogLocation?) {
        if(location!=null) {
            page=location.page;selectedKind=location.kind;favoritesOnly=location.favorites
            group=location.group.takeUnless {it==ADULT_GROUP && !adultUnlocked};search=location.search
        }
        updateFilter()
    }
    val player: ExoPlayer? get() = playerInstance
    private var browseQuery = BrowseQuery()
    private var pagingJob: Job? = null
    private val pagingConfig = PagingConfig(pageSize = 60, initialLoadSize = 60, maxSize = 240, enablePlaceholders = false)
    var channelPages by mutableStateOf<Flow<PagingData<Channel>>>(flowOf(PagingData.empty())); private set
    var seriesPages by mutableStateOf<Flow<PagingData<SeriesEntry>>>(flowOf(PagingData.empty())); private set
    private fun applyBrowseQuery(query: BrowseQuery) {
        if(query==browseQuery)return
        browseQuery=query
        // Each filter owns its cache: returning cannot replay the episode list as a series catalog.
        pagingJob?.cancel()
        val job=Job(viewModelScope.coroutineContext[Job]);pagingJob=job
        val scope=CoroutineScope(viewModelScope.coroutineContext+job)
        channelPages=Pager(pagingConfig,initialKey=query.initialOffset/60*60) {repository.channelPages(query)}.flow.cachedIn(scope)
        seriesPages=Pager(pagingConfig,initialKey=query.initialOffset/60*60) {repository.seriesPages(query)}.flow.cachedIn(scope)
    }

    init {
        viewModelScope.launch { while(true) { delay(5000L); checkpointPlayback() } }
        viewModelScope.launch {
            snapshotFlow {if(initialized && foreground && page==Page.FOOTBALL && !settingsOpen)football else null}.collectLatest {matches->
                if(matches!=null)while(true) {
                    val next=System.currentTimeMillis()+FOOTBALL_REFRESH_MS
                    updateFootballScores()
                    delay((next-System.currentTimeMillis()).coerceAtLeast(1000L))
                }
            }
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { Triple(repository.savedUrl(), repository.savedName(), repository.lastUrl()) }
                playlistUrl = saved.first; playlistName = saved.second
                savedPlaylists=withContext(Dispatchers.IO) {repository.savedPlaylists()}
                epgOverride=withContext(Dispatchers.IO) {preferences.epgUrl(playlistUrl)}
                progressText = if (playlistUrl.isBlank()) null else preferences.text(R.string.loading)
                val source = playlistUrl
                withContext(Dispatchers.IO) { repository.restore(source, ::showProgress) }
                if (playlistUrl != source) return@launch
                favorites = withContext(Dispatchers.IO) { repository.favorites() }
                seriesFavorites = withContext(Dispatchers.IO) { repository.seriesFavorites(source) }
                guide = withContext(Dispatchers.IO) { repository.savedGuide(source) }
                reloadOverview()
                recent = withContext(Dispatchers.IO) {
                    repository.channel(source, saved.third)?.takeUnless { it.adult } ?: repository.channels(BrowseQuery(source, MediaKind.MOVIES),limit=1).firstOrNull()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = preferences.text(R.string.load_failed) }
            finally { initialized = true; progressText = null }
            if (foreground && playlistUrl.isNotBlank()) refreshDaily()
        }
    }
    private fun showProgress(progress: CatalogProgress) { viewModelScope.launch {
        if(initialized && !updating && !loading)return@launch
        progressPercent=progress.percent
        val stage=preferences.text(when(progress.stage) {
            CatalogStage.DOWNLOADING->R.string.downloading;CatalogStage.IMPORTING->R.string.importing
            CatalogStage.FINALIZING->R.string.finalizing;CatalogStage.COMPLETE->R.string.saved_text;else->R.string.loading
        })
        progressText=stage + if(progress.percent!=null)" · ${progress.percent}%" else if(progress.bytes>0)" · ${progress.bytes/(1024*1024)} MB" else ""
    } }
    private suspend fun reloadOverview() {
        val source = playlistUrl
        val data = withContext(Dispatchers.IO) {
            Triple(repository.overview(source), repository.state(source), openedSeriesId?.let { repository.seriesDetail(source,it) })
        }
        if (source != playlistUrl) return
        catalog = data.first; updated = data.second.updated; epgUrl = data.second.epgUrl
        favorites = withContext(Dispatchers.IO) { repository.favorites() }
        recent?.let { old -> recent=withContext(Dispatchers.IO) {repository.channel(source,old.url)} ?: old }
        seriesFavorites = withContext(Dispatchers.IO) { repository.seriesFavorites(source) }
        if (openedSeriesId != null) openedSeries = data.third
        if (recent == null && catalog.total > 0) recent = withContext(Dispatchers.IO) {
            repository.channels(BrowseQuery(source,if(catalog.movies>0)MediaKind.MOVIES else MediaKind.LIVE),limit=1).firstOrNull()
        }
        updateFilter()
        refreshGuide()
    }
    fun savePlaylist(url: String, name: String) {
        val clean = url.trim()
        if (!PlaylistRepository.validUrl(clean)) { message = preferences.text(R.string.invalid_url); return }
        if(!initialized || switchingPlaylist)return
        switchingPlaylist=true
        viewModelScope.launch {playlistSwitch.withLock {
            try {
                refreshJob?.cancelAndJoin()
                val changed=clean!=playlistUrl
                withContext(NonCancellable+Dispatchers.IO) {repository.saveSource(clean,name.trim())}
                if(changed) {
                    // Save the old playback position before changing the active source.
                    stop();guideJob?.cancelAndJoin();filterJob?.cancelAndJoin()
                    openedSeries=null;openedSeriesId=null;selectedSeason=null;group=null;search=""
                    selectedKind=MediaKind.LIVE;favoritesOnly=false;adultUnlocked=false;dismissParental()
                    catalogAnchors.clear();seriesOrigin=null;playbackOrigin=null
                    footballFeed=FootballFeed();footballSearch="";footballTab=FOOTBALL_GAMES;footballPosition=null;fromFootball=false
                    football=emptyList();recent=null
                }
                playlistUrl=clean;playlistName=name.trim().ifBlank {"Minha playlist"}
                savedPlaylists=withContext(Dispatchers.IO) {repository.savedPlaylists()}
                epgOverride=withContext(Dispatchers.IO) {preferences.epgUrl(clean)}
                settingsOpen=false;message=null
                if(changed) {
                    page=Page.HOME;loading=true
                    guide=withContext(Dispatchers.IO) {repository.savedGuide(clean)}
                    withContext(Dispatchers.IO) {repository.restore(clean,::showProgress)}
                    recent=withContext(Dispatchers.IO) {repository.channel(clean,repository.lastUrl(clean))?.takeUnless {it.adult}}
                    reloadOverview()
                }
            } catch(e: CancellationException) {throw e}
            catch(_: Exception) {message=preferences.text(R.string.load_failed)}
            finally {switchingPlaylist=false;loading=false;progressText=null;progressPercent=null}
            // A saved catalog appears immediately; only the daily background check may download it again.
            if(playlistUrl==clean)refreshDaily()
        }}
    }
    fun switchPlaylist(playlist: SavedPlaylist) = savePlaylist(playlist.url,playlist.name)
    private fun refreshDaily() = refresh(silent = true,daily = true)
    fun refresh(force: Boolean = false, silent: Boolean = false, daily: Boolean = false) {
        if (playlistUrl.isBlank()) { settingsOpen = true; return }
        if (refreshJob?.isActive == true) return
        val source = playlistUrl
        refreshJob = viewModelScope.launch {
            val quiet = silent && catalog.total > 0
            val progress: (CatalogProgress) -> Unit = { value -> if(playlistUrl==source)showProgress(value) }
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
                updating=true;progressPercent=if(pending)50 else 0;progressText=preferences.text(R.string.loading)+" · ${progressPercent}%"
                if (!quiet) { loading = true; message = null }
                val metadata = withContext(Dispatchers.IO) {
                    if(pending) repository.metadata(source) else repository.fetchMetadata(source)
                }
                val downloaded = pending || withContext(Dispatchers.IO) { repository.downloadFile(source,!force && catalog.total>0,progress) }
                if (downloaded) {
                    withContext(NonCancellable + Dispatchers.IO) { repository.importPending(source,metadata,progress) }
                } else withContext(NonCancellable + Dispatchers.IO) { repository.repairMetadata(source,metadata) }
                coroutineContext.ensureActive()
                if (playlistUrl == source) reloadOverview()
                showProgress(CatalogProgress(CatalogStage.COMPLETE,100));delay(500)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!quiet) message = if(catalog.total>0) preferences.text(R.string.load_failed)
                    else preferences.text(R.string.load_failed)
            } finally { updating=false;loading=false;progressText=null;progressPercent=null }
        }
    }
    fun browse(kind: MediaKind?, favorites: Boolean = false) {
        if(!favorites && kind==selectedKind && openedSeriesId!=null) {closeSeries();return}
        if(!favorites && kind==selectedKind && current?.kind==MediaKind.MOVIES) {closeVideo();return}
        seriesOrigin=null;playbackOrigin=null;fromFootball=false
        if(kind!=null && current!=null && (current?.kind!=kind || kind==MediaKind.SERIES))stop()
        openedSeriesId = null; openedSeries = null; selectedSeason = null
        selectedKind = kind; favoritesOnly = favorites; group = null; search = ""; page = Page.LIBRARY
        updateFilter()
    }
    fun showSeries(series: SeriesEntry) {
        if(series.adult && !adultUnlocked) { requireParental { showSeries(series) }; return }
        if(openedSeriesId==null)seriesOrigin=catalogLocation()
        if(current!=null)stop()
        selectedKind = MediaKind.SERIES
        openedSeriesId = series.id; openedSeries = series; selectedSeason = series.seasons.firstOrNull(); group = null; search = ""
        updateFilter()
    }
    fun closeSeries() {
        val origin=seriesOrigin;seriesOrigin=null
        stop();openedSeriesId=null;openedSeries=null;selectedSeason=null;search=""
        restoreLocation(origin)
    }
    fun closeVideo() {
        val movie=current?.kind==MediaKind.MOVIES
        val football=fromFootball
        val origin=playbackOrigin
        stop()
        if(football)page=Page.FOOTBALL else if(movie)restoreLocation(origin)
    }
    fun backFromLibrary() {
        when {fromFootball->{stop();page=Page.FOOTBALL};openedSeriesId!=null->closeSeries();current?.kind==MediaKind.MOVIES->closeVideo();else->page=Page.HOME}
    }
    fun selectSeason(season: Int) { selectedSeason = season; updateFilter() }
    fun selectGroup(value: String?) {
        if(value==ADULT_GROUP && !adultUnlocked) { requireParental { selectGroup(value) }; return }
        group = value
        if(current?.kind==MediaKind.MOVIES)playbackOrigin=catalogLocation()
        updateFilter()
    }
    fun search(value: String) { search = value;if(current?.kind==MediaKind.MOVIES)playbackOrigin=catalogLocation(); updateFilter(debounce = true) }
    private fun updateFilter(debounce: Boolean = false) {
        filterJob?.cancel()
        val aliases=if(selectedKind==MediaKind.MOVIES && group!=null)catalog.groups[MediaKind.MOVIES].orEmpty().filter { normalized(movieCategory(it))==normalized(group.orEmpty()) }else emptyList()
        val key=catalogPositionKey()
        var query = BrowseQuery(playlistUrl,selectedKind,favoritesOnly,group,search,openedSeriesId,selectedSeason,includeAdult=adultUnlocked && (group==ADULT_GROUP || favoritesOnly || selectedKind==null),groupAliases=aliases)
        filterJob = viewModelScope.launch {
            filtering = true
            if(debounce)delay(180)
            val old=browseQuery
            val same=old.copy(initialOffset=0)==query
            val anchor=catalogAnchors[key]
            val offset=if(same)old.initialOffset else if(anchor!=null)withContext(Dispatchers.IO) {repository.catalogOffset(query,anchor.item)} ?: 0 else 0
            query=query.copy(initialOffset=offset)
            applyBrowseQuery(query)
            visibleCount = withContext(Dispatchers.IO) { repository.matchingCount(query) }
            filtering = false
        }
    }
    /** Lookup runs off the main thread; no full catalog is loaded for group artwork. */
    suspend fun groupLogo(source: String, group: String): String = withContext(Dispatchers.IO) {
        repository.groupLogo(source, group)
    }
    fun groups(): List<String> {
        val raw=if(selectedKind==null)catalog.groups.values.flatten().distinct() else catalog.groups[selectedKind].orEmpty()
        if(selectedKind==MediaKind.MOVIES)return orderedMovieCategories(raw)
        return raw.filterNot(::isAdultGroup).sorted() + if(raw.any(::isAdultGroup))listOf(ADULT_GROUP)else emptyList()
    }
    suspend fun animeCover(series: SeriesEntry): String? {
        if(!series.anime || series.adult)return null
        val source=playlistUrl;val key=source+":"+series.id
        val request=coverRequests[key] ?: run {
            if(coverRequests.size>=32)return null
            viewModelScope.async { artwork.anime(source,series.title) }.also { task->
                coverRequests[key]=task
                task.invokeOnCompletion { viewModelScope.launch { if(coverRequests[key]===task)coverRequests.remove(key) } }
            }
        }
        return request.await()
    }
    suspend fun footballArtwork(details: FootballDetails): FootballArtwork = viewModelScope.async {artwork.football(details)}.await()
    suspend fun synopsis(channel: Channel?, series: SeriesEntry?, force: Boolean = false): Synopsis {
        if(channel?.adult==true && !adultUnlocked || series?.adult==true && !adultUnlocked)return Synopsis()
        return synopses.load(playlistUrl,channel,series,force)
    }
    private fun requireParental(action: () -> Unit) { parentalRequestId++;protectedAction=action;changePassword=false;parentalError=null;parentalDialog=true }
    fun editParental() { parentalRequestId++;protectedAction=null;changePassword=true;parentalError=null;parentalDialog=true }
    fun dismissParental() { parentalRequestId++;parentalDialog=false;protectedAction=null;parentalError=null }
    fun submitParental(password: String, newPassword: String = "", confirmation: String = "") {
        if(parentalBusy)return
        val requestId=parentalRequestId
        viewModelScope.launch {
            parentalBusy=true
            try {
                val setting=changePassword || !parentalConfigured
                if(setting && (newPassword.length !in 4..64 || newPassword!=confirmation)) {
                    parentalError=preferences.text(R.string.password_mismatch);return@launch
                }
                val ok=withContext(Dispatchers.IO) { if(setting)preferences.setPassword(password,newPassword)else preferences.verifyPassword(password) }
                parentalConfigured=preferences.hasPassword()
                if(requestId!=parentalRequestId || !parentalDialog)return@launch
                if(!ok) { parentalError=preferences.text(if(preferences.blockedUntil()>System.currentTimeMillis())R.string.password_wait else R.string.wrong_password);return@launch }
                parentalConfigured=preferences.hasPassword();adultUnlocked=!changePassword
                val action=protectedAction;parentalDialog=false;protectedAction=null;parentalError=null;changePassword=false
                updateFilter();action?.invoke()
            } finally { parentalBusy=false }
        }
    }
    fun chooseLanguage(tag: String) { viewModelScope.launch {
        if(withContext(Dispatchers.IO) { preferences.saveLanguage(tag) }) {
            language=tag
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag))
        }
    } }
    fun chooseAppearance(value: AppAppearance) {viewModelScope.launch {
        if(withContext(Dispatchers.IO) {preferences.saveAppearance(value)})appearance=value
    }}
    fun setGuideAddress(address: String) {
        val clean=address.trim()
        if(clean.isNotBlank() && !PlaylistRepository.validUrl(clean)) { message=preferences.text(R.string.invalid_url);return }
        val source=playlistUrl
        viewModelScope.launch {
            if(withContext(Dispatchers.IO) { preferences.saveEpgUrl(clean,source) } && source==playlistUrl) {
                guideJob?.cancelAndJoin();epgOverride=clean;refreshGuide(true)
            }
        }
    }
    fun showFootball() { page=Page.FOOTBALL;refreshGuide() }
    fun refreshFootball() {refreshGuide(true);viewModelScope.launch {updateFootballScores(force=true)}}
    fun playFootball(channel: Channel) {play(channel);fromFootball=true}
    private suspend fun updateFootballScores(force: Boolean = false) = scoreRefresh.withLock {
        if(!foreground || page!=Page.FOOTBALL)return@withLock
        val source=playlistUrl
        footballScoresLoading=true
        try {
            val matches=football
            val feed=scores.load(matches,force)
            val confirmed=withContext(Dispatchers.IO) {scores.confirmedBroadcasts(source,matches,feed)}
            if(source==playlistUrl) {footballFeed=feed;if(football==matches)football=confirmed}
        } catch(e: CancellationException) {throw e} catch(_: Exception) {footballFeed=footballFeed.copy(failed=true)}
        finally {footballScoresLoading=false}
    }
    fun isFavorite(channel: Channel) = favorites.any {catalogContentKey(it)==catalogContentKey(channel.url)}
    fun toggleFavorite(channel: Channel) {
        if(channel.kind==MediaKind.SERIES) {
            val id=seriesIdentity(channel)
            toggleSeriesFavorite(SeriesEntry(id.id,id.title,channel.seriesImageUrl,setOf(channel.group),emptyList(),adult=channel.adult))
            return
        }
        val before = pendingFavorites ?: favorites
        val matching=before.filter {catalogContentKey(it)==catalogContentKey(channel.url)}.toSet()
        val next = if(matching.isNotEmpty())before-matching else before+channel.url
        pendingFavorites = next
        viewModelScope.launch {
            try {
                favoriteWrites.withLock {
                    withContext(NonCancellable + Dispatchers.IO) { repository.saveFavorites(next) }
                    favorites = withContext(Dispatchers.IO) {repository.favorites()}
                    if(pendingFavorites==next)pendingFavorites = null
                    if(favoritesOnly) { repository.invalidatePages(); updateFilter() }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(pendingFavorites==next)pendingFavorites=null;message=preferences.text(R.string.load_failed) }
        }
    }
    fun toggleSeriesFavorite(series: SeriesEntry) {
        if(series.adult && !adultUnlocked) { requireParental { toggleSeriesFavorite(series) };return }
        val source=playlistUrl
        val before=pendingSeriesFavorites?.takeIf {it.first==source}?.second ?: seriesFavorites
        val next=if(series.id in before)before-series.id else before+series.id
        pendingSeriesFavorites=source to next
        viewModelScope.launch {
            try { favoriteWrites.withLock {
                withContext(NonCancellable+Dispatchers.IO) { repository.saveSeriesFavorites(source,next) }
                if(source==playlistUrl) {
                    seriesFavorites=next
                    if(favoritesOnly) { repository.invalidatePages();updateFilter() }
                }
                if(pendingSeriesFavorites==(source to next))pendingSeriesFavorites=null
            } } catch(e: CancellationException) {throw e}
            catch(_: Exception) {if(pendingSeriesFavorites==(source to next))pendingSeriesFavorites=null;message=preferences.text(R.string.load_failed)}
        }
    }
    private fun refreshGuide(force: Boolean = false) {
        if(guideJob?.isActive==true)return
        val source = epgOverride.ifBlank { epgUrl.ifBlank { ProviderApi.epgEndpoint(playlistUrl).orEmpty() } }
        val playlist=playlistUrl
        if(source.isBlank()) { guideLoading=false;guideError=true;return }
        guideJob = viewModelScope.launch {
            guideLoading=true
            try {
                val channels=withContext(Dispatchers.IO) { repository.liveChannels(playlist) }
                football=footballMatches(guide,channels)
                val checked=withContext(Dispatchers.IO) { repository.guideChecked(playlist) }
                if(force || checked==0L || System.currentTimeMillis()-checked>=6*3600000L) {
                    val fresh=withContext(Dispatchers.IO) { loadGuide(source,channels.flatMap {listOf(it.tvgId,normalized(it.name),guideChannelName(it.name))}.filter(String::isNotBlank).toSet()) }
                    if(fresh.names.isNotEmpty() || fresh.programmes.isNotEmpty()) {
                        withContext(Dispatchers.IO) { repository.saveGuide(playlist,fresh) }
                        if(playlistUrl==playlist) { guide=fresh;football=footballMatches(fresh,channels);guideError=false }
                    } else guideError=true
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { guideError=true }
            finally { guideLoading=false }
        }
    }

    fun play(channel: Channel) {
        if(channel.adult && !adultUnlocked) { requireParental { play(channel) };return }
        if(channel.kind!=MediaKind.LIVE)fromFootball=false
        if(channel.kind==MediaKind.MOVIES && (current?.kind!=MediaKind.MOVIES || playbackOrigin==null))playbackOrigin=catalogLocation()
        if(channel.kind==MediaKind.SERIES && openedSeriesId==null && seriesOrigin==null)seriesOrigin=catalogLocation()
        if (selectedKind != channel.kind) { group = null; search = ""; favoritesOnly = false }
        selectedKind = channel.kind
        if (channel.kind == MediaKind.SERIES) {
            selectedSeason = channel.seasonNumber
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
        checkpointPlayback()
        playJob?.cancel()
        playerSource=""
        playerInstance?.stop()
        playbackError = null; playbackErrorDetails = null; buffering = true; current = channel; recent = channel; page = Page.LIBRARY
        val source=playlistUrl
        viewModelScope.launch(Dispatchers.IO) { repository.saveLast(channel,source) }
        val instance=try {playerInstance ?: createPlayer().also {playerInstance=it}}
            catch(_: Exception) {buffering=false;playbackError=preferences.text(R.string.load_failed);playbackErrorDetails=playbackDiagnosticText(-1);return}
        playJob=viewModelScope.launch { try {
            val saved=if(channel.kind==MediaKind.LIVE)null else playbackPositions[source to channel.url]
                ?: withContext(Dispatchers.IO) {repository.playbackProgress(source,channel.url)}
            ensureActive()
            if(current?.url!=channel.url || playlistUrl!=source)return@launch
            val http = DefaultHttpDataSource.Factory().setUserAgent(channel.userAgent.ifBlank { "AuroraIPTV/1.7 Android" })
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                .setDefaultRequestProperties(if (channel.referrer.isBlank()) emptyMap() else mapOf("Referer" to channel.referrer))
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(getApplication(), http))
            val item = MediaItem.Builder().setUri(channel.url).setMediaId(channel.url)
            val url = channel.url.lowercase()
            val mime = channel.mimeType.ifBlank {
                when { url.contains(".m3u8") || url.contains("type=m3u8") || url.contains("output=m3u8") -> MimeTypes.APPLICATION_M3U8
                    url.contains(".mpd") -> MimeTypes.APPLICATION_MPD
                    else -> "" }
            }
            if (mime.isNotBlank()) item.setMimeType(mime)
            playerSource=source
            val mediaSource=factory.createMediaSource(item.build())
            if(channel.kind==MediaKind.LIVE)instance.setMediaSource(mediaSource)
            else instance.setMediaSource(mediaSource,saved?.resumePositionMs ?: 0L)
            instance.prepare(); instance.playWhenReady = true
        } catch(e: CancellationException) {throw e}
        catch (_: Exception) { buffering = false; playbackError = preferences.text(R.string.load_failed);playbackErrorDetails=playbackDiagnosticText(-1) } }
    }

    private fun checkpointPlayback() {
        val channel=current ?: return
        val instance=playerInstance ?: return
        if(channel.kind==MediaKind.LIVE || playerSource.isBlank() || instance.currentMediaItem?.mediaId!=channel.url ||
            !instance.isCurrentMediaItemSeekable || instance.isCurrentMediaItemLive)return
        val key=playerSource to channel.url
        val progress=PlaybackProgress(instance.contentPosition.coerceAtLeast(0),instance.contentDuration.coerceAtLeast(0),instance.playbackState==Player.STATE_ENDED)
        if(playbackPositions[key]==progress)return
        playbackPositions[key]=progress
        while(playbackPositions.size>1000)playbackPositions.remove(playbackPositions.keys.first())
        playbackWrites.trySend(PlaybackCheckpoint(playerSource,channel.url,progress))
    }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(getApplication<Application>())
        .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
        .setRenderersFactory(DefaultRenderersFactory(getApplication()).setEnableDecoderFallback(true))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1000, 2000).setTargetBufferBytes(32 * 1024 * 1024).build())
        .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING;if(state==Player.STATE_ENDED)checkpointPlayback() }
                override fun onIsPlayingChanged(playing: Boolean) { this@AuroraViewModel.isPlaying = playing;if(!playing)checkpointPlayback() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(reason==Player.DISCONTINUITY_REASON_SEEK)checkpointPlayback()
                }
                override fun onPlayerError(error: PlaybackException) {
                    checkpointPlayback()
                    buffering = false
                    playbackError = preferences.text(R.string.load_failed)
                    playbackErrorDetails = playbackDiagnosticText(error)
                }
            })
        }

    fun retry() { current?.let(::play) }
    fun stop() { checkpointPlayback();playJob?.cancel();playerSource="";playerInstance?.release(); playerInstance = null; current = null; fullscreen = false; playbackError = null; playbackErrorDetails = null; buffering = false; isPlaying = false; resumePlayback = false;playbackOrigin=null;fromFootball=false }
    fun foreground(active: Boolean) {
        foreground = active
        if (active && initialized && playlistUrl.isNotBlank()) refreshDaily()
        if (!active) {
            checkpointPlayback()
            catalogAnchors.keys.removeAll {it.group==ADULT_GROUP}
            if(seriesOrigin?.group==ADULT_GROUP)seriesOrigin=null
            if(playbackOrigin?.group==ADULT_GROUP)playbackOrigin=null
            adultUnlocked=false;dismissParental()
            if(current?.adult==true)stop()
            if(recent?.adult==true)recent=null
            if(group==ADULT_GROUP || openedSeries?.adult==true) {group=null;openedSeries=null;openedSeriesId=null;selectedSeason=null}
            updateFilter()
            resumePlayback = playerInstance?.playWhenReady == true; playerInstance?.pause() }
        else if (resumePlayback) { playerInstance?.play(); resumePlayback = false }
    }
    override fun onCleared() {
        checkpointPlayback();playerSource="";playbackWrites.close()
        playerInstance?.release()
        val pending = viewModelScope.coroutineContext[Job]
        CoroutineScope(Dispatchers.IO).launch { pending?.join();playbackWriteJob.join();repository.close();playbackPersistence.cancel() }
    }
}
