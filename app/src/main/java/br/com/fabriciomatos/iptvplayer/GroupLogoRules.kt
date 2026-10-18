package br.com.fabriciomatos.iptvplayer

/** Remove decorative provider diamonds from display only. Never rewrite stored group IDs. */
private val groupDiamond = Regex("\\s*♦[\\uFE0E\\uFE0F]?\\s*")
internal fun groupDisplayName(label: String): String = label.replace(groupDiamond, " ").trim()

private val genericGroupsWithoutLogo = setOf(
    "canais", "ao vivo", "live", "brasil", "nacionais", "internacionais",
    "esportes", "filmes", "series", "infantil", "musica", "variedades",
    "outros", "todos", "4k", "uhd", "hd", "fhd", "sd"
)

/**
 * Exact, opt-in brand identities. Only the visual identity is shared:
 * "GLOBO | CAPITAIS" and "GLOBO | REGIONAIS" keep independent group IDs.
 *
 * The URLs are fixed PNG renderings of identified Wikimedia Commons logos;
 * Coil handles network loading and disk caching. No provider data is transmitted.
 * These are Wikimedia thumbnails, not the gallery's HTML pages or unsupported SVG files.
 */
internal enum class ChannelBrand(val logoUrl: String, val darkBackground: Boolean = false) {
    GLOBO("https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1f/TV_Globo_logo_%28April_2025%29.png/250px-TV_Globo_logo_%28April_2025%29.png"),
    SBT("https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ed/Logotipo_do_SBT_%282025%29.png/250px-Logotipo_do_SBT_%282025%29.png"),
    PRIME_VIDEO("https://thumb.wikimedia.org/wikipedia/commons/thumb/c/ca/Amazon_Prime_Video_logo_%282024%29.svg/250px-Amazon_Prime_Video_logo_%282024%29.svg.png"),
    DISNEY_PLUS("https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/Disney%2B_2024.svg/330px-Disney%2B_2024.svg.png"),
    DAZN("https://thumb.wikimedia.org/wikipedia/commons/thumb/9/92/DAZN_LOGO_SVG.svg/250px-DAZN_LOGO_SVG.svg.png", true),
    CAZETV("https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9b/Caz%C3%A9TV_wordmark.svg/250px-Caz%C3%A9TV_wordmark.svg.png"),
    GOAT("https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5a/LOGO_COMPLETO_PRETO.png/330px-LOGO_COMPLETO_PRETO.png")
}

/**
 * Identify brands only at the beginning of the meaningful title. Avoid false
 * positives like GloboNews, SBT News, "Disney Channel", and an unrelated GOAT.
 */
internal fun channelBrand(label: String): ChannelBrand? {
    val raw=groupDisplayName(label)
    var key=normalized(raw)
    if(key.isBlank() || key in genericGroupsWithoutLogo) return null
    key=key.replace(Regex("""^(?:(?:ppv|pay per view)\s+)+"""),"")
        .replace(Regex("""^(?:(?:tv|rede)\s+)+"""),"").trim()
    fun exactPrefix(value: String) = key==value || key.startsWith("$value ")
    return when {
        exactPrefix("globo") && !Regex("""^globo (?:news|noticias|play|filmes|premium|gold|internacional)\b""").containsMatchIn(key) ->
            ChannelBrand.GLOBO
        exactPrefix("sbt") && !Regex("""^sbt (?:news|noticias)\b""").containsMatchIn(key) ->
            ChannelBrand.SBT
        exactPrefix("amazon prime") || exactPrefix("prime video") || exactPrefix("amazon prime video") ->
            ChannelBrand.PRIME_VIDEO
        (exactPrefix("disney") && raw.contains('+')) || exactPrefix("disney plus") ->
            ChannelBrand.DISNEY_PLUS
        exactPrefix("dazn") -> ChannelBrand.DAZN
        exactPrefix("cazetv") || exactPrefix("caze tv") -> ChannelBrand.CAZETV
        (key=="goat" || Regex("""^goat (?:hd|fhd|4k|uhd|sd|live|ao vivo|jogos|esportes|futebol|ppv)\b""").containsMatchIn(key) ||
            exactPrefix("canal goat")) -> ChannelBrand.GOAT
        else -> null
    }
}

internal fun groupBrandLogoUrl(group: String): String = channelBrand(group)?.logoUrl.orEmpty()

/**
 * Unknown brands may still use a logo supplied by their own M3U channel.
 * Compare meaningful brand names and never take arbitrary artwork from a group.
 */
internal fun groupLogoMatchScore(group: String, channelName: String): Int {
    val key = normalized(groupDisplayName(group))
        .replace(Regex("\\b(?:hd|fhd|sd|4k|uhd)\\b"), "").trim()
    if (key.isBlank() || key in genericGroupsWithoutLogo) return 0
    val groupBrand = channelBrand(group)
    if(groupBrand!=null) {
        return if(channelBrand(channelName)==groupBrand) 100 else 0
    }
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
