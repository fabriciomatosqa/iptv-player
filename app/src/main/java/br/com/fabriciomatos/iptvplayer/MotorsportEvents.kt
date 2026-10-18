package br.com.fabriciomatos.iptvplayer

/**
 * A race weekend contains separate sessions, not opposing teams.
 * The EPG provides the schedule; it does NOT provide live classifications or race results.
 */
data class MotorsportCardDetails(
    val stage: String,
    val session: String,
    val sessionId: String,
    val stageId: String
)

private val motorsportGp=Regex(
    """(?i)\b(?:grande\s+pr[eê]mio|grand\s+prix|gp)\s+((?:(?:de|do|da|dos|das|of|di)\s+)?[\p{L}][\p{L}\d\s.'-]{1,75})"""
)
private val motorsportSeparator=Regex("""\s+[-–—|·:]\s+|\s*[|·]\s*""")
private val motorsportSessionSuffix=Regex(
    """(?i)\s+\b(?:treino\s+livre|free\s+practice|practice|classifica[cç][aã]o|qualifica[cç][aã]o|qualifying|corrida|race|sprint|warm[\s-]?up|fp[1234]|tl[1234])\b.*$"""
)
private val motorsportEndurance=Regex(
    """(?i)\b(?:24\s*(?:horas|hours|h)\s+(?:de\s+|du\s+|of\s+)?le\s+mans|500\s+milhas\s+de\s+indian[aá]polis)\b"""
)

/** Title and session descriptions are display-only, never fabricated timing/position data. */
fun motorsportCardDetails(programme: Programme, competition: String): MotorsportCardDetails {
    val original=programme.metadata.originalTitle.ifBlank { programme.title }.trim()
    val evidence=listOf(original,programme.title,programme.metadata.subtitle).joinToString(" ")
    val value=normalized(evidence)

    val session=when {
        Regex("""\b(?:sprint (?:qualifying|qualificacao|classificacao|shootout)|(?:qualifying|qualificacao|classificacao|shootout) (?:da |do )?sprint)\b""").containsMatchIn(value) ->
            "Classificação Sprint" to "sprint-quali"
        Regex("""\b(?:corrida sprint|sprint race|sprint)\b""").containsMatchIn(value) ->
            "Corrida Sprint" to "sprint"
        Regex("""\b(?:qualifying|qualificacao|classificacao|quali|tomada de tempo)\b""").containsMatchIn(value) ->
            "Classificação" to "qualifying"
        Regex("""\b(?:fp|tl|treino livre|free practice|practice)\s*([1234])\b""")
            .find(value)?.let { true } == true -> {
                val number=Regex("""\b(?:fp|tl|treino livre|free practice|practice)\s*([1234])\b""")
                    .find(value)!!.groupValues[1]
                ("Treino Livre "+number) to ("practice-"+number)
            }
        Regex("""\b(?:treino|practice|warm up|warmup)\b""").containsMatchIn(value) ->
            "Treinos" to "practice"
        Regex("""\b(?:corrida|race|prova)\b""").containsMatchIn(value) ->
            "Corrida" to "race"
        else -> "Sessão não informada" to "unspecified"
    }

    val grandPrix=motorsportGp.find(original)?.groupValues?.get(1)
        ?.let { raw ->
            raw.split(motorsportSeparator).first().replace(motorsportSessionSuffix,"")
                .replace(Regex("""(?i)\s+(?:f[123]|formula\s+[123e]|motogp|indycar)\b.*$"""),"")
                .trim(' ','.','-',':')
        }?.takeIf { it.length in 2..54 }
    val endurance=motorsportEndurance.find(original)?.value
    val stage=when {
        grandPrix!=null -> "GP "+grandPrix
        endurance!=null -> endurance
        else -> {
            val simplified=original.split(motorsportSeparator).first()
                .replace(motorsportSessionSuffix,"")
                .trim(' ','.','-',':')
                .take(56)
            if(simplified.isBlank() || normalized(simplified)==normalized(competition) ||
                Regex("""^(?:f[123]|formula [123e]|treino(?: livre)? ?[1234]?|qualifying|classificacao|corrida|race|sprint)$""")
                    .matches(normalized(simplified))) "Etapa a confirmar" else simplified
        }
    }
    return MotorsportCardDetails(stage,session.first,session.second,normalized(stage))
}

/** Match providers by championship + GP + session, never merge FP1 with FP2 or the race. */
fun motorsportFixtureKey(programme: Programme, competitionId: String): String {
    val card=motorsportCardDetails(programme,competitionId)
    return listOf("motorsport",competitionId,card.stageId,card.sessionId,
        if(programme.broadcast==BroadcastHint.REPLAY)"replay" else "event").joinToString(":")
}
