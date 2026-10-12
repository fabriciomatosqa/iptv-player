package br.com.fabriciomatos.iptvplayer

fun animeTitleKey(title: String) = canonicalAnimeTitle(title).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
fun animeTitleScore(request: String, candidate: String): Int {
    val a=animeTitleKey(request);val b=animeTitleKey(candidate)
    if(a==b)return 100
    // Some catalogs truncate very long Japanese names; never guess from a short prefix.
    if(a.length>=60 && b.length>=60 && (a.startsWith(b)||b.startsWith(a))) {
        return if(minOf(a.length,b.length).toDouble()/maxOf(a.length,b.length)>=0.92)95 else 0
    }
    if(animeTitleKey(candidate.substringBefore(':'))==a)return 98
    return 0
}
fun teamKey(name: String) = normalized(name).replace(Regex("[^\\p{L}\\p{N}]+")," ")
    .replace(Regex("^(?:afc|fc|sc)\\s+|\\s+(?:afc|fc|sc)$"),"").trim()
fun footballLeagueSlug(competition: String): String? {
    val key=normalized(competition)
    return when {
        key.contains("brasileiro") || key.contains("brasileirao") -> if(Regex("serie\\s*b\\b").containsMatchIn(key))"bra.2" else "bra.1"
        key.contains("libertadores") -> "conmebol.libertadores"
        key.contains("sulamericana") || key.contains("sul americana") || key.contains("sudamericana") -> "conmebol.sudamericana"
        key.contains("copa do brasil") -> "bra.copa_do_brazil"
        key.contains("copa do nordeste") -> "bra.copa_do_nordeste"
        key.contains("champions league") || key.contains("liga dos campeoes") -> "uefa.champions"
        key.contains("europa league") || key.contains("liga europa") -> "uefa.europa"
        key.contains("conference league") -> "uefa.europa.conf"
        key.contains("premier league") || key.contains("campeonato ingles") -> "eng.1"
        key.contains("la liga") || key.contains("laliga") || key.contains("campeonato espanhol") -> "esp.1"
        key.contains("bundesliga") || key.contains("campeonato alemao") -> "ger.1"
        key.contains("ligue 1") || key.contains("campeonato frances") -> "fra.1"
        key.contains("italian") -> "ita.1"
        key.contains("portugues") || key.contains("primeira liga") -> "por.1"
        key.contains("campeonato paulista") -> "bra.camp.paulista"
        key.contains("campeonato carioca") -> "bra.camp.carioca"
        key.contains("copa do mundo") -> "fifa.world"
        else -> null
    }
}
