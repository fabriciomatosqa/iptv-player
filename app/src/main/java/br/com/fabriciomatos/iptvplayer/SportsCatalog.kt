package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

/** Sport, competition and broadcast status are independent dimensions. */
enum class SportKind(val key: String, val label: String) {
    FOOTBALL("football","Futebol"), BASKETBALL("basketball","Basquete"),
    AMERICAN_FOOTBALL("american_football","Futebol americano"), BASEBALL("baseball","Beisebol"),
    HOCKEY("hockey","Hóquei"), MOTORSPORT("motorsport","Automobilismo"),
    VOLLEYBALL("volleyball","Vôlei"), TENNIS("tennis","Tênis")
}
const val SPORTS_ALL = "__all_sports_leagues__"
data class SportLeague(val id:String,val title:String,val order:Int)
data class SportEvent(val id:String,val sport:SportKind,val league:SportLeague,
    val title:String,val home:String,val away:String,val programme:Programme,val channels:List<Channel>)
private data class SportRule(val sport:SportKind,val id:String,val title:String,val regex:String,val priority:Int)
/** Data-driven aliases; do not merge different sports or divisions. */
private val leagueRules = listOf(
    SportRule(SportKind.BASKETBALL,"basketball.nba","NBA","nba|national basketball association",10),
    SportRule(SportKind.BASKETBALL,"basketball.wnba","WNBA","wnba|women s national basketball association",15),
    SportRule(SportKind.BASKETBALL,"basketball.nbb","NBB — Brasil","nbb|novo basquete brasil|campeonato brasileiro de basquete",20),
    SportRule(SportKind.BASKETBALL,"basketball.euro","EuroLeague","euroleague|euroliga|liga europeia de basquete",30),
    SportRule(SportKind.BASKETBALL,"basketball.arg","Liga Argentina de Basquete","liga nacional de basquet(?:e|bol)|campeonato argentino de basquete",40),
    SportRule(SportKind.AMERICAN_FOOTBALL,"american.nfl","NFL","nfl|national football league|liga profissional de futebol americano dos eua|liga de futebol americano",10),
    SportRule(SportKind.AMERICAN_FOOTBALL,"american.ncaa","NCAA — Futebol americano","ncaa football|college football",20),
    SportRule(SportKind.BASEBALL,"baseball.mlb.nl","MLB — Liga Nacional","mlb national league|liga nacional(?: de beisebol)?|national league baseball",11),
    SportRule(SportKind.BASEBALL,"baseball.mlb.al","MLB — Liga Americana","mlb american league|liga americana(?: de beisebol)?|american league baseball",12),
    SportRule(SportKind.BASEBALL,"baseball.mlb","MLB","mlb|major league baseball|liga principal de beisebol",10),
    SportRule(SportKind.HOCKEY,"hockey.nhl","NHL","nhl|national hockey league|liga nacional de hoquei",10),
    SportRule(SportKind.HOCKEY,"hockey.khl","KHL","khl|kontinental hockey league",20),
    SportRule(SportKind.MOTORSPORT,"motor.f1","Fórmula 1","formula 1|formula one|f1|fia formula one",10),
    SportRule(SportKind.MOTORSPORT,"motor.f2","Fórmula 2","formula 2|formula two|f2|fia formula 2",20),
    SportRule(SportKind.MOTORSPORT,"motor.f3","Fórmula 3","formula 3|formula three|f3|fia formula 3",30),
    SportRule(SportKind.MOTORSPORT,"motor.wrc","Mundial de Rally — WRC","wrc|world rally championship|campeonato mundial de rally|mundial de rali",40),
    SportRule(SportKind.MOTORSPORT,"motor.stock","Stock Car","stock car|stock car pro series",50),
    SportRule(SportKind.MOTORSPORT,"motor.indy","IndyCar","indycar|indy car",60),
    SportRule(SportKind.MOTORSPORT,"motor.nascar","NASCAR","nascar|nascar cup series",70),
    SportRule(SportKind.MOTORSPORT,"motor.motogp","MotoGP","motogp|moto gp",80),
    SportRule(SportKind.VOLLEYBALL,"volleyball.super","Superliga de Vôlei","superliga de volei|superliga brasileira de volei",10),
    SportRule(SportKind.TENNIS,"tennis.atp","ATP","atp|atp tour",10),
    SportRule(SportKind.TENNIS,"tennis.wta","WTA","wta|wta tour",20)
)
private fun sportTokens(value:String) = normalized(value).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
private val ruleRegexes=leagueRules.map {it to Regex("(?:^|\\b)(?:"+it.regex+")(?:\\b|$)")}
private val sportMarkers=listOf(
    SportKind.AMERICAN_FOOTBALL to Regex("\\b(futebol americano|american football|nfl|super bowl|philadelphia eagles|carolina panthers|new england patriots)\\b"),
    SportKind.BASEBALL to Regex("\\b(baseball|beisebol|mlb|diamondbacks|phillies|yankees|red sox|dodgers|mets|world series baseball)\\b"),
    SportKind.HOCKEY to Regex("\\b(hoquei|hockey|nhl|khl|stanley cup)\\b"),
    SportKind.MOTORSPORT to Regex("\\b(formula [123]|formula one|f[123]|grand prix|grande premio|stock car|rally|rali|wrc|indycar|nascar|motogp|automobilismo|motorsport)\\b"),
    SportKind.BASKETBALL to Regex("\\b(basquete|basquetebol|basketball|nba|wnba|nbb|euroleague|euroliga)\\b"),
    SportKind.VOLLEYBALL to Regex("\\b(volei|voleibol|volleyball|volley)\\b"),
    SportKind.TENNIS to Regex("\\b(tenis|tennis|atp tour|wta tour)\\b"),
    SportKind.FOOTBALL to Regex("\\b(futebol|soccer|football|premier league|libertadores|brasileirao|sul americana|la liga|bundesliga|serie a italiana)\\b")
)
private fun identifiedSports(value:String):Set<SportKind> {
    val key=sportTokens(value)
    return sportMarkers.filter {it.second.containsMatchIn(key)}.map {it.first}.toSet()
}
private fun onlySpecific(kinds:Set<SportKind>):SportKind? = kinds.filter {it!=SportKind.FOOTBALL}.singleOrNull()
/** Trust sport metadata, then EPG title and competition, lastly channel group branding. */
fun programmeSport(programme:Programme,channelNames:List<String> = emptyList(),groups:List<String> = emptyList()):SportKind? {
    val metadata=programme.metadata
    val explicit=identifiedSports(metadata.sport)
    onlySpecific(explicit)?.let {return it}
    if(explicit==setOf(SportKind.FOOTBALL))return SportKind.FOOTBALL
    if(explicit.size>1)return null
    val primary=identifiedSports(listOf(metadata.competition,metadata.originalTitle.ifBlank {programme.title},
        metadata.subtitle,programme.category).joinToString(" "))
    onlySpecific(primary)?.let {return it}
    if(primary.size>1)return null
    val secondary=identifiedSports(metadata.description)
    onlySpecific(secondary)?.let {return it}
    if(primary==setOf(SportKind.FOOTBALL)||secondary==setOf(SportKind.FOOTBALL))return SportKind.FOOTBALL
    val branding=identifiedSports((groups+channelNames).joinToString(" "))
    onlySpecific(branding)?.let {return it}
    if(branding==setOf(SportKind.FOOTBALL))return SportKind.FOOTBALL
    return null
}
/** Never promote long editorial prose into a league tab. */
private fun cleanLeague(raw:String):String? {
    val candidate=raw.trim().substringBefore(',').substringBefore(';').trim()
    val key=sportTokens(candidate)
    if(candidate.length !in 3..54 || Regex("[.!?\\n]").containsMatchIn(candidate) ||
        Regex("\\b(recebem|recebe|enfrentam|enfrenta|divisao de elite|disputa entre|transmissao de|jogo entre)\\b").containsMatchIn(key))return null
    if(!Regex("\\b(campeonato|liga|league|cup|copa|serie|series|championship|tour|trofeu|torneio|formula|rally)\\b").containsMatchIn(key))return null
    return candidate
}
fun sportCompetition(programme:Programme,sport:SportKind):SportLeague {
    val sources=listOf(programme.metadata.competition,programme.metadata.subtitle,
        programme.category,programme.title,programme.metadata.description)
    for(source in sources) {
        val key=sportTokens(source)
        val rule=ruleRegexes.firstOrNull {it.first.sport==sport&&it.second.containsMatchIn(key)}?.first
        if(rule!=null)return SportLeague(rule.id,rule.title,rule.priority)
    }
    val fallback=listOf(programme.metadata.competition,programme.category,programme.metadata.subtitle)
        .mapNotNull(::cleanLeague).firstOrNull()
    return if(fallback!=null)SportLeague(sport.key+":"+sportTokens(fallback),fallback,500)
    else SportLeague(sport.key+":other","Outras competições",900)
}
private val editorial=Regex("\\b(melhores momentos|resenha|debate|sportcenter|sportscenter|documentario|noticias|jornal esportivo)\\b")
fun isSportsProgramme(programme:Programme):Boolean {
    val kind=programmeSport(programme)?:return hasFootballMatchup(programme.title)
    if(editorial.containsMatchIn(sportTokens(programme.title)))return false
    return hasFootballMatchup(programme.title)||kind==SportKind.MOTORSPORT||
        ruleRegexes.any {it.first.sport==kind&&it.second.containsMatchIn(
            sportTokens(programme.title+" "+programme.metadata.competition))}
}
private val sportsVersus=Regex("(?i)\\s+(?:x|vs\\.?|v\\.?|versus)\\s+")
private fun sportTeams(title:String,sport:SportKind):Pair<String,String>? {
    if(sport==SportKind.MOTORSPORT)return null
    val basic=title.substringBefore(" ║").substringBefore(" ||").substringBefore(" · ")
    val match=sportsVersus.find(basic)?:return null
    val home=basic.substring(0,match.range.first).substringAfterLast(':').trim()
    val away=basic.substring(match.range.last+1).split(Regex("\\s+[-–—]\\s+")).first().trim()
    return if(home.length in 2..70&&away.length in 2..70&&sportTokens(home)!=sportTokens(away))home to away else null
}
fun sportsEvents(guide:Guide,channels:List<Channel>,now:Long=System.currentTimeMillis()):List<SportEvent> {
    val live=channels.filter {it.kind==MediaKind.LIVE&&!it.adult}
    val byId=live.filter {it.tvgId.isNotBlank()}.groupBy {it.tvgId}
    val byName=live.groupBy {guideChannelName(it.name)}
    val epgNames=guide.names.entries.groupBy({it.value},{guideChannelName(it.key)})
    val result=linkedMapOf<String,SportEvent>()
    val grouped=mutableMapOf<String,String>()
    guide.matches.asSequence().filter {it.programme.end>now-FOOTBALL_RETENTION_MS}
        .sortedBy {it.programme.start}.forEach {entry->
        val available=(byId[entry.channelId].orEmpty()+
            epgNames[entry.channelId].orEmpty().flatMap {byName[it].orEmpty()}).distinctBy {it.url}
        if(available.isEmpty())return@forEach
        val kind=programmeSport(entry.programme,available.map {it.name},available.map {it.group})?:return@forEach
        if(kind==SportKind.FOOTBALL)return@forEach
        if(!isSportsProgramme(entry.programme)&&!hasFootballMatchup(entry.programme.title))return@forEach
        val league=sportCompetition(entry.programme,kind)
        val teams=sportTeams(entry.programme.title,kind)
        val title=entry.programme.metadata.originalTitle.ifBlank {entry.programme.title}
            .substringBefore(" ║").substringBefore(" ||").take(130)
        val identity=teams?.let {listOf(sportTokens(it.first),sportTokens(it.second)).sorted().joinToString(":")}
            ?:sportTokens(title)
        val group=listOf(kind.key,league.id,identity,
            if(entry.programme.broadcast==BroadcastHint.REPLAY)"replay"else"schedule").joinToString(":")
        val previous=grouped[group]?.let(result::get)?.takeIf {abs(it.programme.start-entry.programme.start)<=90*60000L}
        val id=previous?.id?:group+":"+entry.programme.start
        result[id]=SportEvent(id,kind,league,title,teams?.first.orEmpty(),teams?.second.orEmpty(),
            previous?.programme?:entry.programme,
            (previous?.channels.orEmpty()+available).distinctBy {it.url}
                .sortedWith(compareByDescending<Channel> {footballChannelQuality(it.name)}.thenBy {it.name}))
        grouped[group]=id
    }
    return result.values.sortedWith(compareBy<SportEvent> {it.programme.start}.thenBy {it.id})
}
fun sportLeagues(events:List<SportEvent>,sport:SportKind):List<SportLeague> =
    listOf(SportLeague(SPORTS_ALL,"Todos",0))+events.asSequence().filter {it.sport==sport}
        .map {it.league}.distinctBy {it.id}.sortedWith(compareBy<SportLeague> {it.order}
            .thenBy {sportTokens(it.title)}.thenBy {it.id}).toList()
fun filterSportEvents(events:List<SportEvent>,sport:SportKind,league:String,search:String,now:Long):List<SportEvent> {
    val words=sportTokens(search).split(' ').filter(String::isNotBlank)
    return events.filter {it.sport==sport&&(league==SPORTS_ALL||it.league.id==league)&&
        it.programme.end>now-FOOTBALL_RETENTION_MS&&
        words.all {word->sportTokens(it.title+" "+it.home+" "+it.away+" "+it.league.title).contains(word)}}
}
