package br.com.fabriciomatos.iptvplayer

data class FootballArtwork(val competition: String = "", val home: String = "", val away: String = "")

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
    if(footballAgeGroup(competition).isNotBlank()) return null
    val rule = footballCompetitionRule(competition) ?: return null
    return when(footballGender(competition)) {
        FootballGender.WOMEN -> if(rule.id == "fifa.wwc") "fifa.wwc" else null
        FootballGender.CONFLICT -> null
        else -> rule.scoreLeague
    }
}
