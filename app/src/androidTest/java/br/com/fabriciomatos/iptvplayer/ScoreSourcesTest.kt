package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreSourcesTest {
    @Test fun publicScheduleLoadsWithoutAnyProviderGuideAndCachesNetworkReads() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val now=System.currentTimeMillis()
        val kickoff=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",java.util.Locale.ROOT).apply {
            timeZone=java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(now+3600000L))
        val match=JSONObject().put("id",813).put("status",JSONObject().put("utcTime",kickoff)
            .put("started",false).put("finished",false))
            .put("home",JSONObject().put("id",1).put("name","Fluminense"))
            .put("away",JSONObject().put("id",2).put("name","Botafogo"))
        val payload=JSONObject().put("leagues",JSONArray().put(
            JSONObject().put("id",268).put("primaryId",268).put("ccode","BRA")
                .put("name","Brasileirão").put("matches",JSONArray().put(match))))
        val calls=java.util.concurrent.atomic.AtomicInteger(0)
        val repo=PlaylistRepository(context)
        try {
            val loader=FootballScoreRepository(repo) {address ->
                assertTrue(address.startsWith("https://www.fotmob.com/api/data/matches?date="))
                assertFalse(address.contains("username"))
                calls.incrementAndGet()
                payload
            }
            val first=loader.loadPublic(now=now)
            assertFalse(first.failed)
            assertEquals(1,publicFootballMatches(first,now).size)
            assertTrue(publicFootballMatches(first,now).single().channels.isEmpty())
            val count=calls.get()
            assertTrue(count>=3)
            val cached=loader.loadPublic(now=now)
            assertEquals(count,calls.get())
            assertEquals(first.events,cached.events)
        } finally {repo.close()}
    }
    @Test fun capturedFotMobFeedIncludesFinalResultsAndTheRealLiveClock() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val json=JSONObject(context.assets.open("scores-fotmob.json").bufferedReader().use {it.readText()})
        val scores=parseFotMobScores(json,1791850000000L)
        val final=scores.single {it.home.names.any {name->name.contains("Nassr")}}
        assertEquals(MatchState.FINISHED,final.state);assertEquals(3,final.homeGoals);assertEquals(1,final.awayGoals)
        val live=scores.single {it.state==MatchState.LIVE}
        assertTrue(live.clock.contains(':'));assertNotNull(live.homeGoals)
        val match=FootballMatch("nassr",Programme("Al Nassr x Neftchi Fergana",final.kickoff+4*3600000,final.kickoff+8*3600000),
            emptyList(),FootballDetails("AFC Champions League Elite","Al Nassr","Neftchi Fergana"))
        assertEquals(3,matchFootballScore(match,FootballFeed(scores))?.homeGoals)
        // The captured final result is independent of this later EPG slot.
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(match.programme,final))
        assertFalse(compactFootballMatch(match,final,match.programme.start-1))
        assertFalse(transmissionIsLive(match.programme,final,match.programme.start))
        assertEquals(BroadcastMode.REPLAY,broadcastMode(match.programme.copy(broadcast=BroadcastHint.REPLAY),final))
    }
    @Test fun theDailyFootballEndpointsAreReachableFromNativeAndroid() {
        val day=footballDateKey(System.currentTimeMillis())
        val primary=readFootballJson("https://www.fotmob.com/api/data/matches?date=$day&timezone=UTC")
        assertNotNull(primary.optJSONArray("leagues"))
        val scores=parseFotMobScores(primary,System.currentTimeMillis())
        assertTrue(scores.isNotEmpty())
        val secondary=readFootballJson("https://site.api.espn.com/apis/site/v2/sports/soccer/afc.champions/scoreboard?dates=$day&limit=100")
        assertNotNull(secondary.optJSONArray("events"))
        parseFootballScoreboard(secondary,"afc.champions",System.currentTimeMillis())
    }
    @Test fun womensAndYouthCompetitionsCannotSupplyASeniorMensScore() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val json=JSONObject(context.assets.open("scores-fotmob.json").bufferedReader().use {it.readText()})
        val league=json.getJSONArray("leagues").getJSONObject(0)
        league.put("primaryId",293).put("ccode","INT").put("name","International Friendlies Women")
        assertEquals(setOf("fifa.friendly.w"),parseFotMobScores(JSONObject().put("leagues",JSONArray().put(league)),1).map {it.league}.toSet())
        league.put("name","International Friendlies U-20")
        assertTrue(parseFotMobScores(JSONObject().put("leagues",JSONArray().put(league)),1).all {it.league!="fifa.friendly"})
        league.put("primaryId",47).put("name","Premier League Women").put("ccode","ENG")
        assertTrue(parseFotMobScores(JSONObject().put("leagues",JSONArray().put(league)),1).all {it.league!="eng.1"})
        league.put("primaryId",268).put("name","Brasileirão Femenino").put("ccode","BRA")
        assertTrue(parseFotMobScores(JSONObject().put("leagues",JSONArray().put(league)),1).all {it.league!="bra.1"})
    }
    @Test fun anUnavailableArchiveDoesNotShowAGlobalFailureWhenCurrentScoresRefresh() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context);val repository=PlaylistRepository(context)
        try {
            val now=System.currentTimeMillis()
            val game=FootballMatch("test",Programme("Fluminense x Botafogo",now-1000,now+3600000),emptyList(),FootballDetails("Campeonato Brasileiro","Fluminense","Botafogo"))
            val source=FootballScoreRepository(repository) {url->
                if(url.contains("date="+footballDateKey(now)))JSONObject().put("leagues",JSONArray())else error("An old date is unavailable")
            }
            assertFalse(source.load(listOf(game),now=now).failed)
        } finally {repository.close()}
    }
}
