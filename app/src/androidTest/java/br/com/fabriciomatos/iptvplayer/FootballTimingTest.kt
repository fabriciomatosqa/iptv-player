package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballTimingTest {
    @Test fun aWrongFutureGuideSlotUsesTheConfirmedLiveKickoffAndTheSavedFinishExpiresAfterOneHour() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val now=System.currentTimeMillis();val source="https://example.test/football.m3u"
        val start=now-3600000L
        val match=FootballMatch("botafogo-ceara-future-slot",Programme("Botafogo-SP x Ceará",now+15*3600000L,now+17*3600000L),emptyList(),FootballDetails("Campeonato Brasileiro - Série B","Botafogo-SP","Ceará"))
        val live=FootballScore("fotmob:1","bra.2","Série B",start,ScoreTeam("1",listOf("Botafogo-SP")),ScoreTeam("2",listOf("Ceará")),0,0,MatchState.LIVE,"59:51",now)
        val replay=match.copy(id="replay",programme=match.programme.copy(broadcast=BroadcastHint.REPLAY))
        PlaylistRepository(context).also {
            val timing=FootballScoreRepository(it)
            val corrected=timing.confirmedBroadcasts(source,listOf(match),FootballFeed(listOf(live),now),now).single()
            assertEquals(start,corrected.programme.start);assertTrue(transmissionIsLive(corrected.programme,live,now))
            assertEquals(replay,timing.confirmedBroadcasts(source,listOf(replay),FootballFeed(listOf(live),now),now).single())
            assertNull(matchFootballScore(replay,FootballFeed(listOf(live),now)))
            val finished=live.copy(state=MatchState.FINISHED,checked=now+30000L)
            val ended=timing.confirmedBroadcasts(source,listOf(match),FootballFeed(listOf(finished),now+30000),now+30000).single()
            assertEquals(now+30000L,ended.programme.end)
            assertEquals(1,filterFootball(listOf(ended),FOOTBALL_GAMES,"",ended.programme.end+3599999L).size)
            assertTrue(filterFootball(listOf(ended),FOOTBALL_GAMES,"",ended.programme.end+3600000L).isEmpty())
            it.close()
        }
        PlaylistRepository(context).also {
            val reopened=FootballScoreRepository(it).confirmedBroadcasts(source,listOf(match),FootballFeed(),now+60000).single()
            assertEquals(start,reopened.programme.start);assertEquals(now+30000L,reopened.programme.end)
            assertEquals(match,FootballScoreRepository(it).confirmedBroadcasts("https://other.test/list.m3u",listOf(match),FootballFeed(),now).single())
            it.close()
        }
    }
}
