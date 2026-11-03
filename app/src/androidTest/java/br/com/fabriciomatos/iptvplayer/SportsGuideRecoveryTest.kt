package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SportsGuideRecoveryTest {
    private val now=xmltvTime("20261103160000 +0000")
    private val source="https://provider.example.test/get.php?username=fixture&password=fixture"
    private val channels=listOf(
        Channel("ESPN 5 FHD","https://delivery.example.test/play/opaque-a","Esportes",tvgId="espn.5.br"),
        Channel("ESPN 5 HD [ALT]","https://delivery.example.test/play/opaque-b","Esportes",tvgId="espn.5.br"),
        Channel("ESPN 4 FHD","https://delivery.example.test/play/opaque-c","Esportes",tvgId="espn.4.br"))
    private val assets get()=InstrumentationRegistry.getInstrumentation().context.assets
    private fun capturedPublic():Guide=assets.open("guide/epgshare-br-20261103.xml").use {
        parseGuide(it,"https://fixture.test/guide.xml",emptySet(),now)
    }
    private fun expired():ProviderGuideRows=assets.open("guide/provider-expired-table.json").bufferedReader().use {
        XtreamSportsGuide.parseRows(JSONObject(it.readText()),now)
    }
    private fun publicRead()=BrazilBroadcastGuide.link(capturedPublic(),channels).let {
        GuideRead(it,GuideAttempt(GuideSource.EPGSHARE_BR,GuideStatus.READY,GuideCounts(2,12,12,it.matches.size)))
    }

    @Test fun capturedProviderFailureAndCurrentBroadcasterGuideRestoreOnlyRealPlaylistStreams()=runBlocking {
        var counts=GuideCounts()
        val empty=assets.open("guide/provider-empty.xml").use {
            parseGuide(it,"https://fixture.test/xmltv",emptySet(),now,inspect={counts=it})
        }
        assertEquals(2,counts.channels);assertEquals(0,counts.programmes)
        assertEquals(GuideStatus.EMPTY,guideStatus(counts,0))
        val old=expired()
        assertEquals(4,old.counts.programmes);assertEquals(0,old.counts.current)
        assertTrue(old.programmes.isEmpty());assertEquals(GuideStatus.STALE,guideStatus(old.counts,0))
        val calls=ArrayList<String>()
        val recovered=recoverSportsGuide(source,"https://fixture.test/xmltv",channels,Guide(),true,now,
            xml={_,_,_->calls.add("xml");GuideRead(empty,GuideAttempt(GuideSource.XMLTV,GuideStatus.EMPTY,counts))},
            api={_,_,_->calls.add("api");GuideRead(attempt=GuideAttempt(GuideSource.PROVIDER_API,GuideStatus.STALE,old.counts))},
            brazil={_,_->calls.add("broadcast");publicRead()})
        assertEquals(listOf("xml","api","broadcast"),calls)
        val games=footballMatches(recovered.guide,channels,now)
        assertTrue("Current real-format XMLTV must restore a linked game",games.isNotEmpty())
        assertTrue(games.all {it.channels.isNotEmpty() && it.channels.all {c->c in channels}})
        assertTrue(games.any {it.channels.size==2})
        assertTrue(games.any {it.details.home=="Porto" && it.details.away=="Estoril"})
        assertTrue(games.none {it.details.home.contains("VT") || it.details.home.contains("NFL")})
        assertTrue(games.all {it.programme.broadcast==BroadcastHint.REPLAY})
        assertTrue(recovered.received);assertTrue(recovered.diagnostics.alternate)
        assertEquals(GuideStatus.READY,recovered.diagnostics.status)
        assertEquals(recovered.diagnostics,GuideDiagnostics.decode(recovered.diagnostics.encode()))
        assertFalse(recovered.diagnostics.encode().contains("password"))
    }

    @Test fun alternateGuideNeverOverridesProviderScheduleOrLinksOtherCountriesAndSimilarNames()=runBlocking {
        val linked=publicRead()
        val foreign=channels.first().copy(name="ESPN 5 FHD",tvgId="espn.5.pt")
        val confusing=channels.first().copy(name="ESPN 50 HD",tvgId="espn.50.br")
        val unrelated=channels.first().copy(name="ESPN 5 HD",tvgId="unrelated.id")
        assertTrue(BrazilBroadcastGuide.link(capturedPublic(),listOf(foreign,confusing,unrelated)).matches.isEmpty())
        val active=Programme("Noticiário esportivo",now,now+3600000L)
        val current=Guide(programmes=mapOf(channels.first().tvgId to listOf(active)))
        var alternateCalled=false
        val result=recoverSportsGuide(source,"https://fixture.test/xmltv",channels,linked.guide,true,now,
            xml={_,_,_->GuideRead(current,GuideAttempt(GuideSource.XMLTV,GuideStatus.NO_GAMES,GuideCounts(1,1,1)))},
            api={_,_,_->GuideRead(attempt=GuideAttempt(GuideSource.PROVIDER_API,GuideStatus.EMPTY))},
            brazil={_,_->alternateCalled=true;linked})
        assertFalse(alternateCalled)
        assertEquals(active,result.guide.forChannel(channels.first()).first())
        val disabled=recoverSportsGuide(source,"",channels,linked.guide,false,now,
            xml={_,_,_->GuideRead(attempt=GuideAttempt(GuideSource.XMLTV,GuideStatus.EMPTY))},
            api={_,_,_->GuideRead(attempt=GuideAttempt(GuideSource.PROVIDER_API,GuideStatus.EMPTY))},
            brazil={_,_->fail("Disabled fallback must not fetch");linked})
        assertTrue(disabled.guide.matches.isEmpty())
    }

    @Test fun emptyAndPartialSourcesKeepUnexpiredChannelGuideSlots() {
        val p=Programme("Futebol: Porto x Estoril",now,now+3600000L)
        val previous=Guide(programmes=mapOf("espn.5.br" to listOf(p)),matches=listOf(GuideMatch("espn.5.br",p)))
        val other=Programme("Noticiário esportivo",now,now+3600000L)
        val fresh=Guide(programmes=mapOf("espn.4.br" to listOf(other)))
        val empty=Guide(programmes=mapOf("espn.5.br" to emptyList()))
        val merged=mergeProviderGuide(previous,fresh,empty,now)
        assertEquals(listOf(p),merged.forChannel(channels.first()))
        assertEquals(listOf(other),merged.forChannel(channels.last()))
        assertEquals(1,footballMatches(merged,channels,now).size)
        val contradicted=mergeProviderGuide(previous,Guide(programmes=mapOf("espn.5.br" to listOf(other))),empty,now)
        assertEquals(listOf(other),contradicted.forChannel(channels.first()))
        assertTrue(contradicted.matches.isEmpty())
    }

    @Test fun opaqueDeliveryIdsComeFromAuthenticatedApiAndDetailedTablesAreNotTruncatedAt24()=runBlocking {
        val channel=channels.first()
        assertNull(XtreamSportsGuide.streamId(source,channel))
        assertEquals("72",XtreamSportsGuide.streamId(source,channel.copy(url="https://cdn.example.test/fixture/fixture/72.ts")))
        assertNull(XtreamSportsGuide.streamId(source,channel.copy(url="https://cdn.example.test/another/fixture/72.ts")))
        val apiRows=listOf(ProviderGuideStream("9001",channel.name,channel.tvgId))
        assertEquals("9001",XtreamSportsGuide.mappedStream(channel,apiRows))
        assertNull(XtreamSportsGuide.mappedStream(channel,apiRows+apiRows.single().copy(id="9002")))
        assertNull(XtreamSportsGuide.mappedStream(channel,apiRows.map {it.copy(epgId="wrong.br")}))
        val rows=JSONArray()
        repeat(50) {i->rows.put(JSONObject().put("title","Noticiário esportivo")
            .put("start_timestamp",(now-200000000L+i*1000000L)/1000)
            .put("stop_timestamp",(now-199000000L+i*1000000L)/1000))}
        rows.put(JSONObject().put("title","Vila Nova x Novorizontino - Campeonato Brasileiro Série B")
            .put("start_timestamp",(now+3600000L)/1000).put("stop_timestamp",(now+10800000L)/1000))
        val calls=ArrayList<String>()
        val result=XtreamSportsGuide.inspect(source,listOf(channel),now,readChannels={apiRows}) {url->
            calls.add(url.substringAfter("action=").substringBefore('&'))
            assertTrue(url.contains("stream_id=9001"))
            if(url.contains("action=get_short_epg"))JSONObject().put("epg_listings",JSONArray())
            else JSONObject().put("epg_listings",rows)
        }
        assertEquals(listOf("get_short_epg","get_simple_data_table"),calls)
        assertEquals(GuideStatus.READY,result.attempt.status)
        assertEquals("Vila Nova",footballMatches(result.guide,listOf(channel),now).single().details.home)
    }

    @Test fun recoveredGuideAndUpdatedTvgIdsSurviveReopenWithoutChangingCatalogFavoritesOrProgress()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val c=channels.first().copy(tvgId="old-id")
        PlaylistRepository(context).useForTest {repo ->
            repo.saveSource(source,"Teste");repo.appendCache(source,Download(Playlist(listOf(c)),"",""))
            repo.saveFavorites(setOf(c.url))
            repo.importReader(source,java.io.StringReader("#EXTM3U\n#EXTINF:-1 tvg-id=\"espn.5.br\" group-title=\"Esportes\",ESPN 5 FHD\n${c.url}\n"))
            assertEquals(1,repo.overview(source).total)
            assertEquals("espn.5.br",repo.liveChannels(source).single().tvgId)
            repo.saveGuide(source,publicRead().guide,now)
            repo.saveArtwork("guide-diagnostic:test",GuideDiagnostics(listOf(publicRead().attempt),1,now).encode())
        }
        PlaylistRepository(context).useForTest {repo ->
            assertEquals(1,repo.overview(source).total);assertEquals(setOf(c.url),repo.favorites())
            val saved=repo.savedGuide(source)
            assertTrue(footballMatches(saved,repo.liveChannels(source),now).isNotEmpty())
            assertTrue(saved.matches.all {it.programme.metadata.guideSource=="epgshare-br"})
            assertTrue(saved.withoutAlternate().matches.isEmpty())
            assertTrue(GuideDiagnostics.decode(repo.cachedArtwork("guide-diagnostic:test")!!).alternate)
        }
    }
    private inline fun PlaylistRepository.useForTest(block:(PlaylistRepository)->Unit) {try {block(this)}finally {close()}}

    @Test fun httpAuthenticationEmptyStaleMalformedAndGzipAreDiagnosedOnActualTransport()=runBlocking {
        suspend fun serve(code:Int,bytes:ByteArray):GuideRead=withContext(Dispatchers.IO) {
            ServerSocket(0).use {server ->
                val worker=thread {
                    server.accept().use {socket->
                        val input=socket.getInputStream().bufferedReader()
                        while(!input.readLine().isNullOrEmpty()) {}
                        socket.getOutputStream().write(("HTTP/1.1 $code Response\r\nContent-Type: application/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
                    }
                }
                try {readGuide("http://127.0.0.1:${server.localPort}/fixture",emptySet(),now)} finally {worker.join(3000)}
            }
        }
        assertEquals(GuideStatus.AUTH,serve(401,"denied".toByteArray()).attempt.status)
        assertEquals(GuideStatus.HTTP,serve(503,"unavailable".toByteArray()).attempt.status)
        assertEquals(GuideStatus.EMPTY,serve(200,"<tv><channel id=\"a\"/></tv>".toByteArray()).attempt.status)
        assertEquals(GuideStatus.INVALID,serve(200,"<html>login</html>".toByteArray()).attempt.status)
        assertEquals(GuideStatus.INVALID,serve(200,"<tv><programme".toByteArray()).attempt.status)
        val invalidTime="<tv><programme channel=\"a\" start=\"invalid\" stop=\"invalid\"><title>A x B</title></programme></tv>"
        assertEquals(GuideStatus.INVALID,serve(200,invalidTime.toByteArray()).attempt.status)
        val past="<tv><programme channel=\"a\" start=\"20261027000000 +0000\" stop=\"20261027010000 +0000\"><title>Campeonato Brasileiro: A x B</title></programme></tv>"
        assertEquals(GuideStatus.STALE,serve(200,past.toByteArray()).attempt.status)
        val zipped=ByteArrayOutputStream().apply {GZIPOutputStream(this).use {it.write("<tv/>".toByteArray())}}.toByteArray()
        assertEquals(GuideStatus.EMPTY,serve(200,zipped).attempt.status)
    }
}
