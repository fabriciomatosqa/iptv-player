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
 * Fallback brand identities only. Provider logos always have priority:
 * "GLOBO | CAPITAIS" and "GLOBO | REGIONAIS" keep independent group IDs.
 *
 * URLs are fallback PNG renderings of identified Wikimedia Commons logos;
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
internal data class ChannelLogoCandidate(val name: String, val logo: String)

/** Group IDs are never rewritten: normalizing only helps match logo identity. */
private fun groupCore(group: String): String = normalized(groupDisplayName(group))
    .replace(Regex("""^(?:(?:ppv|pay per view)\s+)+"""),"")
    .replace(Regex("""^(?:(?:tv|rede)\s+)+"""),"")
    .replace(Regex("""\b(?:4k|uhd|fhd|full hd|sd|1080p|720p)\b""")," ")
    .replace(Regex("""\s+""")," ").trim()

private fun groupAliases(core: String): List<String> = when(core) {
    "insports" -> listOf("nsports", "in sports")
    "cazetv" -> listOf("caze tv", "cazetv")
    "amazon prime" -> listOf("amazon prime", "prime video", "amazon prime video")
    "bandeirantes", "band" -> listOf("band", "bandeirantes")
    else -> listOf(core)
}

/** Name match must use complete tokens, not arbitrary shared substrings. */
internal fun groupLogoMatchScore(group: String, channelName: String): Int {
    val core=groupCore(group)
    if(core.isBlank() || core in genericGroupsWithoutLogo) return 0
    val expected=channelBrand(group)
    if(expected!=null && expected==channelBrand(channelName)) return 120
    val name=normalized(channelName)
        .replace(Regex("""^(?:\d+\s+)?(?:us|br|uk|pt)\s+"""),"").trim()
    return groupAliases(core).maxOfOrNull { alias ->
        if(alias.isBlank() || alias in genericGroupsWithoutLogo) 0 else when {
            name==alias -> 110
            name.startsWith(alias+" ") -> 95
            alias.length>=3 && Regex("(?:^|\\s)"+Regex.escape(alias)+"(?:\\s|$)").containsMatchIn(name) -> 70
            else -> 0
        }
    } ?: 0
}

/** Use the very same image_url/tvg-logo already displayed on the channel cards. */
internal fun providerGroupLogo(group: String, candidates: List<ChannelLogoCandidate>,
    allowConsensus: Boolean = true): String {
    val core=groupCore(group)
    if(core.isBlank() || core in genericGroupsWithoutLogo) return ""
    val valid=candidates.filter {it.logo.startsWith("https://") || it.logo.startsWith("http://")}
    if(valid.isEmpty())return ""
    val matching=valid.filter {groupLogoMatchScore(group,it.name)>0}
    fun pick(rows: List<ChannelLogoCandidate>, ratio: Double): String {
        if(rows.isEmpty())return ""
        val counts=rows.groupingBy {it.logo}.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String,Int>> {it.value}.thenBy {it.key})
        val top=counts.first()
        return if(top.value.toDouble()/rows.size>ratio &&
            (counts.size==1 || top.value>counts[1].value))top.key else ""
    }
    val reliable=pick(matching,if(matching.size==1)0.0 else 0.5)
    if(reliable.isNotBlank())return reliable
    // Generic or inconsistent channel names are acceptable only with broad
    // agreement on the image, never when a group mixes unrelated channel logos.
    return if(allowConsensus && valid.size>=2)pick(valid,0.66) else ""
}

/** A sports LEAGUE may use a provider image only when a channel explicitly
 * names that league. Never display an ESPN logo as if it were an NHL crest. */
internal fun sportsLeagueLogo(competition: String, channels: List<Channel>): String =
    providerGroupLogo(competition,channels.map {ChannelLogoCandidate(it.name,it.imageUrl)},false)
