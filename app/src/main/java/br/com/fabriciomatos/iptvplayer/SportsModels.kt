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
    HANDBALL("Handebol")
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
    SportsCompetitionRule("nhl", SportKind.HOCKEY, "NHL", listOf("nhl", "national hockey league"), 10),
    SportsCompetitionRule("khl", SportKind.HOCKEY, "KHL", listOf("khl", "kontinental hockey league"), 20),
    SportsCompetitionRule("f1", SportKind.MOTORSPORT, "Fórmula 1", listOf("formula 1", "formula one", "f1", "grand prix de formula 1"), 10),
    SportsCompetitionRule("f2", SportKind.MOTORSPORT, "Fórmula 2", listOf("formula 2", "f2", "fia formula 2"), 20),
    SportsCompetitionRule("f3", SportKind.MOTORSPORT, "Fórmula 3", listOf("formula 3", "f3", "fia formula 3"), 30),
    SportsCompetitionRule("wrc", SportKind.MOTORSPORT, "Mundial de Rally — WRC", listOf("wrc", "world rally championship", "mundial de rally"), 40),
    SportsCompetitionRule("stockcar", SportKind.MOTORSPORT, "Stock Car", listOf("stock car", "stockcar brasil", "stock car pro series"), 50),
    SportsCompetitionRule("motogp", SportKind.MOTORSPORT, "MotoGP", listOf("motogp", "moto gp"), 60),
    SportsCompetitionRule("indycar", SportKind.MOTORSPORT, "IndyCar", listOf("indycar", "indy 500"), 70),
    SportsCompetitionRule("tennis.atp", SportKind.TENNIS, "ATP", listOf("atp", "atp tour"), 10),
    SportsCompetitionRule("tennis.wta", SportKind.TENNIS, "WTA", listOf("wta", "wta tour"), 20),
    SportsCompetitionRule("volley.superliga", SportKind.VOLLEYBALL, "Superliga de Vôlei", listOf("superliga de volei", "superliga de voleibol"), 10)
)
private val compiledSportsRules=sportsCompetitionRules.map { rule ->
    rule to rule.patterns.map { Regex("(?<![a-z0-9])(?:$it)(?![a-z0-9])") }
}
private val headToHead = Regex("(?i)\\s+(?:x|vs\\.?|v\\.?|versus)\\s+")
private fun evidenceString(programme: Programme): String = listOf(
    programme.metadata.sport,programme.metadata.competition,programme.category,
    programme.metadata.originalTitle,programme.title,programme.metadata.subtitle,
    programme.metadata.description
).joinToString(" ")

/** No team-name inference: require explicit sporting context in the EPG or title. */
private val sportsMarkers = listOf(
    SportKind.AMERICAN_FOOTBALL to Regex("\\b(nfl|futebol americano|american football|college football)\\b"),
    SportKind.BASEBALL to Regex("\\b(mlb|beisebol|baseball|npb|major league baseball)\\b"),
    SportKind.BASKETBALL to Regex("\\b(nba|wnba|nbb|basquete|basketball|euroleague|euroliga)\\b"),
    SportKind.HOCKEY to Regex("\\b(nhl|khl|hoquei|hockey)\\b"),
    SportKind.MOTORSPORT to Regex("\\b(formula ?[123]|formula one|f1|f2|f3|wrc|rally|stock ?car|motogp|moto gp|indycar|automobilismo)\\b"),
    SportKind.TENNIS to Regex("\\b(tenis|tennis|atp|wta)\\b"),
    SportKind.VOLLEYBALL to Regex("\\b(voleibol|volei|volleyball)\\b"),
    SportKind.HANDBALL to Regex("\\b(handebol|handball)\\b")
)
fun detectOtherSport(programme: Programme): SportKind? {
    val value=normalized(evidenceString(programme))
    return sportsMarkers.firstOrNull { it.second.containsMatchIn(value) }?.first
}
fun sportsProgrammeCandidate(programme: Programme): Boolean {
    val kind=detectOtherSport(programme) ?: return false
    if(headToHead.containsMatchIn(programme.title)) return true
    return kind==SportKind.MOTORSPORT && Regex(
        "(?i)\\b(grand prix|gp\\b|formula ?[123]|f[123]\\b|rally|wrc\\b|stock ?car|motogp|indycar|corrida|qualifica|treino livre)"
    ).containsMatchIn(programme.title)
}

data class SportsEvent(
    val id:String, val sport:SportKind, val competitionId:String, val competition:String,
    val name:String, val home:String, val away:String, val programme:Programme, val channels:List<Channel>
)
data class SportsCategory(val id:String, val title:String, val priority:Int)
const val SPORTS_LIVE="__live__"
const val SPORTS_GAMES="__games__"

/** Known aliases win. Otherwise accept a short competition field, never free-form prose. */
fun sportsCompetition(programme: Programme, sport: SportKind): Pair<String,String> {
    val sources=listOf(programme.metadata.competition,programme.category,
        programme.title,programme.metadata.subtitle,programme.metadata.description)
    for(source in sources) {
        val key=normalized(source)
        val rule=compiledSportsRules.firstOrNull { (rule,patterns) ->
            rule.sport==sport && patterns.any { it.containsMatchIn(key) }
        }?.first
        if(rule!=null) return rule.id to rule.displayName
    }
    val fallback=listOf(programme.metadata.competition,programme.category)
        .map(String::trim).firstOrNull { raw ->
            raw.length in 4..55 && !raw.contains(',') &&
            !Regex("(?i)\\b(ao vivo|live|jogos|games|sports|esportes|filmes|series)\\b").matches(raw) &&
            !headToHead.containsMatchIn(raw) &&
            normalized(raw)!=normalized(sport.displayName) &&
            normalized(raw)!="esportes" &&
            normalized(raw)!="sports"
        }
    if(fallback!=null) return sport.name.lowercase()+":"+normalized(fallback) to fallback
    return sport.name.lowercase()+":other" to "Outros eventos"
}
fun sportsEvents(guide:Guide,channels:List<Channel>,now:Long=System.currentTimeMillis()):List<SportsEvent> {
    val live=channels.filter { it.kind==MediaKind.LIVE && !it.adult }
    val byId=live.filter { it.tvgId.isNotBlank() }.groupBy { it.tvgId }
    val byName=live.groupBy { guideChannelName(it.name) }
    val aliases=guide.names.entries.groupBy({ it.value },{ guideChannelName(it.key) })
    val merged=linkedMapOf<String,SportsEvent>()
    val lastByFixture=mutableMapOf<String,String>()
    guide.matches.asSequence().filter { it.programme.end>now-FOOTBALL_RETENTION_MS }
        .sortedBy {it.programme.start}.forEach {entry ->
            val p=entry.programme
            val sport=detectOtherSport(p) ?: return@forEach
            if(!sportsProgrammeCandidate(p)) return@forEach
            val linked=(byId[entry.channelId].orEmpty() +
                aliases[entry.channelId].orEmpty().flatMap { byName[it].orEmpty() })
                .distinctBy {it.url}
            if(linked.isEmpty()) return@forEach
            val league=sportsCompetition(p,sport)
            val matchup=headToHead.find(p.title)
            val home=if(matchup!=null) p.title.substring(0,matchup.range.first).trim()
                .substringAfterLast(": ").substringAfterLast(" - ").take(70) else ""
            val away=if(matchup!=null) p.title.substring(matchup.range.last+1).trim()
                .split(Regex("\\s+[-–—]\\s+|\\s+[║|]\\s+")).first().trim().take(70) else ""
            val title=if(home.isNotBlank() && away.isNotBlank()) "$home × $away"
                else p.title.trim().take(96)
            val fixture=listOf(sport.name,league.first,normalized(title),
                if(p.broadcast==BroadcastHint.REPLAY)"replay" else "event").joinToString(":")
            val previous=lastByFixture[fixture]?.let(merged::get)?.takeIf {
                abs(it.programme.start-p.start)<=90*60000L
            }
            val id=previous?.id ?: fixture+":"+p.start
            merged[id]=SportsEvent(id,sport,league.first,league.second,title,home,away,
                previous?.programme ?: p,(previous?.channels.orEmpty()+linked).distinctBy {it.url}
                    .sortedWith(compareByDescending<Channel>{footballChannelQuality(it.name)}.thenBy {it.name}))
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
    return events.filter { event ->
        event.sport==sport && event.programme.end>now-FOOTBALL_RETENTION_MS &&
            (category==SPORTS_GAMES || (category==SPORTS_LIVE &&
                event.programme.broadcast==BroadcastHint.LIVE &&
                now in event.programme.start..event.programme.end) ||
                category==event.competitionId) &&
            words.all { normalized(event.name+" "+event.competition).contains(it) }
    }
}
