package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

/**
 * Sport is classified independently of competition, broadcast state and participants.
 * Weak channel names (ESPN, Sport TV) must not decide the sport by themselves.
 */
enum class SportKind(val displayName: String) {
    FOOTBALL("Futebol"),
    BASKETBALL("Basquete"),
    AMERICAN_FOOTBALL("Futebol Americano"),
    BASEBALL("Beisebol"),
    MOTORSPORT("Automobilismo"),
    HOCKEY("Hóquei"),
    TENNIS("Tênis"),
    VOLLEYBALL("Vôlei"),
    HANDBALL("Handebol"),
    OTHER("Outras modalidades")
}

data class SportsCompetitionRule(
    val id: String, val sport: SportKind, val displayName: String, val patterns: List<String>, val priority: Int
)

/** A single ordered registry; competition titles are never entire EPG descriptions. */
val sportsCompetitionRules = listOf(
    SportsCompetitionRule("nba", SportKind.BASKETBALL, "NBA", listOf("nba", "national basketball association"), 10),
    SportsCompetitionRule("nbb", SportKind.BASKETBALL, "NBB — Brasil", listOf("nbb", "novo basquete brasil", "campeonato brasileiro de basquete"), 20),
    SportsCompetitionRule("euroleague", SportKind.BASKETBALL, "EuroLeague", listOf("euroleague", "euroliga", "euro liga de basquete"), 30),
    SportsCompetitionRule("basket.arg", SportKind.BASKETBALL, "Liga Argentina de Basquete", listOf("liga nacional de basquet", "liga argentina de basquete"), 40),
    SportsCompetitionRule("wnba", SportKind.BASKETBALL, "WNBA", listOf("wnba"), 50),
    SportsCompetitionRule("nfl", SportKind.AMERICAN_FOOTBALL, "NFL", listOf("nfl", "national football league", "liga profissional de futebol americano dos eua"), 10),
    SportsCompetitionRule("elf", SportKind.AMERICAN_FOOTBALL, "European League of Football", listOf("european league of football", "liga europeia de futebol americano"), 20),
    SportsCompetitionRule("ncaa.fb", SportKind.AMERICAN_FOOTBALL, "NCAA — Futebol Americano", listOf("ncaa football", "college football"), 30),
    SportsCompetitionRule("mlb", SportKind.BASEBALL, "MLB", listOf("mlb", "major league baseball", "liga nacional, os .*phillies", "liga americana de beisebol"), 10),
    SportsCompetitionRule("npb", SportKind.BASEBALL, "NPB — Japão", listOf("npb", "nippon professional baseball"), 20),
    SportsCompetitionRule("nhl", SportKind.HOCKEY, "NHL", listOf("nhl", "national hockey league",
        "liga nacional de hoquei", "liga de hoquei no gelo do mundo", "liga de hockey no gelo do mundo"), 10),
    SportsCompetitionRule("khl", SportKind.HOCKEY, "KHL", listOf("khl", "kontinental hockey league"), 20),
    SportsCompetitionRule("f1", SportKind.MOTORSPORT, "Fórmula 1", listOf("formula 1", "formula one", "f1", "grand prix de formula 1"), 10),
    SportsCompetitionRule("f2", SportKind.MOTORSPORT, "Fórmula 2", listOf("formula 2", "f2", "fia formula 2"), 20),
    SportsCompetitionRule("f3", SportKind.MOTORSPORT, "Fórmula 3", listOf("formula 3", "f3", "fia formula 3"), 30),
    SportsCompetitionRule("wrc", SportKind.MOTORSPORT, "Mundial de Rally — WRC", listOf("wrc", "world rally championship", "mundial de rally"), 40),
    SportsCompetitionRule("stockcar", SportKind.MOTORSPORT, "Stock Car", listOf("stock car", "stockcar brasil", "stock car pro series"), 50),
    SportsCompetitionRule("motogp", SportKind.MOTORSPORT, "MotoGP", listOf("motogp", "moto gp"), 60),
    SportsCompetitionRule("indycar", SportKind.MOTORSPORT, "IndyCar", listOf("indycar", "indy 500", "indy 500 milhas", "500 milhas de indianapolis"), 70),
    SportsCompetitionRule("formula-e", SportKind.MOTORSPORT, "Fórmula E", listOf("formula e", "formula-e", "formula e world championship"), 80),
    SportsCompetitionRule("wec", SportKind.MOTORSPORT, "WEC — Endurance", listOf("wec", "world endurance championship", "mundial de endurance", "24 horas de le mans", "24h le mans"), 90),
    SportsCompetitionRule("nascar", SportKind.MOTORSPORT, "NASCAR", listOf("nascar", "cup series", "xfinity series"), 100),
    SportsCompetitionRule("dtm", SportKind.MOTORSPORT, "DTM", listOf("dtm", "deutsche tourenwagen masters"), 110),
    SportsCompetitionRule("porsche-cup", SportKind.MOTORSPORT, "Porsche Cup", listOf("porsche cup", "porsche carrera cup"), 120),
    SportsCompetitionRule("super-formula", SportKind.MOTORSPORT, "Super Formula", listOf("super formula", "super formula championship"), 130),
    SportsCompetitionRule("dakar", SportKind.MOTORSPORT, "Rally Dakar", listOf("rally dakar", "rali dakar", "dakar rally"), 140),
    SportsCompetitionRule("moto2", SportKind.MOTORSPORT, "Moto2", listOf("moto2", "moto 2"), 150),
    SportsCompetitionRule("moto3", SportKind.MOTORSPORT, "Moto3", listOf("moto3", "moto 3"), 160),
    SportsCompetitionRule("worldsbk", SportKind.MOTORSPORT, "WorldSBK", listOf("worldsbk", "world superbike", "superbike world championship"), 170),
    SportsCompetitionRule("tennis.atp", SportKind.TENNIS, "ATP", listOf("atp", "atp tour"), 10),
    SportsCompetitionRule("tennis.wta", SportKind.TENNIS, "WTA", listOf("wta", "wta tour"), 20),
    SportsCompetitionRule("volley.superliga", SportKind.VOLLEYBALL, "Superliga de Vôlei", listOf("superliga de volei", "superliga de voleibol"), 10)
)
private val compiledSportsRules=sportsCompetitionRules.map { rule ->
    rule to rule.patterns.map { Regex("(?<![a-z0-9])(?:$it)(?![a-z0-9])") }
}
private val headToHead = Regex("(?i)\\s+(?:x|×|vs\\.?|v\\.?|versus)\\s+")
private val sportsEditorial = Regex("\\b(debate|resenha|mesa redonda|noticias|news|jornal|sportscenter|sportcenter|melhores momentos|highlights|resumo|documentario|revista|magazine|boletim|preview|review|entrevista|bastidores|pre jogo|pos jogo|pre game|post game)\\b")
private val otherSports = listOf(
    "Boxe" to Regex("\\b(boxe|boxing)\\b"),
    "MMA" to Regex("\\b(mma|ufc|artes marciais)\\b"),
    "Rugby" to Regex("\\b(rugby|rugbi)\\b"),
    "Futsal" to Regex("\\b(futsal)\\b"),
    "Ciclismo" to Regex("\\b(ciclismo|cycling|tour de france|giro d italia)\\b"),
    "Atletismo" to Regex("\\b(atletismo|athletics)\\b"),
    "Natação" to Regex("\\b(natacao|swimming)\\b"),
    "Críquete" to Regex("\\b(criquete|cricket)\\b"),
    "Golfe" to Regex("\\b(golfe|golf|pga tour)\\b"),
    "Surfe" to Regex("\\b(surfe|surf|surfing|wsl)\\b")
)
private fun otherSportName(programme: Programme): String? {
    val explicit=programme.metadata.sport.trim()
    otherSports.firstOrNull {it.second.containsMatchIn(normalized(explicit))}?.let {return it.first}
    if(explicit.length in 3..40 && normalized(explicit) !in setOf("sports","esportes","outros","futebol","soccer","football") &&
        sportsMarkers.none {it.second.containsMatchIn(normalized(explicit))} && !sportsEditorial.containsMatchIn(normalized(explicit)))
        return explicit
    val sources=listOf(programme.metadata.sport,programme.metadata.competition,programme.title,programme.category)
    for(source in sources) otherSports.firstOrNull { it.second.containsMatchIn(normalized(source)) }?.let {return it.first}
    return programme.metadata.sport.trim().takeIf {
        it.length in 3..40 && normalized(it) !in setOf("sports","esportes","outros","futebol","soccer","football") &&
            !sportsEditorial.containsMatchIn(normalized(it)) && !headToHead.containsMatchIn(it)
    }
}
// Do not concatenate all source fields into a single string: an unrelated word in a
// long description may otherwise override a trustworthy football competition.
private val footballSportMarker=Regex("""\b(futebol|soccer|football)\b""")

/** No team-name inference: require explicit sporting context in the EPG or title. */
private val sportsMarkers = listOf(
    SportKind.AMERICAN_FOOTBALL to Regex("\\b(nfl|futebol americano|american football|college football)\\b"),
    SportKind.BASEBALL to Regex("\\b(mlb|beisebol|baseball|npb|major league baseball)\\b"),
    SportKind.BASKETBALL to Regex("\\b(nba|wnba|nbb|basquete|basketball|euroleague|euroliga)\\b"),
    SportKind.HOCKEY to Regex("\\b(nhl|khl|hoquei|hockey)\\b"),
    SportKind.MOTORSPORT to Regex("\\b(formula ?[123e]|formula one|f1|f2|f3|wrc|rally|rali|stock ?car|motogp|moto ?[23]|indycar|indy 500|automobilismo|wec|endurance|nascar|dtm|porsche cup|super formula|worldsbk|superbike|dakar|24 horas de le mans)\\b"),
    SportKind.TENNIS to Regex("\\b(tenis|tennis|atp|wta)\\b"),
    SportKind.VOLLEYBALL to Regex("\\b(voleibol|volei|volleyball)\\b"),
    SportKind.HANDBALL to Regex("\\b(handebol|handball)\\b")
)
fun detectOtherSport(programme: Programme): SportKind? {
    val explicit=normalized(programme.metadata.sport)
    if(explicit.isNotBlank()) {
        sportsMarkers.firstOrNull {it.second.containsMatchIn(explicit)}?.let {return it.first}
        if(footballSportMarker.containsMatchIn(explicit))return null
        otherSportName(programme.copy(title="",category="",metadata=programme.metadata.copy(competition="")))?.let {return SportKind.OTHER}
    }
    // Strong evidence first, before free-form descriptions. A generic broadcaster
    // like "ESPN HD" or a team nickname is not sufficient to identify a sport.
    val primary=listOf(
        programme.metadata.sport, programme.metadata.competition,
        programme.title, programme.category, programme.metadata.originalTitle,
        programme.metadata.subtitle
    )
    for(source in primary) {
        val key=normalized(source)
        if(key.isBlank())continue
        sportsMarkers.firstOrNull { (_,marker)->marker.containsMatchIn(key) }?.let { return it.first }
    }
    // Editorial descriptions sometimes mention many sports. Use them only if the
    // structured data did not explicitly identify the programme as football.
    if(primary.any { footballSportMarker.containsMatchIn(normalized(it)) }) return null
    val description=normalized(programme.metadata.description)
    return sportsMarkers.firstOrNull { it.second.containsMatchIn(description) }?.first
        ?: otherSportName(programme)?.let { SportKind.OTHER }
}
fun sportsProgrammeCandidate(programme: Programme): Boolean {
    val kind=detectOtherSport(programme) ?: return false
    if(sportsEditorial.containsMatchIn(normalized(listOf(programme.title,programme.category,
        programme.metadata.subtitle).joinToString(" "))))return false
    if(headToHead.containsMatchIn(programme.title)) return true
    if(kind==SportKind.OTHER) return Regex("\\b(etapa|stage|final|semifinal|bateria|heat|prova|race|round|rodada|torneio|tournament)\\b")
        .containsMatchIn(normalized(programme.title)) && programme.title.trim().length in 8..160
    if (kind != SportKind.MOTORSPORT) return false
    val title=normalized(programme.title)
    val strong=Regex("""\b(grand prix|grande premio|gp|formula ?[123e]|f[123]|indycar|indy 500|motogp|moto ?[23]|rally|rali|wrc|stock ?car|nascar|wec|endurance|24h|24 horas|le mans|porsche cup|dtm|super formula|worldsbk|superbike|dakar)\b""")
    if (strong.containsMatchIn(title)) return true
    // A generic "treino" or "corrida" is not sufficient without trusted sport metadata.
    val context=normalized(listOf(programme.metadata.sport,programme.metadata.competition,programme.category).joinToString(" "))
    return Regex("""\b(treino|practice|fp[1234]|tl[1234]|classificacao|qualificacao|qualifying|sprint|corrida|race)\b""").containsMatchIn(title) &&
        Regex("""\b(automobilismo|motorsport|formula ?[123e]|f[123]|wec|nascar|indycar|motogp|wrc|rally|endurance|stock ?car)\b""").containsMatchIn(context)
}

data class SportsEvent(
    val id:String, val sport:SportKind, val competitionId:String, val competition:String,
    val name:String, val home:String, val away:String, val programme:Programme, val channels:List<Channel>,
    val channelProgrammes:Map<String,Programme> = emptyMap(),
    val disciplineName:String = sport.displayName
)
data class SportsCategory(val id:String, val title:String, val priority:Int)
const val SPORTS_LIVE="__live__"
const val SPORTS_GAMES="__games__"

/** Known aliases win. Otherwise accept a short competition field, never free-form prose. */
fun sportsCompetition(programme: Programme, sport: SportKind): Pair<String,String> {
    fun identified(id:String,title:String):Pair<String,String> {
        val evidence=listOf(programme.metadata.gender,programme.metadata.division,
            programme.metadata.competition,programme.category,programme.title).joinToString(" ")
        val gender=footballGender(evidence)
        val age=footballAgeGroup(evidence)
        val division=programme.metadata.division.trim().take(60)
        val suffix=buildList {
            if(id !in setOf("wnba","tennis.wta")) {
                if(gender==FootballGender.WOMEN)add("Feminino")
                if(gender==FootballGender.MEN)add("Masculino")
                if(gender==FootballGender.CONFLICT)add("Categoria não confirmada")
            }
            if(age.isNotBlank())add("Sub-$age")
            if(division.isNotBlank() && !normalized(title).contains(normalized(division)) &&
                normalized(division)!=normalized(age))add(division)
        }
        return (id+suffix.joinToString("") { ":"+normalized(it) }) to
            (title+suffix.filterNot { normalized(title).contains(normalized(it)) }.joinToString("") { " · $it" })
    }
    fun validLabel(raw:String):Boolean {
        val key=normalized(raw)
        return raw.length in 4..55 && !raw.contains(',') && !headToHead.containsMatchIn(raw) &&
            key !in setOf("ao vivo","live","jogos","games","sports","esportes","outros eventos",
                "filmes","series","movies","cinema",
                normalized(sport.displayName),normalized(programme.metadata.sport)) &&
            sportsMarkers.none {it.second.matches(key)} && otherSports.none {it.second.matches(key)} &&
            !sportsEditorial.containsMatchIn(key)
    }
    val explicit=programme.metadata.competition.trim()
    if(validLabel(explicit) && compiledSportsRules.none { (rule,patterns) ->
        rule.sport==sport && patterns.any {it.containsMatchIn(normalized(explicit))}
    }) return identified(sport.name.lowercase()+":"+normalized(explicit),explicit)
    val sources=listOf(programme.metadata.competition,programme.category,
        programme.title,programme.metadata.subtitle,programme.metadata.description)
    for(source in sources) {
        val key=normalized(source)
        val rule=compiledSportsRules.firstOrNull { (rule,patterns) ->
            rule.sport==sport && patterns.any { it.containsMatchIn(key) }
        }?.first
        if(rule!=null) return identified(rule.id,rule.displayName)
    }
    val titleLabels=programme.title.split(Regex("\\s+[-–—|·]\\s+|:\\s+"))
        .map(String::trim).filter {isCompetitionLabel(it) && !headToHead.containsMatchIn(it)}
    val fallback=(listOf(programme.metadata.competition,programme.category)+titleLabels)
        .map(String::trim).firstOrNull(::validLabel)
    if(fallback!=null) return identified(sport.name.lowercase()+":"+normalized(fallback),fallback)
    return identified(sport.name.lowercase()+":other","Campeonato não informado")
}
fun sportsEvents(guide:Guide,channels:List<Channel>,now:Long=System.currentTimeMillis(),
    index:SportsChannelIndex=SportsChannelIndex(guide,channels)):List<SportsEvent> {
    val merged=linkedMapOf<String,SportsEvent>()
    val lastByFixture=mutableMapOf<String,String>()
    guide.matches.asSequence().filter { sportsWindowContains(it.programme,now) }
        .sortedBy {it.programme.start}.forEach {entry ->
            val p=entry.programme
            val sport=detectOtherSport(p) ?: return@forEach
            if(!sportsProgrammeCandidate(p)) return@forEach
            val linked=index[entry.channelId]
            if(linked.isEmpty()) return@forEach
            val league=sportsCompetition(p,sport)
            val matchup=headToHead.find(p.title)
            fun participant(value:String)=value.trim().replace(Regex("(?i)\\s*[\\[(](?:ao vivo|live|reprise|replay)[\\])]"),"").trim()
            val home=if(matchup!=null) participant(p.title.substring(0,matchup.range.first)
                .substringAfterLast(": ").split(Regex("\\s+[-–—]\\s+")).last()) else ""
            val away=if(matchup!=null) participant(p.title.substring(matchup.range.last+1)
                .split(Regex("\\s+[-–—]\\s+|\\s+[║|·]\\s+")).first()) else ""
            if(matchup!=null && (home.length !in 2..80 || away.length !in 2..80 ||
                normalized(home)==normalized(away)))return@forEach
            val title=if(home.isNotBlank() && away.isNotBlank()) "$home × $away"
                else p.title.trim().take(96)
            val discipline=if(sport==SportKind.OTHER)otherSportName(p) ?: return@forEach else sport.displayName
            val fixture=if(sport==SportKind.MOTORSPORT) motorsportFixtureKey(p,league.first)
                else listOf(discipline,league.first,
                    if(matchup!=null)listOf(teamKey(home),teamKey(away)).sorted().joinToString(":") else normalized(title),
                    if(footballExplicitReplay(p))"replay" else "event").joinToString(":")
            val previous=lastByFixture[fixture]?.let(merged::get)?.takeIf {
                abs(it.programme.start-p.start)<=90*60000L &&
                    it.programme.start<p.end && p.start<it.broadcastEnd &&
                    linked.none { channel -> it.channelProgrammes[channel.url]?.let { slot -> slot!=p }==true }
            }
            val id=previous?.id ?: fixture+":"+p.start+":"+entry.channelId
            merged[id]=SportsEvent(id,sport,league.first,league.second,title,home,away,
                previous?.programme ?: p,(previous?.channels.orEmpty()+linked).distinctBy {it.url}
                    .sortedWith(compareByDescending<Channel>{footballChannelQuality(it.name)}.thenBy {it.name}),
                previous?.channelProgrammes.orEmpty()+linked.associate {it.url to p},discipline)
            lastByFixture[fixture]=id
        }
    return merged.values.sortedWith(compareBy<SportsEvent>{it.programme.start}.thenBy {it.name})
}
fun sportsCategories(events:List<SportsEvent>,sport:SportKind):List<SportsCategory> {
    val types=events.asSequence().filter {it.sport==sport}
        .groupBy {it.competitionId}.map { (id,list) ->
            val title=list.first().competition
            val position=sportsCompetitionRules.find {it.id==id&&it.sport==sport}?.priority ?: 1000
            SportsCategory(id,title,position)
        }.sortedWith(compareBy<SportsCategory>{it.priority}.thenBy {normalized(it.title)}.thenBy {it.id})
    return listOf(SportsCategory(SPORTS_LIVE,"Ao Vivo",-2),
        SportsCategory(SPORTS_GAMES,"Jogos",-1))+types
}
fun sportsFilter(events:List<SportsEvent>, sport:SportKind, category:String,search:String,
    now:Long):List<SportsEvent> {
    val words=normalized(search).split(Regex("\\s+")).filter(String::isNotBlank)
    val filtered=events.filter { event ->
        event.sport==sport && event.programme.end>now-FOOTBALL_RETENTION_MS &&
            (category==SPORTS_GAMES || (category==SPORTS_LIVE &&
                event.programme.broadcast==BroadcastHint.LIVE &&
                now in event.programme.start..event.programme.end) ||
                category==event.competitionId) &&
            words.all { normalized(event.name+" "+event.competition+
                if(sport==SportKind.MOTORSPORT) " "+motorsportCardDetails(event.programme,event.competition).session else "").contains(it) }
    }
    if(sport!=SportKind.MOTORSPORT) return filtered
    return filtered.sortedWith(compareBy<SportsEvent> { event ->
        when {
            event.programme.broadcast==BroadcastHint.LIVE && now>=event.programme.start && now<event.programme.end -> 0
            now>=event.programme.start && now<event.programme.end &&
                event.programme.broadcast!=BroadcastHint.REPLAY -> 1
            now<event.programme.start && event.programme.broadcast!=BroadcastHint.REPLAY -> 2
            now>=event.programme.start && now<event.programme.end -> 3
            now<event.programme.start -> 4
            else -> 5
        }
    }.thenBy {it.programme.start}.thenBy {it.name})
}
