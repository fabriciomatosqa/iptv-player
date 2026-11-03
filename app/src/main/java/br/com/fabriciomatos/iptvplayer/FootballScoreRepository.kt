package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Only public league slugs and dates are sent. Streams, playlist addresses and credentials stay local. */
class FootballScoreRepository(private val repository: PlaylistRepository, private val readJson: (String)->JSONObject = ::readFootballJson) {
    private val requests=Semaphore(3)
    suspend fun load(matches: List<FootballMatch>, force: Boolean = false, now: Long = System.currentTimeMillis()): FootballFeed = withContext(Dispatchers.IO) {
        val available=matches.filter {it.broadcastEnd>now-FOOTBALL_RETENTION_MS ||
            it.programme.broadcast==BroadcastHint.LIVE && it.programme.start in (now-6*3600000L)..now}
        val leagues=available.flatMap {footballScoreLeagues(it.details)}.distinct()
        if(available.isEmpty())return@withContext FootballFeed()
        val days=footballRequestDays(available,now)
        val current=setOf(footballDateKey(now),footballDateKey(now-86400000L))
        val results=ConcurrentHashMap<String,FootballFeed>()
        fun key(league: String, day: String)="$league:$day"
        // Restore old results before making requests so a slow source never empties the cards.
        (listOf("fotmob")+leagues).forEach {league->days.forEach {day->
            repository.cachedArtwork(footballScoreCacheKey(league,day),now)?.let {raw->
                runCatching {decodeFootballFeed(JSONObject(raw))}.getOrNull()?.let {results[key(league,day)]=it}
            }
        }}
        withTimeoutOrNull(40000L) {
            val primary=coroutineScope {days.sortedByDescending {it in current}.map {day->async {requests.withPermit {
                dayScores("fotmob",day,if(day in current)FOOTBALL_REFRESH_MS else 6*3600000L,force,now).also {results[key("fotmob",day)]=it}
            }}}.awaitAll()}
            // One daily feed covers all competitions. Fall back only when it cannot refresh.
            // ESPN's date-range query returns HTTP 400: each request must contain one date.
            val failedDays=days.sortedByDescending {it in current}.zip(primary).filter {it.second.failed}.map {it.first}
            coroutineScope {leagues.flatMap {league->failedDays.map {day->async {requests.withPermit {
                dayScores(league,day,if(day in current)FOOTBALL_REFRESH_MS else 6*3600000L,force,now).also {results[key(league,day)]=it}
            }}}}.awaitAll()}
        }
        val responses=results.values.toList()
        val events=responses.flatMap {it.events}.groupBy {it.league+":"+it.id}.values.map {it.maxBy(FootballScore::checked)}
        val currentFailed=(listOf("fotmob")+leagues).flatMap {league->current.mapNotNull {day->results[key(league,day)]}}
            .none {!it.failed && !footballRefreshDue(it.checked,now)}
        FootballFeed(events,responses.maxOfOrNull {it.checked} ?: 0L,currentFailed)
    }
    /** Fetch incidents only when a user expands a card, never while scrolling the match list. */
    suspend fun incidents(score: FootballScore): List<FootballIncident>? = withContext(Dispatchers.IO) {
        val id=score.id.takeIf {it.startsWith("fotmob:")}
            ?.removePrefix("fotmob:")?.takeIf {it.length in 1..12 && it.all(Char::isDigit)}
            ?: return@withContext null
        val key="football-incidents:$id"
        repository.cachedArtwork(key)?.let { cached ->
            runCatching { decodeFootballIncidents(JSONObject(cached)) }.getOrNull()?.let { return@withContext it }
        }
        val result=runCatching {
            val json=readJson("https://www.fotmob.com/api/data/matchDetails?matchId=$id")
            parseFotMobIncidents(json,id)
        }.getOrNull() ?: return@withContext null
        repository.saveArtwork(key,encodeFootballIncidents(result).toString(),
            if (score.state in listOf(MatchState.LIVE, MatchState.HALFTIME)) FOOTBALL_REFRESH_MS else 60*60000L)
        result
    }
    private suspend fun dayScores(league: String, dates: String, age: Long, force: Boolean, now: Long): FootballFeed {
        coroutineContext.ensureActive()
        val key=footballScoreCacheKey(league,dates)
        val cached=repository.cachedArtwork(key,now)?.let {runCatching {decodeFootballFeed(JSONObject(it))}.getOrNull()}
        if(!force && cached!=null && now-cached.checked in 0 until age)return cached
        return try {
            val address=if(league=="fotmob")"https://www.fotmob.com/api/data/matches?date=$dates&timezone=UTC"
                else "https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=$dates&limit=100"
            val json=readJson(address)
            coroutineContext.ensureActive()
            val events=if(league=="fotmob")parseFotMobScores(json,now)else {
                check(json.optJSONArray("events")!=null)
                parseFootballScoreboard(json,league,now)
            }
            val feed=FootballFeed(events,now)
            repository.saveArtwork(key,encodeFootballFeed(feed).toString(),7*86400000L)
            feed
        } catch(e: CancellationException) {throw e} catch(_: Exception) {
            (cached ?: FootballFeed()).copy(failed=true)
        }
    }
}
fun footballDateKey(value: Long) = SimpleDateFormat("yyyyMMdd",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(value))
fun footballRequestDays(matches: List<FootballMatch>, now: Long): List<String> =
    ((-7..0).map {footballDateKey(now+it*86400000L)}+matches.flatMap {
        listOf(footballDateKey(it.programme.start.coerceIn(now-7*86400000L,now+14*86400000L)),
            footballDateKey((it.programme.start+86400000L).coerceIn(now,now+14*86400000L)))
    }).distinct()
fun footballDates(start: Long, end: Long): String {
    val format=SimpleDateFormat("yyyyMMdd",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}
    return format.format(Date(start))+"-"+format.format(Date(end))
}
fun footballScoreCacheKey(league: String, dates: String) = "football-scores:$league:$dates"
internal fun footballIsoDate(value: String): Long {
    for(pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX","yyyy-MM-dd'T'HH:mm:ssX","yyyy-MM-dd'T'HH:mmX")) {
        val date=runCatching {SimpleDateFormat(pattern,Locale.ROOT).apply {isLenient=false;timeZone=TimeZone.getTimeZone("UTC")}.parse(value)?.time}.getOrNull()
        if(date!=null)return date
    }
    return 0L
}
fun parseFootballScoreboard(json: JSONObject, league: String, checked: Long): List<FootballScore> {
    val rows=json.optJSONArray("events") ?: return emptyList()
    val competition=json.optJSONArray("leagues")?.optJSONObject(0)
    val title=competition?.optString("name").orEmpty()
    val badge=competition?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty().takeIf {it.startsWith("https://a.espncdn.com/")}.orEmpty()
    return buildList {
        for(i in 0 until rows.length()) {
            val event=rows.optJSONObject(i) ?: continue
            val contest=event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors=contest.optJSONArray("competitors") ?: continue
            val teams=(0 until competitors.length()).mapNotNull {competitors.optJSONObject(it)}.filter {it.optString("type")=="team"}
            val home=teams.singleOrNull {it.optString("homeAway")=="home"} ?: continue
            val away=teams.singleOrNull {it.optString("homeAway")=="away"} ?: continue
            val kickoff=footballIsoDate(event.optString("date"))
            val id=event.optString("id")
            if(kickoff==0L || id.isBlank())continue
            val status=contest.optJSONObject("status") ?: event.optJSONObject("status") ?: JSONObject()
            val type=status.optJSONObject("type") ?: JSONObject()
            val state=when {
                type.optString("name").contains("CANCEL",true)->MatchState.CANCELLED
                type.optString("name").contains("POSTPON",true) || type.optString("name").contains("SUSPEND",true) || type.optString("name").contains("ABANDON",true)->MatchState.POSTPONED
                type.optString("name").contains("HALFTIME",true)->MatchState.HALFTIME
                type.optBoolean("completed") && type.optString("state")=="post"->MatchState.FINISHED
                type.optString("state")=="in"->MatchState.LIVE
                type.optString("state")=="pre"->MatchState.SCHEDULED
                else->MatchState.UNKNOWN
            }
            fun team(row: JSONObject): ScoreTeam {
                val t=row.optJSONObject("team") ?: JSONObject()
                val names=listOf("displayName","shortDisplayName","name","location","abbreviation").map {t.optString(it)}.filter(String::isNotBlank).distinct()
                val logo=t.optString("logo").takeIf {it.startsWith("https://a.espncdn.com/")}.orEmpty()
                return ScoreTeam(t.optString("id"),names,logo)
            }
            fun goals(row: JSONObject): Int? {
                if(state !in listOf(MatchState.LIVE,MatchState.HALFTIME,MatchState.FINISHED))return null
                val raw=row.opt("score")
                val value=if(raw is JSONObject)raw.optString("displayValue",raw.optString("value"))else raw?.toString()
                return value?.toIntOrNull()?.takeIf {it in 0..99}
            }
            val homeTeam=team(home);val awayTeam=team(away)
            if(homeTeam.names.isEmpty() || awayTeam.names.isEmpty())continue
            add(FootballScore(id,league,title,kickoff,homeTeam,awayTeam,goals(home),goals(away),state,status.optString("displayClock"),checked,badge))
        }
    }
}
fun encodeFootballFeed(feed: FootballFeed): JSONObject {
    fun team(team: ScoreTeam)=JSONObject().put("id",team.id).put("names",JSONArray(team.names)).put("badge",team.badge)
    return JSONObject().put("checked",feed.checked).put("events",JSONArray(feed.events.map {
        JSONObject().put("id",it.id).put("league",it.league).put("competition",it.competition).put("kickoff",it.kickoff)
            .put("home",team(it.home)).put("away",team(it.away)).put("homeGoals",it.homeGoals ?: JSONObject.NULL).put("awayGoals",it.awayGoals ?: JSONObject.NULL)
            .put("state",it.state.name).put("clock",it.clock).put("checked",it.checked).put("badge",it.badge)
    }))
}
fun decodeFootballFeed(json: JSONObject): FootballFeed {
    fun team(team: JSONObject): ScoreTeam {
        val names=team.getJSONArray("names")
        return ScoreTeam(team.getString("id"),(0 until names.length()).map {names.getString(it)},team.optString("badge"))
    }
    val events=json.getJSONArray("events")
    return FootballFeed((0 until events.length()).map {i->
        val e=events.getJSONObject(i)
        FootballScore(e.getString("id"),e.getString("league"),e.getString("competition"),e.getLong("kickoff"),team(e.getJSONObject("home")),team(e.getJSONObject("away")),
            if(e.isNull("homeGoals"))null else e.getInt("homeGoals"),if(e.isNull("awayGoals"))null else e.getInt("awayGoals"),MatchState.valueOf(e.getString("state")),e.optString("clock"),e.getLong("checked"),e.optString("badge"))
    },json.getLong("checked"))
}
internal fun readFootballJson(address: String): JSONObject {
    val connection=(URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout=8000;readTimeout=8000
        setRequestProperty("User-Agent","AuroraIPTV/1.8 Android");setRequestProperty("Accept","application/json")
    }
    try {
        check(connection.responseCode in 200..299)
        return connection.inputStream.use {input->
            val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=input.read(buffer);if(count<0)break;check(result.size()+count<=4*1024*1024);result.write(buffer,0,count)}
            JSONObject(result.toString("UTF-8"))
        }
    } finally {connection.disconnect()}
}
