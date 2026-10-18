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
class FootballWomenUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun aggregatedWomenTabSearchAndGeneralLiveFilterUseTheSameGames() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val now=System.currentTimeMillis();val source="https://example.test/women.m3u"
        val channels=(0..2).map { Channel("ESPN $it HD","https://example.test/live/$it","Futebol",tvgId="sport$it") }
        val titles=listOf("Libertadores Feminino - Belgrano x Palmeiras","Brasileirão Feminino - Corinthians x Caracas",
            "Belgrano x Palmeiras - Copa Libertadores Feminina")
        val guide=Guide(matches=titles.mapIndexed { i,t -> GuideMatch("sport$i",Programme(t,now-60000,now+7200000,broadcast=BroadcastHint.LIVE)) })
        PlaylistRepository(context).also { repo ->
            repo.saveSource(source,"Futebol");repo.appendCache(source,Download(Playlist(channels,"https://example.test/guide.xml"),"",""));repo.saveGuide(source,guide)
            val games=footballMatches(guide,channels,now)
            footballRequestDays(games,now).forEach { repo.saveArtwork(footballScoreCacheKey("fotmob",it),encodeFootballFeed(FootballFeed(checked=now)).toString()) }
            repo.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AuroraViewModel
            scenario.onActivity { model=ViewModelProvider(it)[AuroraViewModel::class.java] }
            compose.waitUntil(15000) { model.initialized && model.football.size==2 }
            compose.onNodeWithTag("home_football").performClick()
            compose.onNodeWithTag("football_tab_$FOOTBALL_WOMEN").performScrollTo().performClick()
            assertEquals(2,filterFootball(model.football,model.footballTab,"",now).size)
            assertEquals(1,footballTabs(model.football).count { it.key==FOOTBALL_WOMEN })
            compose.onNodeWithTag("football_search").performTextInput("Palmeiras")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Belgrano").assertExists()
            compose.onNodeWithText("Corinthians").assertDoesNotExist()
            compose.onNodeWithTag("football_tab_$FOOTBALL_LIVE").performScrollTo().performClick()
            compose.onNodeWithText("Belgrano").assertExists()
            assertEquals("Palmeiras",model.footballSearch)
            assertEquals(2,model.football.first { it.details.home=="Belgrano" }.channels.size)
            assertTrue(model.football.all { it.programme.broadcast==BroadcastHint.LIVE })
        }
    }
}
