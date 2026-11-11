package br.com.fabriciomatos.iptvplayer

const val ADULT_GROUP = "__aurora_adult__"
private val adultWords = Regex("\\b(adultos?|adults?|xxx|porn[oô]?|pornografia|hentais?|erotico|erotica)\\b")
private val adultMarker = Regex("(?i)(?:\\+\\s*18|18\\s*\\+|\\bnsfw\\b)")
private val animeGroups = Regex("\\b(crunchyroll|animes?|funimation|hidive)\\b")
fun isAdultGroup(group: String): Boolean = adultWords.containsMatchIn(normalized(group)) || adultMarker.containsMatchIn(group) || group.contains("🔞")
fun isAnimeGroup(group: String) = animeGroups.containsMatchIn(normalized(group))
fun canonicalAnimeTitle(title: String): String = normalized(title.replace(Regex("\\s*[\\[(](?:19|20)\\d{2}[\\])]\\s*$"), "")
    .replace(Regex("(?i)\\s*[\\[(](?:l|leg|dub|hd|fhd|4k)[\\])]\\s*$"), ""))

data class FootballDetails(val competition: String, val home: String, val away: String,
    val gender: FootballGender = footballGender(competition).takeUnless { it == FootballGender.UNKNOWN } ?: footballGender("$home $away"),
    val ageGroup: String = footballAgeGroup("$competition $home $away"), val country: String = "")
private val matchSeparator = Regex("(?i)\\s+(?:x|×|vs\\.?|v\\.?|versus)\\s+")

/** Interpret evidence before removing any prefix from display names. Club names alone are never gender evidence. */
fun footballDetails(title: String, category: String = "", metadata: FootballMetadata = FootballMetadata(),
    channelNames: List<String> = emptyList(), playlistGroups: List<String> = emptyList()): FootballDetails? {
    val fixtureTitle=footballFixtureTitle(title,metadata)
    val text = normalized("$fixtureTitle $category ${metadata.sport}")
    // A head-to-head fixture does not imply soccer. Source sport and competition
    // evidence take precedence, including longer EPG descriptions of the NHL.
    if (detectOtherSport(Programme(title,0L,1L,category=category,metadata=metadata)) != null) return null
    if (Regex("\\b(rugby|ufc|bjj|futsal)\\b").containsMatchIn(text)) return null
    if (Regex("\\b(resenha|debate|mesa redonda|central do|noticias|jornal|sportcenter|sportscenter|gols da rodada|melhores momentos|documentario|40 minutos|entrevista|bastidores|pre jogo|pos jogo|pre game|post game)\\b").containsMatchIn(normalized("$title $fixtureTitle $category"))) return null
    val clean=fixtureTitle.replace(Regex("(?i)\\s*[\\[(](?:reprise|replay|ao vivo|live)[\\])]\\s*")," ")
        .replace(Regex("(?i)^(?:reprise|replay|vt)\\s*(?::|-)?\\s+|\\s+[-–—]\\s+(?:reprise|replay|ao vivo|live)$"),"")
    val parts = clean.split(Regex("[║|]+|\\s+·\\s+")).map(String::trim).filter(String::isNotBlank)
    val main = parts.firstOrNull { matchSeparator.containsMatchIn(it) } ?: return null
    val separator = matchSeparator.find(main) ?: return null
    var home = main.substring(0, separator.range.first).trim()
    var away = main.substring(separator.range.last + 1).trim()
    val titleCompetitions = mutableListOf<String>()
    val heading = Regex("^(.+?)\\s+(?:-|–|—|:)\\s+(.+)$").matchEntire(home)
    if (heading != null && isCompetitionLabel(heading.groupValues[1])) {
        titleCompetitions.add(heading.groupValues[1].trim())
        home = heading.groupValues[2].trim()
        if(home.contains(':')) home=home.substringAfterLast(':').trim()
    } else if (home.contains(':')) {
        val prefix = home.substringBeforeLast(':').trim()
        if (isCompetitionLabel(prefix)) titleCompetitions.add(prefix)
        home = home.substringAfterLast(':').trim()
    }
    val trailing = Regex("\\s+[-–—]\\s+").find(away)
    if(trailing != null) {
        val suffix = away.substring(trailing.range.last + 1).trim()
        if(isCompetitionLabel(suffix)) { titleCompetitions.add(suffix); away = away.substring(0,trailing.range.first).trim() }
    }
    titleCompetitions.addAll(parts.filter { !matchSeparator.containsMatchIn(it) && isCompetitionLabel(it) })
    val rawCompetitions = listOf(metadata.originalTitle,metadata.subtitle).mapNotNull { raw ->
        if(hasFootballMatchup(raw)) footballDetails(raw,category)?.competition?.takeIf(String::isNotBlank)
        else raw.takeIf(::isCompetitionLabel)
    }
    val explicitCompetition = metadata.competition.takeIf(::isCompetitionValue).orEmpty()
    val describedCompetition = footballCompetitionMention(metadata.description).orEmpty()
    val identifiedCompetition = (titleCompetitions + listOf(explicitCompetition) + rawCompetitions + listOf(category,describedCompetition) + playlistGroups + channelNames)
        .firstOrNull { it == explicitCompetition && it.isNotBlank() || isCompetitionLabel(it) }.orEmpty()
    val competition = identifiedCompetition + metadata.division.takeIf { it.isNotBlank() && !normalized(identifiedCompetition).contains(normalized(it)) }?.let { " — $it" }.orEmpty()
    val evidence = listOf(titleCompetitions.joinToString(" "), metadata.competition + " " + metadata.gender,
        listOf(title,metadata.originalTitle,metadata.subtitle,metadata.description,category,metadata.retainedEvidence).joinToString(" "),
        playlistGroups.joinToString(" "), channelNames.joinToString(" "))
    val gender = evidence.map(::footballGender).firstOrNull { it != FootballGender.UNKNOWN } ?: FootballGender.UNKNOWN
    val footballContext = competition.isNotBlank() || Regex("\\b(futebol|soccer|football)\\b").containsMatchIn(text) ||
        (playlistGroups + channelNames).any { Regex("\\b(futebol|soccer|football)\\b").containsMatchIn(normalized(it)) } || Regex("\\b(futebol|soccer|football)\\b").containsMatchIn(normalized(metadata.description))
    if(!footballContext) return null
    if (home.length !in 2..80 || away.length !in 2..80 || normalized(home) == normalized(away) ||
        footballCompetitionRule(home) != null || footballCompetitionRule(away) != null) return null
    return FootballDetails(competition, home, away, gender,
        footballAgeGroup("$competition $home $away ${metadata.division}"),metadata.country)
}
/** Descriptions are accepted only as one short, unambiguous matchup line. */
internal fun footballFixtureTitle(title: String, metadata: FootballMetadata): String {
    if(hasFootballMatchup(title))return title
    val candidates=metadata.description.lineSequence().map(String::trim)
        .filter {it.length in 7..160 && matchSeparator.findAll(it).count()==1}
        .filter { !it.contains(". ") && !it.contains('!') && !it.contains('?') }.toList()
    return candidates.singleOrNull() ?: title
}
internal fun footballProgrammeCandidate(p: Programme) = hasFootballMatchup(footballFixtureTitle(p.title,p.metadata))
fun hasFootballMatchup(title: String) = matchSeparator.containsMatchIn(title)
fun isFootballProgramme(title: String, category: String = "") = footballDetails(title, category) != null
