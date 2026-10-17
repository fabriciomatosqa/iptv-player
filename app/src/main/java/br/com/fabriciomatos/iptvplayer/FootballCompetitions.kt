package br.com.fabriciomatos.iptvplayer

enum class FootballGender { UNKNOWN, MEN, WOMEN, CONFLICT }
fun footballGender(text: String): FootballGender {
    val key = normalized(text)
    val women = Regex("\\b(feminin[oa]s?|femenin[oa]s?|women(?: s)?|female|ladies)\\b").containsMatchIn(key)
    val men = Regex("\\b(masculin[oa]s?|men(?: s)?|male)\\b").containsMatchIn(key)
    return when { women && men -> FootballGender.CONFLICT; women -> FootballGender.WOMEN; men -> FootballGender.MEN; else -> FootballGender.UNKNOWN }
}

data class FootballCompetitionRule(val id: String, val title: String, val aliases: String,
    val group: Int, val priority: Int, val scoreLeague: String? = id)
data class FootballCategoryPriority(val group: Int, val priority: Int)

/** One configurable table. Aliases match whole competition names, never arbitrary substrings. */
val footballCompetitionRules = listOf(
    FootballCompetitionRule("bra.1", "Brasileirão", "brasileirao(?: serie a)?|campeonato brasileiro(?: serie a)?", 1, 10),
    FootballCompetitionRule("bra.2", "Brasileirão — Série B", "(?:brasileirao|campeonato brasileiro) serie b", 1, 11),
    FootballCompetitionRule("conmebol.libertadores", "Libertadores", "(?:copa )?(?:conmebol )?libertadores(?: da america)?", 1, 20),
    FootballCompetitionRule("conmebol.sudamericana", "Sul-Americana", "(?:copa )?(?:conmebol )?(?:sul americana|sulamericana|sudamericana)", 1, 30),
    FootballCompetitionRule("eng.1", "Premier League — Inglaterra", "premier league|english premier league|campeonato ingles(?: primeira divisao)?", 3, 10),
    FootballCompetitionRule("esp.1", "La Liga — Espanha", "la liga|laliga|campeonato espanhol(?: primeira divisao)?", 3, 20),
    FootballCompetitionRule("ita.1", "Campeonato Italiano — Serie A", "serie a(?: italiana)?|italian serie a|campeonato italiano(?: serie a)?", 3, 30),
    FootballCompetitionRule("ger.1", "Bundesliga — Alemanha", "bundesliga|campeonato alemao(?: primeira divisao)?", 3, 40),
    FootballCompetitionRule("fra.1", "Ligue 1 — França", "ligue 1|campeonato frances(?: primeira divisao)?", 3, 50),
    FootballCompetitionRule("por.1", "Campeonato Português — Primeira Liga", "primeira liga|liga portugal|campeonato portugues(?: primeira liga)?", 3, 60),
    FootballCompetitionRule("tur.1", "Campeonato Turco", "campeonato turco|super lig|superliga turca", 4, 10),
    FootballCompetitionRule("ned.1", "Eredivisie — Holanda", "eredivisie|campeonato holandes", 4, 20),
    FootballCompetitionRule("rus.1", "Campeonato Russo", "russian premier league|campeonato russo", 4, 30),
    FootballCompetitionRule("eng.2", "Championship — Inglaterra, 2ª divisão", "(?:efl )?championship|campeonato ingles segunda divisao", 4, 40),
    FootballCompetitionRule("esp.2", "La Liga — Segunda Divisão", "(?:la liga|laliga|campeonato espanhol) (?:2|segunda divisao)", 4, 50),
    FootballCompetitionRule("ger.2", "2. Bundesliga", "2 bundesliga|bundesliga 2|campeonato alemao segunda divisao", 4, 60),
    FootballCompetitionRule("uefa.champions", "UEFA Champions League", "(?:uefa )?(?:champions league|liga dos campeoes)", 3, 70),
    FootballCompetitionRule("uefa.europa", "UEFA Europa League", "(?:uefa )?(?:europa league|liga europa)", 4, 70),
    FootballCompetitionRule("uefa.europa.conf", "UEFA Conference League", "(?:uefa )?(?:europa )?conference league", 4, 80),
    FootballCompetitionRule("bra.copa_do_brazil", "Copa do Brasil", "copa do brasil", 1, 40),
    FootballCompetitionRule("bra.copa_do_nordeste", "Copa do Nordeste", "copa do nordeste", 1, 50),
    FootballCompetitionRule("bra.camp.paulista", "Campeonato Paulista", "campeonato paulista", 1, 60),
    FootballCompetitionRule("bra.camp.carioca", "Campeonato Carioca", "campeonato carioca", 1, 70),
    FootballCompetitionRule("afc.champions", "AFC Champions League Elite", "afc champions league(?: elite)?", 5, 100),
    FootballCompetitionRule("jpn.1", "J1 League — Japão", "j1 league|campeonato japones(?: j1 league)?", 5, 100),
    FootballCompetitionRule("usa.1", "MLS — Estados Unidos", "mls|major league soccer", 5, 100),
    FootballCompetitionRule("arg.1", "Campeonato Argentino", "campeonato argentino|liga profesional(?: argentina)?", 5, 100),
    FootballCompetitionRule("mex.1", "Campeonato Mexicano", "campeonato mexicano|liga mx", 5, 100),
    FootballCompetitionRule("ksa.1", "Campeonato Saudita", "campeonato saudita|saudi pro league", 5, 100),
    FootballCompetitionRule("fifa.wwc", "Copa do Mundo", "(?:fifa )?(?:copa do mundo|world cup)", 2, 20, "fifa.world")
)
private fun competitionBase(value: String) = normalized(value)
    .replace(Regex("\\b(feminin[oa]s?|femenin[oa]s?|women(?: s)?|female|ladies|masculin[oa]s?|men(?: s)?)\\b"), " ")
    .replace(Regex("\\b(?:de )?futebol\\b|\\bfootball\\b|\\bsoccer\\b"), " ")
    .replace(Regex("\\s+"), " ").trim()
private val compiledFootballRules = footballCompetitionRules.map { it to Regex("(?:${it.aliases})") }
fun footballCompetitionRule(value: String): FootballCompetitionRule? {
    val key = competitionBase(value)
    return compiledFootballRules.firstOrNull { it.second.matches(key) }?.first
}
fun footballCompetitionRule(details: FootballDetails): FootballCompetitionRule? {
    val rule = footballCompetitionRule(details.competition) ?: return null
    if(details.country.isBlank()) return rule
    val countries = mapOf("eng" to setOf("england","inglaterra","eng","ingles"), "ita" to setOf("italy","italia","ita"),
        "bra" to setOf("brazil","brasil","bra"), "esp" to setOf("spain","espanha","esp"),
        "ger" to setOf("germany","alemanha","ger","deu"), "fra" to setOf("france","franca","fra"),
        "por" to setOf("portugal","por"), "tur" to setOf("turkey","turkiye","turquia","tur"),
        "jpn" to setOf("japan","japao","jpn"), "usa" to setOf("usa","united states","estados unidos"))
    val allowed = countries[rule.id.substringBefore('.')] ?: return rule
    return rule.takeIf { normalized(details.country) in allowed }
}
fun footballAgeGroup(value: String): String = Regex("\\b(?:sub|u|under)\\s*(\\d{2})\\b")
    .find(normalized(value))?.groupValues?.get(1).orEmpty()
fun footballCompetitionIdentity(details: FootballDetails): String {
    val base = footballCompetitionRule(details)?.id ?: competitionBase("${details.competition} ${details.country}").ifBlank { "unknown" }
    val gender = when(details.gender) { FootballGender.WOMEN -> ":women"; FootballGender.CONFLICT -> ":conflict"; else -> "" }
    return base + gender + details.ageGroup.takeIf(String::isNotBlank)?.let { ":u$it" }.orEmpty()
}
fun footballCompetitionTitle(details: FootballDetails): String {
    val title = footballCompetitionRule(details)?.title ?: listOf(details.competition,details.country).filter(String::isNotBlank).joinToString(" — ")
    return title + (if(details.gender == FootballGender.WOMEN && footballGender(title) != FootballGender.WOMEN) " Feminina" else "") +
        (if(details.ageGroup.isNotBlank() && footballAgeGroup(title).isBlank()) " Sub-${details.ageGroup}" else "")
}
fun isCompetitionValue(value: String): Boolean = normalized(value) !in setOf("", "ao vivo", "live", "jogos", "games", "futebol", "soccer", "football", "esportes", "sports", "jogos femininos")
fun isCompetitionLabel(value: String): Boolean {
    val key = normalized(value)
    if(!isCompetitionValue(value)) return false
    return footballCompetitionRule(value) != null || Regex("\\b(campeonato|copa|cup|liga|league|championship|bundesliga|torneio|taca|eliminatorias|amistoso|eurocopa|serie [abcd]|libertadores)\\b").containsMatchIn(key)
}

fun footballCategoryPriority(details: FootballDetails): FootballCategoryPriority {
    val rule = footballCompetitionRule(details)
    if(rule != null) return FootballCategoryPriority(rule.group, rule.priority)
    val europe = Regex("\\b(uefa|ingles|inglaterra|england|italiano|italia|italy|espanhol|espanha|spain|alemao|alemanha|germany|frances|franca|france|portugues|portugal|turco|turquia|holandes|holanda|russo|russia|belga|belgica|escoces|escocia)\\b")
    return FootballCategoryPriority(if(europe.containsMatchIn(normalized("${details.competition} ${details.country}"))) 4 else 5, 100)
}
