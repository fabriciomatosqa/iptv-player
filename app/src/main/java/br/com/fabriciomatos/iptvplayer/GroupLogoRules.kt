package br.com.fabriciomatos.iptvplayer

/** Strip provider-supplied diamond decoration from the UI, never from the saved group key. */
private val groupDiamond = Regex("\\s*♦[\\uFE0E\\uFE0F]?\\s*")
internal fun groupDisplayName(label: String): String = label.replace(groupDiamond, " ").trim()

private val genericGroupsWithoutLogo = setOf(
    "canais", "ao vivo", "live", "brasil", "nacionais", "internacionais",
    "esportes", "filmes", "series", "infantil", "musica", "variedades",
    "outros", "todos", "4k", "uhd", "hd", "fhd", "sd"
)

/**
 * Do not choose an arbitrary channel image for mixed-provider groups.
 * Recognize a logo only when a channel has a matching brand/name.
 */
internal fun groupLogoMatchScore(group: String, channelName: String): Int {
    val key = normalized(groupDisplayName(group))
        .replace(Regex("\\b(?:hd|fhd|sd|4k|uhd)\\b"), "").trim()
    if (key.isBlank() || key in genericGroupsWithoutLogo) return 0
    val aliases = when {
        key.contains("bandeirantes") || key == "band" -> listOf("band", "bandeirantes")
        key.contains("premiere") -> listOf("premiere")
        key.contains("record") -> listOf("record")
        key.contains("rede globo") -> listOf("globo")
        key == "sbt" || key.contains("sistema brasileiro de televisao") -> listOf("sbt")
        key.startsWith("rede ") -> listOf(key.removePrefix("rede "))
        key.startsWith("tv ") -> listOf(key.removePrefix("tv "))
        else -> listOf(key)
    }
    val name = normalized(channelName)
    return aliases.maxOfOrNull { alias ->
        when {
            name == alias -> 100
            name.startsWith("$alias ") -> 90
            name.contains(" $alias ") -> 60
            else -> 0
        }
    } ?: 0
}
