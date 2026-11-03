package br.com.fabriciomatos.iptvplayer

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class XtreamSportsGuideTest {
    private val source="https://iptv.example.test/get.php?username=user&password=pass&type=m3u_plus"
    private fun channel(name:String,stream:String,tvgId:String="") =
        Channel(name,"https://iptv.example.test/live/user/pass/$stream.ts","Esportes",
            tvgId=tvgId,typeHint="live")
    private fun encoded(value:String)=Base64.encodeToString(value.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)

    @Test fun realProviderSchedulesRestoreBroadcasterPlaybackWithoutPublicFakeStreams() = runBlocking {
        val now=System.currentTimeMillis()
        val channels=listOf(channel("ESPN 4 HD","12345","espn4hd"),channel("ESPN 4 FHD","12346","espn4fhd"))
        val calls=AtomicInteger()
        val payload=JSONObject().put("epg_listings",JSONArray().put(JSONObject()
            .put("title",encoded("Vila Nova x Novorizontino - Campeonato Brasileiro Série B"))
            .put("description",encoded("Jogo ao vivo de futebol"))
            .put("start_timestamp",(now+3600000L)/1000)
            .put("stop_timestamp",(now+10800000L)/1000)))
        val guide=XtreamSportsGuide.load(source,channels,now) { url ->
            calls.incrementAndGet()
            assertTrue(url.startsWith("https://iptv.example.test/player_api.php?"))
            assertTrue(url.contains("action=get_short_epg"))
            assertTrue(url.contains("stream_id=12345"))
            payload
        }
        assertEquals(1,calls.get())
        assertEquals(2,guide.matches.size)
        val games=footballMatches(guide,channels,now)
        assertEquals(1,games.size)
        assertEquals(2,games.single().channels.size)
        assertEquals(setOf("espn4hd","espn4fhd"),games.single().channels.map {it.tvgId}.toSet())
        assertEquals("Vila Nova",games.single().details.home)
        assertEquals("Novorizontino",games.single().details.away)
        assertEquals(FOOTBALL_GAMES,footballTabs(games).first {it.key==FOOTBALL_GAMES}.key)
    }

    @Test fun partialGuideRefreshKeepsCurrentMatchesButRejectsContradictedOrExpiredSlots() {
        val now=System.currentTimeMillis()
        val programme=Programme("Vila Nova x Novorizontino - Campeonato Brasileiro Série B",
            now-3600000L,now+3600000L)
        val expired=Programme("Time Antigo x Outro - Campeonato Brasileiro",now-7200000L,now-3600000L)
        val previous=Guide(matches=listOf(GuideMatch("espn4",programme),GuideMatch("old",expired)))
        val empty=mergeProviderGuide(previous,Guide(),Guide(),now)
        assertEquals(1,empty.matches.size)
        assertEquals(programme,empty.matches.single().programme)
        val regular=Programme("Filme em vez de jogo",now-3600000L,now+3600000L)
        val changed=mergeProviderGuide(previous,Guide(programmes=mapOf("espn4" to listOf(regular))),
            Guide(),now)
        assertTrue(changed.matches.isEmpty())
    }

    @Test fun missingProviderGuideNeverFabricatesMatchesOrChannelLinks() = runBlocking {
        val now=System.currentTimeMillis()
        val linked=channel("ESPN 4 FHD","12345","espn4")
        assertNull(XtreamSportsGuide.streamId(source,linked.copy(
            url="https://unrelated.example.test/live/someone-else/pass/12345.ts")))
        assertEquals("12345",XtreamSportsGuide.streamId(source,linked.copy(
            url="https://provider-cdn.example.test/live/user/pass/12345.ts")))
        assertNull(XtreamSportsGuide.streamId(source,linked.copy(
            url="https://iptv.example.test/live/user/pass/not-a-stream.ts")))
        val guide=XtreamSportsGuide.load(source,listOf(linked),now) { JSONObject() }
        assertTrue(guide.matches.isEmpty())
        val unrelated=JSONObject().put("epg_listings",JSONArray().put(JSONObject()
            .put("title",encoded("Cinema de ação"))
            .put("start_timestamp",now/1000)
            .put("stop_timestamp",(now+3600000)/1000)))
        assertTrue(XtreamSportsGuide.parse(unrelated,now).isEmpty())
    }
}
