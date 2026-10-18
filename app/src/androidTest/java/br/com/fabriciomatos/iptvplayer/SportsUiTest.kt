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
class SportsUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun modesAndLeaguesNavigateWithoutMixingSportsAndKeepFootballAvailable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val now=System.currentTimeMillis()
        val source="https://example.test/sports-navigation.m3u"
        val sources=listOf(
            "Everton x Chelsea - Premier League" to "a",
            "Philadelphia Eagles x Carolina Panthers - NFL" to "b",
            "Los Angeles Lakers x Boston Celtics - NBA" to "c",
            "Philadelphia Phillies x Arizona Diamondbacks - MLB" to "d",
            "Fórmula 1 - GP São Paulo" to "e"
        )
        val channels=sources.map { (_,id)->
            Channel("SPORTS "+id+" HD","https://example.test/live/"+id,"Esportes",tvgId=id)
        }
        val guide=Guide(matches=sources.map { (title,id)->
            GuideMatch(id,Programme(title,now-30000L,now+7200000L,category="Esportes"))
        })
        PlaylistRepository(context).also { repo->
            repo.saveSource(source,"Esportes")
            repo.appendCache(source,Download(Playlist(channels,"https://example.test/guide.xml"),"",""))
            repo.saveGuide(source,guide)
            repo.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            lateinit var model:AuroraViewModel
            scenario.onActivity { model=ViewModelProvider(it)[AuroraViewModel::class.java] }
            compose.waitUntil(20000) {model.initialized &&
                model.football.size==1 && model.sportsEvents.size==4}
            compose.onNodeWithTag("home_football").performClick()
            compose.onNodeWithTag("sports_tab_football").assertExists()
            compose.onNodeWithTag("sports_tab_baseball").performScrollTo().performClick()
            compose.onNodeWithTag("sports_league_baseball.mlb").assertExists()
            compose.onNodeWithTag("sports_event_card").assertExists()
            compose.onNodeWithText("Philadelphia Phillies").assertExists()
            compose.onNodeWithText("Everton").assertDoesNotExist()
            compose.onNodeWithTag("sports_tab_american_football").performScrollTo().performClick()
            compose.onNodeWithText("Philadelphia Eagles").assertExists()
            compose.onNodeWithText("Philadelphia Phillies").assertDoesNotExist()
            compose.onNodeWithTag("sports_tab_motorsport").performScrollTo().performClick()
            compose.onNodeWithText("Fórmula 1").assertExists()
            compose.onNodeWithTag("sports_tab_football").performScrollTo().performClick()
            compose.onNodeWithText("Everton").assertExists()
            assertEquals(SportKind.FOOTBALL,model.selectedSport)
        }
    }
}
