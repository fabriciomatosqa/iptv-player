package br.com.fabriciomatos.iptvplayer

const val ADULT_GROUP = "__aurora_adult__"
private val adultWords = Regex("\\b(adultos?|adults?|xxx|porn[oô]?|pornografia|hentais?|erotico|erotica)\\b")
private val adultMarker = Regex("(?i)(?:\\+\\s*18|18\\s*\\+|\\bnsfw\\b)")
private val animeGroups = Regex("\\b(crunchyroll|animes?|funimation|hidive)\\b")
fun isAdultGroup(group: String): Boolean = adultWords.containsMatchIn(normalized(group)) || adultMarker.containsMatchIn(group) || group.contains("🔞")
fun isAnimeGroup(group: String) = animeGroups.containsMatchIn(normalized(group))
fun canonicalAnimeTitle(title: String): String = normalized(title.replace(Regex("\\s*[\\[(](?:19|20)\\d{2}[\\])]\\s*$"), "")
    .replace(Regex("(?i)\\s*[\\[(](?:l|leg|dub|hd|fhd|4k)[\\])]\\s*$"), ""))

data class FootballDetails(val competition: String, val home: String, val away: String)
private val matchSeparator = Regex("(?i)\\s+(?:x|vs\\.?|v\\.?|versus)\\s+")
private val footballCompetitions = Regex("\\b(brasileirao|libertadores|sul americana|sulamericana|champions league|liga dos campeoes|premier league|la liga|laliga|bundesliga|ligue 1|liga europa|europa league|conference league|copa do brasil|copa do mundo|copa do nordeste|serie [ab] italiana|campeonato (?:brasileiro|paulista|carioca|ingles|espanhol|portugues|italiano|alemao)|futebol|soccer|football)\\b")

fun footballDetails(title: String, category: String = ""): FootballDetails? {
    val text = normalized("$title $category")
    if (Regex("\\b(americano|american|nfl|rugby|basquete|basketball|nba|baseball|beisebol|ufc|bjj|volei|voleibol|tenis|handebol|futsal)\\b").containsMatchIn(text)) return null
    if (Regex("\\b(resenha|debate|mesa redonda|central do|noticias|jornal|sportcenter|sportscenter|gols da rodada|melhores momentos|documentario|reprise|40 minutos)\\b").containsMatchIn(text)) return null
    if (!footballCompetitions.containsMatchIn(text)) return null
    // XMLTV feeds often repeat the matchup after ║, || or sub-title.
    val parts = title.split(Regex("[║|]+|\\s+·\\s+")).map(String::trim).filter(String::isNotBlank)
    val main = parts.firstOrNull { matchSeparator.containsMatchIn(it) } ?: return null
    val separator = matchSeparator.find(main) ?: return null
    var home = main.substring(0, separator.range.first).trim()
    var away = main.substring(separator.range.last + 1).trim()
    var competition = ""
    if (home.contains(':')) {
        val prefix = home.substringBeforeLast(':').trim()
        if (footballCompetitions.containsMatchIn(normalized(prefix))) competition = prefix
        home = home.substringAfterLast(':').trim()
    }
    if (away.contains(" - ")) {
        competition = away.substringAfter(" - ").trim()
        away = away.substringBefore(" - ").trim()
    }
    if (competition.isBlank()) competition = parts.firstOrNull { !matchSeparator.containsMatchIn(it) && footballCompetitions.containsMatchIn(normalized(it)) }.orEmpty()
    if (competition.isBlank() && footballCompetitions.containsMatchIn(normalized(category))) competition = category.trim()
    if (home.length !in 2..80 || away.length !in 2..80 || normalized(home) == normalized(away)) return null
    return FootballDetails(competition, home, away)
}
fun isFootballProgramme(title: String, category: String = "") = footballDetails(title, category) != null
