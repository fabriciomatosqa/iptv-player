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
        assertEquals(BroadcastMode.REPLAY,broadcastMode(match.programme,final))
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
