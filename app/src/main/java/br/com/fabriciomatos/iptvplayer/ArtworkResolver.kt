package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public title/name metadata only. Playlist URLs and provider credentials never leave the provider. */
class ArtworkResolver(private val repository: PlaylistRepository, private val readJson: (String)->JSONObject = ::readArtworkJson) {
    private val animeLock=Mutex()
    private val sportsLock=Mutex()
    private var animeRequestAt=0L
    private var federationRequestAt=0L

    suspend fun anime(source: String, title: String): String? = animeLock.withLock {
        withContext(Dispatchers.IO) { repository.cachedAnimeCover(source,title) }?.let { return@withLock it.takeIf(String::isNotEmpty) }
        knownAnimePoster(title)?.let { return@withLock it }
        val wait=animeRequestAt+1100-System.currentTimeMillis()
        if(wait>0)delay(wait)
        animeRequestAt=System.currentTimeMillis()
        val image=try {
            val query=URLEncoder.encode(title.replace(Regex("(?i)\\s*\\[(?:l|leg|dub)\\]\\s*$"),""),"UTF-8")
            val json=withContext(Dispatchers.IO) { readJson("https://kitsu.io/api/edge/anime?filter%5Btext%5D=$query&page%5Blimit%5D=20") }
            selectAnimePoster(title,json)
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
        withContext(Dispatchers.IO) { repository.saveAnimeCover(source,title,image.orEmpty()) }
        image
    }
    suspend fun football(details: FootballDetails): FootballArtwork = sportsLock.withLock {
        nationalMatch(details)?.let { (home,away) ->
            return@withLock FootballArtwork(FIFA_BADGE,federationBadge(home),federationBadge(away))
        }
        val slug=footballScoreLeagues(details).singleOrNull() ?: return@withLock FootballArtwork()
        val cacheKey="football:$slug"
        val cached=withContext(Dispatchers.IO) { repository.cachedArtwork(cacheKey) }
        val bundle=if(cached!=null) JSONObject(cached) else try {
            withContext(Dispatchers.IO) {
                val teams=readJson("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/teams?limit=100")
                val league=readJson("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard?limit=1")
                sportsBundle(teams,league).also { repository.saveArtwork(cacheKey,it.toString()) }
            }
        } catch(e: CancellationException) {throw e} catch(_: Exception) {
            JSONObject().also {withContext(Dispatchers.IO) {repository.saveArtwork(cacheKey,"{}",3600000L)}}
        }
        FootballArtwork(bundle.optString("badge"),selectTeamBadge(details.home,bundle.optJSONArray("teams") ?: JSONArray()),
            selectTeamBadge(details.away,bundle.optJSONArray("teams") ?: JSONArray()))
    }

    /** One cached league/team catalog per sport, resolved only for cards currently on screen. */
    suspend fun teamSport(event: SportsEvent): FootballArtwork = sportsLock.withLock {
        if(event.home.isBlank() || event.away.isBlank() || sportsDatabaseCategory(event.sport)==null)
            return@withLock FootballArtwork()
        var home = ""
        var away = ""
        for(endpoint in sportsBadgeEndpoints(event.sport,event.competitionId)) {
            val key="sports:teams:espn:$endpoint"
            val cached=withContext(Dispatchers.IO) { repository.cachedArtwork(key) }
            val bundle=if(cached!=null) runCatching { JSONObject(cached) }.getOrDefault(JSONObject()) else try {
                withContext(Dispatchers.IO) {
                    val teams=readJson("https://site.api.espn.com/apis/site/v2/sports/$endpoint/teams?limit=500")
                    sportsBundle(teams,JSONObject()).also { repository.saveArtwork(key,it.toString(),
                        if((it.optJSONArray("teams")?.length() ?: 0) > 0) 30*86400000L else 3600000L) }
                }
            } catch(e: CancellationException) { throw e } catch(_: Exception) {
                JSONObject().also {withContext(Dispatchers.IO) {repository.saveArtwork(key,"{}",3600000L)}}
            }
            val teams=bundle.optJSONArray("teams") ?: JSONArray()
            if(home.isBlank()) home=selectSportsTeamBadge(event.home,teams)
            if(away.isBlank()) away=selectSportsTeamBadge(event.away,teams)
            if(home.isNotBlank() && away.isNotBlank())break
        }
        // Unknown regional leagues and college teams may not be in the ESPN league feed.
        // Search by public team name, but accept a logo only when the SPORT also matches.
        if(home.isBlank())home=sportsDatabaseBadge(event.sport,event.home)
        if(away.isBlank())away=sportsDatabaseBadge(event.sport,event.away)
        FootballArtwork(home=home,away=away)
    }

    private suspend fun sportsDatabaseBadge(sport: SportKind, name: String): String {
        val category=sportsDatabaseCategory(sport) ?: return ""
        val key="sports:team:tsdb:${sport.name}:${teamKey(name)}"
        withContext(Dispatchers.IO) { repository.cachedArtwork(key) }?.let {
            return runCatching { JSONObject(it).optString("badge") }.getOrDefault("")
        }
        val image=try {
            val query=URLEncoder.encode(name,"UTF-8")
            val json=withContext(Dispatchers.IO) {
                readJson("https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t=$query")
            }
            selectSportsDatabaseBadge(name,category,json)
        } catch(e: CancellationException) {throw e} catch(_:Exception) {""}
        withContext(Dispatchers.IO) {
            repository.saveArtwork(key,JSONObject().put("badge",image).toString(),
                if(image.isNotBlank()) 30*86400000L else 6*3600000L)
        }
        return image
    }

    private suspend fun federationBadge(team: NationalTeam): String {
        if(team.badge.isNotBlank())return team.badge
        val key="federation:${normalized(team.country)}"
        withContext(Dispatchers.IO) {repository.cachedArtwork(key)}?.let {return JSONObject(it).optString("badge")}
        val wait=federationRequestAt+2100-System.currentTimeMillis()
        if(wait>0)delay(wait)
        federationRequestAt=System.currentTimeMillis()
        val badge=try {
            val country=URLEncoder.encode(team.country,"UTF-8")
            val json=withContext(Dispatchers.IO) {readJson("https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t=$country")}
            selectFederationBadge(team,json)
        } catch(e: CancellationException) {throw e} catch(_: Exception) {""}
        withContext(Dispatchers.IO) {repository.saveArtwork(key,JSONObject().put("badge",badge).toString(),if(badge.isBlank())3600000L else 30*86400000L)}
        return badge
    }
}


/** Match only within the sport-specific provider catalog; ambiguous names keep the placeholder. */
fun selectSportsTeamBadge(name: String, teams: JSONArray): String {
    val requested=teamKey(name)
    if(requested.isBlank())return ""
    val exact=mutableListOf<String>()
    val expanded=mutableListOf<String>()
    for(i in 0 until teams.length()) {
        val team=teams.optJSONObject(i) ?: continue
        val badge=team.optString("badge").takeIf {it.startsWith("https://")} ?: continue
        val aliases=team.optJSONArray("names") ?: continue
        val names=(0 until aliases.length()).map {aliases.optString(it)}
        if(names.any {teamKey(it)==requested}) exact.add(badge)
        else if(names.any {sportsTeamAliasMatch(name,it)}) expanded.add(badge)
    }
    return exact.distinct().singleOrNull() ?: if(exact.isEmpty())expanded.distinct().singleOrNull().orEmpty() else ""
}

fun selectSportsDatabaseBadge(name: String, sportName: String, json: JSONObject): String {
    val teams=json.optJSONArray("teams") ?: return ""
    val exact=mutableListOf<String>()
    for(i in 0 until teams.length()) {
        val team=teams.optJSONObject(i) ?: continue
        if(!team.optString("strSport").equals(sportName,ignoreCase=true))continue
        if(teamKey(name)!=teamKey(team.optString("strTeam")))continue
        team.optString("strBadge").takeIf { it.startsWith("https://") }?.let(exact::add)
    }
    return exact.distinct().singleOrNull().orEmpty()
}

fun selectFederationBadge(request: NationalTeam, json: JSONObject): String {
    val teams=json.optJSONArray("teams") ?: return ""
    val matches=buildList {
        for(i in 0 until teams.length()) {
            val team=teams.optJSONObject(i) ?: continue
            if(team.optString("strSport")!="Soccer" || nationalTeam(team.optString("strTeam"))?.country!=request.country)continue
            val badge=team.optString("strBadge")
            if(badge.startsWith("https://r2.thesportsdb.com/images/media/team/badge/"))add(badge)
        }
    }.distinct()
    return matches.singleOrNull().orEmpty()
}

fun selectAnimePoster(title: String, json: JSONObject): String? {
    val entries=json.optJSONArray("data") ?: return null
    var best=0;var image: String?=null
    for(i in 0 until entries.length()) {
        val a=entries.optJSONObject(i)?.optJSONObject("attributes") ?: continue
        val subtype=a.optString("subtype")
        if(subtype !in setOf("TV","movie","OVA","ONA","special"))continue
        val titles=buildList {
            add(a.optString("canonicalTitle"))
            a.optJSONObject("titles")?.let { t->t.keys().forEach {add(t.optString(it))} }
            a.optJSONArray("abbreviatedTitles")?.let { t->for(n in 0 until t.length())add(t.optString(n)) }
        }
        val score=titles.maxOfOrNull {animeTitleScore(title,it)} ?: 0
        val rank=score*10+if(subtype=="TV")3 else if(subtype=="movie")2 else 1
        val poster=a.optJSONObject("posterImage")?.optString("large").orEmpty()
        if(score>0 && rank>best && poster.startsWith("https://")) {best=rank;image=poster}
    }
    return image
}
fun sportsBundle(teamsJson: JSONObject, leagueJson: JSONObject): JSONObject {
    val league=teamsJson.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)
    val teams=JSONArray()
    league?.optJSONArray("teams")?.let { list -> for(i in 0 until list.length()) {
        val team=list.optJSONObject(i)?.optJSONObject("team") ?: continue
        val names=JSONArray(listOf("displayName","shortDisplayName","name","nickname","location","abbreviation").map {team.optString(it)}.filter(String::isNotBlank).distinct())
        teams.put(JSONObject().put("names",names).put("badge",team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()))
    } }
    return JSONObject().put("teams",teams).put("badge",leagueJson.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty())
}
fun selectTeamBadge(name: String, teams: JSONArray): String {
    val key=teamKey(name)
    val matches=buildList {for(i in 0 until teams.length()) {
        val team=teams.optJSONObject(i) ?: continue
        val names=team.optJSONArray("names") ?: continue
        if((0 until names.length()).any {teamKey(names.optString(it))==key})add(team.optString("badge"))
    }}.filter {it.startsWith("https://")}.distinct()
    return matches.singleOrNull().orEmpty()
}
private fun readArtworkJson(address: String): JSONObject {
    val connection=(URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout=8000;readTimeout=8000;setRequestProperty("User-Agent","AuroraIPTV/1.7 Android")
        setRequestProperty("Accept","application/json")
    }
    try {
        check(connection.responseCode in 200..299)
        // These are small, paginated metadata responses, never the playlist itself.
        val bytes=connection.inputStream.use {it.readNBytesCompat(512*1024)}
        return JSONObject(bytes.toString(Charsets.UTF_8))
    } finally {connection.disconnect()}
}
private fun java.io.InputStream.readNBytesCompat(maximum: Int): ByteArray {
    val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) {val count=read(buffer);if(count<0)break;check(result.size()+count<=maximum);result.write(buffer,0,count)}
    return result.toByteArray()
}
