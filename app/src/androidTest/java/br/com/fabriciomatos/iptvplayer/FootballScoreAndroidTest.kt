package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballScoreAndroidTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Before fun reset()=resetCatalog(context)
    @Test fun actualScoreboardParsesFinalAndLiveStatesAndCachesRefreshesAtTwoMinutes() = runBlocking {
        val json=JSONObject(instrumentation.context.assets.open("scores-afc-final.json").bufferedReader().use {it.readText()})
        val now=System.currentTimeMillis()/86400000L*86400000L+12*3600000L
        val parsed=parseFootballScoreboard(json,"afc.champions",now).single()
        assertEquals(MatchState.FINISHED,parsed.state);assertEquals(3,parsed.homeGoals);assertEquals(1,parsed.awayGoals)
        assertEquals("Al Nassr",parsed.home.names.first());assertEquals("Neftchi Fergana",parsed.away.names.first())
        val live=JSONObject(json.toString())
        live.getJSONArray("events").getJSONObject(0).getJSONObject("status").apply {
            put("displayClock","83:21");put("type",JSONObject().put("state","in").put("completed",false).put("name","STATUS_IN_PROGRESS"))
        }
        val liveResult=parseFootballScoreboard(live,"afc.champions",now).single()
        assertEquals(MatchState.LIVE,liveResult.state);assertEquals("83:21",liveResult.clock)
        val scheduled=JSONObject(json.toString())
        scheduled.getJSONArray("events").getJSONObject(0).getJSONObject("status").put("type",JSONObject().put("state","pre").put("name","STATUS_SCHEDULED"))
        assertNull(parseFootballScoreboard(scheduled,"afc.champions",now).single().homeGoals)
        assertEquals(listOf(liveResult),decodeFootballFeed(encodeFootballFeed(FootballFeed(listOf(liveResult),now))).events)
        val match=FootballMatch("replay",Programme("Al Nassr x Neftchi Fergana - AFC Champions League Elite",parsed.kickoff+4*3600000L,now+2*86400000L),
            emptyList(),FootballDetails("AFC Champions League Elite","Al Nassr","Neftchi Fergana"))
        val repository=PlaylistRepository(context);val calls=java.util.concurrent.atomic.AtomicInteger()
        val loader=FootballScoreRepository(repository) {address->assertTrue(address.startsWith("https://www.fotmob.com/") || address.startsWith("https://site.api.espn.com/"));assertFalse(address.contains("username"));assertTrue(Regex("[?&](?:date|dates)=[0-9]{4}(?:[0-9]{4})?(?:&|$)").containsMatchIn(address));calls.incrementAndGet();if(address.contains("fotmob"))error("Primary offline")else json}
        val first=loader.load(listOf(match),now=now);val firstCalls=calls.get();assertTrue(firstCalls>0)
        loader.load(listOf(match),now=now+59999)
        val retryCalls=calls.get()
        assertTrue("FotMob primary error must be retried",retryCalls>firstCalls)
        loader.load(listOf(match),now=now+120000)
        assertTrue("Expired daily score caches must refresh",calls.get()>retryCalls)
        val result=matchFootballScore(match,first)!!
        assertEquals(BroadcastMode.REPLAY,broadcastMode(match.programme,result.event))
        repository.close()
        val reopened=PlaylistRepository(context)
        val offline=FootballScoreRepository(reopened) {error("Offline")}.load(listOf(match),force=true,now=now+120000)
        assertTrue(offline.failed);assertEquals(3,matchFootballScore(match,offline)?.homeGoals)
        reopened.close()
    }
    @Test fun serieASeasonLoadsAndSurvivesProviderWithoutEpgOrNetwork() = runBlocking {
        val now=System.currentTimeMillis()/86400000L*86400000L+12*3600000L
        val year=footballDateKey(now).take(4)
        val json=org.json.JSONObject().put("leagues",org.json.JSONArray().put(
            org.json.JSONObject().put("name","Brasileirão")))
        val rows=org.json.JSONArray()
        val formatter=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",java.util.Locale.ROOT)
            .apply {timeZone=java.util.TimeZone.getTimeZone("UTC")}
        repeat(3) { n ->
            val home=org.json.JSONObject().put("type","team").put("homeAway","home")
                .put("team",org.json.JSONObject().put("id","h"+n).put("displayName","Time A"+n)
                    .put("logo","https://a.espncdn.com/test-home.png"))
            val away=org.json.JSONObject().put("type","team").put("homeAway","away")
                .put("team",org.json.JSONObject().put("id","a"+n).put("displayName","Time B"+n)
                    .put("logo","https://a.espncdn.com/test-away.png"))
            val game=org.json.JSONObject().put("id","test-"+n)
                .put("date",formatter.format(java.util.Date(now+(n+1)*86400000L)))
                .put("competitions",org.json.JSONArray().put(org.json.JSONObject()
                    .put("competitors",org.json.JSONArray().put(home).put(away))
                    .put("status",org.json.JSONObject().put("type",org.json.JSONObject()
                        .put("name","STATUS_SCHEDULED").put("state","pre")))))
            rows.put(game)
        }
        json.put("events",rows)
        val repository=PlaylistRepository(context)
        try {
            val seen=mutableListOf<String>()
            val loader=FootballScoreRepository(repository) {url->
                seen.add(url)
                if(url.contains("soccer/bra.1/scoreboard?dates="+year+"&limit=500"))json
                else if(url.contains("fotmob.com"))org.json.JSONObject().put("leagues",org.json.JSONArray())
                else throw AssertionError("Unexpected lookup: "+url)
            }
            val first=loader.load(emptyList(),now=now)
            assertFalse(first.failed)
            assertEquals(3,brazilianSerieAFixtures(first).size)
            assertTrue("Must query the whole season without relying on IPTV EPG",
                seen.any {it.contains("dates="+year+"&limit=500")})
            assertEquals(3,footballListings(emptyList(),first,FOOTBALL_BRAZIL,"",now).size)
            assertTrue(footballListings(emptyList(),first,FOOTBALL_BRAZIL,"",now)
                .all {it.broadcast==null && it.fixture!=null})
            val cached=FootballScoreRepository(repository) {error("Offline")}.load(emptyList(),force=true,now=now+120000L)
            assertTrue(cached.failed)
            assertEquals(3,brazilianSerieAFixtures(cached).size)
        } finally {repository.close()}
    }

    @Test fun liveAndReplayXmltvHintsSurviveDatabaseReopening() {
        val now=System.currentTimeMillis()
        val xml="""<tv><channel id="sport"><display-name>TV Esportes</display-name></channel>
            <programme channel="sport" start="${stamp(now)}" stop="${stamp(now+3600000)}"><title>Fluminense x Botafogo - Campeonato Brasileiro</title><live/></programme>
            <programme channel="sport" start="${stamp(now+3600000)}" stop="${stamp(now+7200000)}"><title>Al Nassr x Neftchi Fergana - AFC Champions League Elite</title><previously-shown/></programme></tv>"""
        val guide=parseGuide(xml.byteInputStream(),"https://example.test/guide.xml",setOf("sport"),now)
        assertEquals(listOf(BroadcastHint.LIVE,BroadcastHint.REPLAY),guide.matches.map {it.programme.broadcast})
        val repository=PlaylistRepository(context);repository.saveGuide("fixture",guide);repository.close()
        val reopened=PlaylistRepository(context);assertEquals(guide,reopened.savedGuide("fixture"));reopened.close()
    }
    private fun stamp(value: Long)=java.text.SimpleDateFormat("yyyyMMddHHmmss Z",java.util.Locale.ROOT).format(java.util.Date(value))
}
