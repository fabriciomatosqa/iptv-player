package br.com.fabriciomatos.iptvplayer

const val ADULT_GROUP = "__aurora_adult__"
fun isAdultGroup(group: String): Boolean = Regex("\\b(adultos?|adults?|xxx|porn[oô]?|pornografia|hentai|erotico|erotica)\\b").containsMatchIn(normalized(group)) ||
    Regex("(?i)(?:\\+\\s*18|18\\s*\\+|\\bnsfw\\b)").containsMatchIn(group)
fun isAnimeGroup(group: String) = Regex("\\b(crunchyroll|animes?|funimation|hidive)\\b").containsMatchIn(normalized(group))
fun canonicalAnimeTitle(title: String): String = normalized(title.replace(Regex("\\s*[\\[(](?:19|20)\\d{2}[\\])]\\s*$"), "")
    .replace(Regex("(?i)\\s*[\\[(](?:l|leg|dub|hd|fhd|4k)[\\])]\\s*$"), ""))

fun isFootballProgramme(title: String, category: String = ""): Boolean {
    val text = normalized("$title $category")
    if (Regex("\\b(americano|american|nfl|rugby|basquete|basketball|baseball|beisebol)\\b").containsMatchIn(text)) return false
    if (Regex("\\b(resenha|debate|mesa redonda|central do|noticias|jornal|sportcenter|sportscenter|gols da rodada)\\b").containsMatchIn(text)) return false
    val competition = Regex("\\b(brasileirao|libertadores|sul americana|champions league|liga dos campeoes|premier league|la liga|laliga|bundesliga|copa do brasil|copa do mundo|serie a italiana|campeonato (?:brasileiro|paulista|carioca|ingles|espanhol|portugues|italiano|alemao))\\b").containsMatchIn(text)
    val teams = Regex("(?i)\\s+(?:x|vs\\.?|v\\.?|versus)\\s+").containsMatchIn(title)
    return competition || (Regex("\\b(futebol|soccer|football)\\b").containsMatchIn(text) && (teams || !Regex("\\b(programa|revista|documentario|melhores momentos)\\b").containsMatchIn(text)))
}
