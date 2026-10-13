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
    @Test fun actualScoreboardParsesFinalAndLiveStatesAndCachesRefreshesAtOneMinute() = runBlocking {
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
        val loader=FootballScoreRepository(repository) {address->assertTrue(address.startsWith("https://www.fotmob.com/") || address.startsWith("https://site.api.espn.com/"));assertFalse(address.contains("username"));assertTrue(Regex("[?&](?:date|dates)=[0-9]{8}(?:&|$)").containsMatchIn(address));calls.incrementAndGet();if(address.contains("fotmob"))error("Primary offline")else json}
        val first=loader.load(listOf(match),now=now);val firstCalls=calls.get();assertTrue(firstCalls>0)
        loader.load(listOf(match),now=now+59999);val retryCalls=calls.get();assertEquals(firstCalls+footballRequestDays(listOf(match),now).size,retryCalls)
        loader.load(listOf(match),now=now+60000);assertEquals(retryCalls+footballRequestDays(listOf(match),now).size+2,calls.get())
        val result=matchFootballScore(match,first)!!
        assertEquals(BroadcastMode.REPLAY,broadcastMode(match.programme,result.event))
        repository.close()
        val reopened=PlaylistRepository(context)
        val offline=FootballScoreRepository(reopened) {error("Offline")}.load(listOf(match),force=true,now=now+120000)
        assertTrue(offline.failed);assertEquals(3,matchFootballScore(match,offline)?.homeGoals)
        reopened.close()
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
