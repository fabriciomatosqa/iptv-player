package br.com.fabriciomatos.iptvplayer

/** The catalog behind a detail/player screen, including its original filters. */
data class CatalogLocation(val page: Page, val kind: MediaKind?, val favorites: Boolean, val group: String?, val search: String)
data class CatalogPositionKey(val source: String, val kind: MediaKind?, val favorites: Boolean, val group: String?, val search: String,
    val series: String?, val season: Int?)
data class CatalogAnchor(val item: String, val offset: Int, val index: Int)
