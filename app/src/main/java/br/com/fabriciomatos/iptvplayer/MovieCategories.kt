package br.com.fabriciomatos.iptvplayer

const val RECENT_GROUP = "__aurora_recent__"
const val RELEASES_2026_GROUP = "__aurora_releases_2026__"
const val UHD_GROUP = "__aurora_4k__"

/** Equivalent provider spellings share a tab; their original rows are kept. */
fun movieCategory(group: String): String {
    if (group in setOf(RECENT_GROUP, RELEASES_2026_GROUP, UHD_GROUP, ADULT_GROUP)) return group
    if (isAdultGroup(group)) return ADULT_GROUP
    val clean = group.replace(Regex("[^\\p{L}\\p{N} +]"), " ").replace(Regex("\\s+"), " ").trim()
    val key = normalized(clean).replace(Regex("^(?:filmes?|movies?)\\s+"), "")
    return when {
        key in setOf("update", "updates", "recentes", "adicionado recentemente", "adicionados recentemente", "recently added") -> RECENT_GROUP
        key.contains("2026") && key.contains("lancamento") -> RELEASES_2026_GROUP
        Regex("\\b(?:4k|uhd)\\b").containsMatchIn(key) -> UHD_GROUP
        key in setOf("documentario", "documentarios", "documentary", "documentaries") -> "Documentários"
        key in setOf("ficcao", "ficcao cientifica", "sci fi", "science fiction") -> "Ficção científica"
        key == "drama" -> "Drama"
        key == "acao" -> "Ação"
        key == "comedia" -> "Comédia"
        key == "animacao" -> "Animação"
        else -> clean.replace(Regex("(?i)^(?:filmes?|movies?)\\s+"), "").ifBlank { group }
    }
}
fun orderedMovieCategories(groups: List<String>): List<String> =
    (groups.map(::movieCategory) + RECENT_GROUP).distinctBy(::normalized).sortedWith(compareBy<String> {
        when(it) { RECENT_GROUP -> 0; RELEASES_2026_GROUP -> 1; UHD_GROUP -> 2; ADULT_GROUP -> 4; else -> 3 }
    }.thenBy { normalized(it) })
