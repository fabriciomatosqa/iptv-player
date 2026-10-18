package br.com.fabriciomatos.iptvplayer

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballTimingTest {
    @Test fun legacyTimingCacheCannotMoveTheGuideAfterReopeningAndRefreshingScores() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;resetCatalog(context)
        val now=System.currentTimeMillis();val source="https://example.test/football.m3u"
        val channel=Channel("ESPN 4 FHD","https://example.test/live/espn4","Esportes",tvgId="espn.4.br")
        val p=Programme("Vila Nova x Novorizontino - Campeonato Brasileiro - Série B",now+5*3600000L,now+7*3600000L)
        val guide=Guide(mapOf(channel.tvgId to listOf(p)),matches=listOf(GuideMatch(channel.tvgId,p)))
        val match=footballMatches(guide,listOf(channel),now).single()
        val live=FootballScore("fotmob:1","bra.2","Série B",now-3600000L,ScoreTeam("1",listOf("Vila Nova")),
            ScoreTeam("2",listOf("Novorizontino")),0,1,MatchState.LIVE,"59:51",now)
        val legacyKey="football-timing:${PlaylistRepository.digest(source)}:${match.id}"
        PlaylistRepository(context).also { repo ->
            repo.saveSource(source,"Timing regression")
            repo.appendCache(source,Download(Playlist(listOf(channel),"https://example.test/guide.xml"),"",""))
            repo.saveGuide(source,guide)
            repo.saveArtwork(legacyKey,JSONObject().put("start",live.kickoff).put("duration",7200000L)
                .put("finished",0L).put("observed_live",true).toString())
            footballRequestDays(listOf(match),now).forEach { day ->
                repo.saveArtwork(footballScoreCacheKey("fotmob",day),encodeFootballFeed(FootballFeed(listOf(live),now)).toString())
            }
            repo.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AuroraViewModel
            scenario.onActivity { model=ViewModelProvider(it)[AuroraViewModel::class.java] }
            await { model.initialized && model.football.size==1 }
            scenario.onActivity { model.showFootball() }
            await { model.footballFeed.events.isNotEmpty() && !model.footballScoresLoading }
            scenario.onActivity {
                val restored=model.football.single()
                assertEquals(p,restored.programme)
                assertEquals(p,restored.programmeFor(channel))
                assertTrue(filterFootball(listOf(restored),FOOTBALL_LIVE,"",now,model.footballFeed).isEmpty())
                assertEquals(0,matchFootballScore(restored,model.footballFeed)?.homeGoals)
            }
        }
        PlaylistRepository(context).also { repo ->
            assertEquals(p,repo.savedGuide(source).matches.single().programme)
            assertNotNull(repo.cachedArtwork(legacyKey)) // No deletion of unrelated user data or caches.
            repo.close()
        }
    }
    @Test fun ambiguousXmltvNamesStayAmbiguousAfterDatabaseReopening() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val now=xmltvTime("20261018180000 -0300")
        val xml="""<tv><channel id="a"><display-name>ESPN 4</display-name></channel>
            <channel id="b"><display-name>ESPN 4 HD</display-name></channel>
            <programme channel="a" start="20261018180000 -0300" stop="20261018200000 -0300">
            <title>Vila Nova x Novorizontino - Campeonato Brasileiro - Série B</title><live/></programme></tv>"""
        val guide=parseGuide(xml.byteInputStream(),"https://example.test/guide.xml",setOf("a"),now)
        PlaylistRepository(context).also { it.saveGuide("fixture",guide,now);it.close() }
        PlaylistRepository(context).also { repo ->
            val saved=repo.savedGuide("fixture")
            val idless=Channel("ESPN 4 FHD","https://example.test/live/a","Esportes")
            assertTrue(saved.forChannel(idless).isEmpty())
            assertTrue(footballMatches(saved,listOf(idless),now).isEmpty())
            assertEquals(1,footballMatches(saved,listOf(idless.copy(tvgId="a")),now).size)
            repo.close()
        }
    }
    private fun await(condition: ()->Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+15000L
        while(!condition() && android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50)
        assertTrue("Expected football state within 15 seconds",condition())
    }
}
