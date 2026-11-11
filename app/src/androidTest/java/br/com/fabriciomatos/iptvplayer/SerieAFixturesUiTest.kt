package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SerieAFixturesUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun serieATabDoesNotDisplayCachedSeasonFixturesWithoutEpg() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="https://example.test/serie-a-no-epg.m3u"
        val now=System.currentTimeMillis()
        val fixture=FootballScore("serie-a-future","bra.1","Brasileirão",now+3600000L,
            ScoreTeam("flamengo",listOf("Flamengo")),
            ScoreTeam("gremio",listOf("Grêmio")),null,null,MatchState.SCHEDULED,checked=now)
        val repository=PlaylistRepository(context)
        try {
            repository.saveSource(source,"Sem EPG")
            repository.appendCache(source,Download(Playlist(listOf(
                Channel("TV Notícias","https://example.test/news.ts","Notícias",tvgId="news")),
                ""),"",""))
            repository.saveArtwork(footballScoreCacheKey("bra.1",footballDateKey(now).take(4)),
                encodeFootballFeed(FootballFeed(listOf(fixture),now)).toString())
        } finally {repository.close()}
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
            scenario.onActivity {model.page=Page.FOOTBALL;model.footballTab=FOOTBALL_BRAZIL}
            compose.onNodeWithTag("sports_kind_football").assertDoesNotExist()
            compose.onNodeWithTag("sports_competition_bra.1").assertDoesNotExist()
            compose.onNodeWithTag("sports_empty").assertExists()
            assertTrue(footballListings(emptyList(),FootballFeed(listOf(fixture),now),
                FOOTBALL_BRAZIL,"",now).isEmpty())
            compose.onAllNodesWithTag("football_fixture_only").assertCountEquals(0)
            compose.onAllNodesWithTag("football_watch_0").assertCountEquals(0)
        }
    }
}
