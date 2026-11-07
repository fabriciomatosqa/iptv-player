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

    @Test fun serieATabShowsEveryCachedFixtureEvenWithNoEpgAndNeverOffersFakeWatchButton() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="https://example.test/serie-a-no-epg.m3u"
        val now=System.currentTimeMillis()
        val fixtures=listOf(
            FootballScore("serie-a-future","bra.1","Brasileirão",now+3600000L,
                ScoreTeam("flamengo",listOf("Flamengo"),"https://a.espncdn.com/escudo-flamengo.png"),
                ScoreTeam("gremio",listOf("Grêmio"),"https://a.espncdn.com/escudo-gremio.png"),
                null,null,MatchState.SCHEDULED,checked=now),
            FootballScore("serie-a-past","bra.1","Brasileirão",now-5*86400000L,
                ScoreTeam("corinthians",listOf("Corinthians")),
                ScoreTeam("santos",listOf("Santos")),
                2,1,MatchState.FINISHED,checked=now))
        val repository=PlaylistRepository(context)
        try {
            repository.saveSource(source,"Sem EPG")
            repository.appendCache(source,Download(Playlist(listOf(
                Channel("TV Notícias","https://example.test/news.ts","Notícias",tvgId="news")),
                ""),"",""))
            repository.saveArtwork(footballScoreCacheKey("bra.1",footballDateKey(now).take(4)),
                encodeFootballFeed(FootballFeed(fixtures,now)).toString())
            for(offset in listOf(-1L,0L,1L)) {
                repository.saveArtwork(footballScoreCacheKey("fotmob",footballDateKey(now+offset*86400000L)),
                    encodeFootballFeed(FootballFeed(checked=now)).toString())
            }
        } finally {repository.close()}
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==1}
            scenario.onActivity {model.page=Page.FOOTBALL;model.footballTab=FOOTBALL_BRAZIL}
            compose.waitUntil(15000) {model.footballFeed.events.any {it.id=="serie-a-future"}}
            compose.onNodeWithTag("football_tab_"+FOOTBALL_BRAZIL).assertExists()
            assertTrue("Both fixture-only cards must remain visible without EPG",
                compose.onAllNodesWithTag("football_fixture_only").fetchSemanticsNodes().size >= 2)
            compose.onAllNodesWithText("Partida do calendário sem transmissão vinculada à sua lista.",
                substring=true).onFirst().assertExists()
            assertEquals(2,footballListings(emptyList(),model.footballFeed,
                FOOTBALL_BRAZIL,"",System.currentTimeMillis()).size)
            compose.onAllNodesWithTag("football_watch_0").assertCountEquals(0)
            scenario.onActivity {model.footballSearch="Corinthians"}
            compose.waitUntil(5000) {compose.onAllNodesWithText("Corinthians").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(1,footballListings(emptyList(),model.footballFeed,FOOTBALL_BRAZIL,
                "Corinthians",System.currentTimeMillis()).size)
            compose.onAllNodesWithTag("football_watch_0").assertCountEquals(0)
        }
    }
}
