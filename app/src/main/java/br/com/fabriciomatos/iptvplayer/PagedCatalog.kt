package br.com.fabriciomatos.iptvplayer

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CatalogOverview(val total: Int = 0, val live: Int = 0, val movies: Int = 0, val episodes: Int = 0,
    val series: Int = 0, val groups: Map<MediaKind, List<String>> = emptyMap()) {
    fun count(kind: MediaKind?) = when (kind) { MediaKind.LIVE -> live; MediaKind.MOVIES -> movies; MediaKind.SERIES -> series; else -> total }
}
data class BrowseQuery(val source: String = "", val kind: MediaKind? = MediaKind.LIVE, val favoritesOnly: Boolean = false,
    val group: String? = null, val search: String = "", val seriesId: String? = null, val season: Int? = null, val includeAdult: Boolean = false, val revision: Int = 0)

internal class CatalogPage<T : Any>(private val read: (Int, Int) -> List<T>) : PagingSource<Int, T>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(Dispatchers.IO) {
        try {
            val offset = params.key ?: 0
            val data = read(offset, params.loadSize)
            LoadResult.Page(data, if (offset == 0) null else (offset - PAGE_SIZE).coerceAtLeast(0),
                if (data.size < params.loadSize) null else offset + data.size)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { LoadResult.Error(e) }
    }
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = state.anchorPosition?.let { anchor ->
        state.closestPageToPosition(anchor)?.let { it.prevKey?.plus(PAGE_SIZE) ?: it.nextKey?.minus(PAGE_SIZE) }
    }
    companion object { const val PAGE_SIZE = 60 }
}
