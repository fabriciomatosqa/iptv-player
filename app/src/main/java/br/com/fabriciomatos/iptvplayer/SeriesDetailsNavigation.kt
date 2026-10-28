package br.com.fabriciomatos.iptvplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow

/** Retain the title, section and carousel state while fullscreen temporarily replaces the library. */
internal class SeriesDetailsNavigation {
    var series by mutableStateOf<SeriesEntry?>(null)
        private set
    var source by mutableStateOf("")
        private set
    var episodesVisible by mutableStateOf(false)
    var scroll=LazyListState()
        private set
    private val rows=mutableMapOf<Int,LazyListState>()
    private val pages=mutableMapOf<Int,Flow<PagingData<Channel>>>()
    private var pagesScope: CoroutineScope?=null
    fun pages(season: Int, owner: CoroutineScope, create: ()->Flow<PagingData<Channel>>): Flow<PagingData<Channel>> =
        pages.getOrPut(season) {
            val scope=pagesScope ?: CoroutineScope(owner.coroutineContext+SupervisorJob(owner.coroutineContext[Job]))
                .also {pagesScope=it}
            create().cachedIn(scope)
        }
    fun row(season: Int)=rows.getOrPut(season) {LazyListState()}
    fun open(entry: SeriesEntry, playlist: String, episodes: Boolean = false) {
        if(series?.id!=entry.id || source!=playlist) {
            episodesVisible=episodes;scroll=LazyListState();rows.clear();clearPages()
        }
        series=entry;source=playlist
    }
    fun forSource(playlist: String) = series.takeIf {source==playlist}
    private fun clearPages() {pagesScope?.cancel();pagesScope=null;pages.clear()}
    fun close() {series=null;source="";episodesVisible=false;rows.clear();scroll=LazyListState();clearPages()}
}

internal fun AuroraViewModel.closeSeriesDetails() {
    dismissPlaybackResume()
    if(openedSeriesId!=null)closeSeries() else seriesDetails.close()
}

internal fun AuroraViewModel.showSeriesCast() {
    dismissPlaybackResume()
    seriesDetails.episodesVisible=false
}
