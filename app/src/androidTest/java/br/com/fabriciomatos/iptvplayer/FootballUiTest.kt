package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FootballUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun scoreReplayTeamSearchAndTabsWorkAndBackReturnsToFootball() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val now=System.currentTimeMillis();val source="https://example.test/football.m3u"
        val file=File(context.cacheDir,"football.mp4")
        instrumentation.context.assets.open("smoke.mp4").use {input->file.outputStream().use {input.copyTo(it)}}
        val channels=listOf("Brasileirão","AFC","Premier League","Seleções").mapIndexed {i,name->
            Channel("TV $name","${file.toURI()}#channel$i","Esportes",tvgId="sport$i",typeHint="live")
        }
        fun p(title: String, start: Long, hint: BroadcastHint = BroadcastHint.UNKNOWN)=Programme(title,start,start+3*3600000L,broadcast=hint)
        val programmes=listOf(p("Fluminense x Botafogo - Campeonato Brasileiro",now-83*60000L,BroadcastHint.LIVE),
            p("Al Nassr x Neftchi Fergana - AFC Champions League Elite",now+2*3600000L),
            p("Chelsea x Arsenal - Premier League",now+5*3600000L),p("Brasil x Argentina - Amistoso de Futebol",now+6*3600000L))
        val guide=Guide(matches=programmes.mapIndexed {i,p->GuideMatch("sport$i",p)})
        val repository=PlaylistRepository(context)
        repository.saveSource(source,"Agenda de teste");repository.appendCache(source,Download(Playlist(channels,"https://example.test/guide.xml"),"",""));repository.saveGuide(source,guide)
        val json=JSONObject(instrumentation.context.assets.open("scores-afc-final.json").bufferedReader().use {it.readText()})
        val final=parseFootballScoreboard(json,"afc.champions",now).single().copy(kickoff=now-4*3600000L)
        val live=FootballScore("live-test","bra.1","Campeonato Brasileiro",programmes.first().start,ScoreTeam("flu",listOf("Fluminense")),
            ScoreTeam("bot",listOf("Botafogo")),2,1,MatchState.LIVE,"83:21",now)
        val games=footballMatches(guide,channels,now)
        footballRequestDays(games,now).forEach {day->
            repository.saveArtwork(footballScoreCacheKey("fotmob",day),encodeFootballFeed(FootballFeed(listOf(live,final),now)).toString())
        }
        for(league in games.flatMap {footballScoreLeagues(it.details)}.distinct())repository.saveArtwork("football:$league","{}")
        repository.close()
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized && model.football.size==4}
            compose.onNodeWithTag("home_football").assertIsDisplayed().performClick()
            compose.waitUntil(10000) {model.footballFeed.events.size==2 && !model.footballScoresLoading}
            compose.onNodeWithText("83:21").assertExists()
            compose.onNodeWithTag("football_search").performTextInput("Fluminense")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Brasileirão").performClick()
            compose.onAllNodesWithTag("football_match").assertCountEquals(1)
            compose.onNodeWithTag("football_home_goals",useUnmergedTree=true).assertTextEquals("2")
            compose.onNodeWithTag("football_away_goals",useUnmergedTree=true).assertTextEquals("1")
            compose.onNodeWithText("Al Nassr").assertDoesNotExist()
            compose.onNodeWithTag("football_live_dot",useUnmergedTree=true).assertExists()
            compose.onNodeWithText("AO VIVO").assertExists()
            screenshot("football-live-score")
            compose.onNodeWithTag("football_match").performClick()
            compose.waitUntil(15000) {model.isPlaying && model.fromFootball}
            scenario.onActivity {model.player!!.repeatMode=Player.REPEAT_MODE_ONE}
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(10000) {model.page==Page.FOOTBALL && model.current==null}
            assertEquals("Fluminense",model.footballSearch);assertEquals(FOOTBALL_BRAZIL,model.footballTab)
            compose.onNodeWithTag("football_search").assertTextContains("Fluminense")
            compose.onNodeWithTag("football_match").performClick()
            compose.waitUntil(15000) {model.fromFootball && model.isPlaying}
            compose.onNodeWithContentDescription("Voltar ao futebol").performClick()
            compose.waitUntil(10000) {model.page==Page.FOOTBALL && model.current==null}
            compose.onNodeWithTag("football_search").performTextClearance()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Jogos").performClick()
            compose.onNodeWithTag("football_list").performScrollToNode(hasText("Al Nassr"))
            compose.onNode(hasTestTag("football_match") and hasText("Al Nassr")).assertTextContains("Reprise",substring=true)
            compose.onNode(hasTestTag("football_match") and hasText("Al Nassr")).assertTextContains("3")
            screenshot("football-replay-result")
            scenario.onActivity {model.footballTab=FOOTBALL_FIFA}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Brasil").fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodesWithTag("football_match").assertCountEquals(1)
            compose.onNodeWithText("Brasil").assertExists();compose.onNodeWithText("Argentina").assertExists()
        }
    }
    private fun screenshot(name: String) {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/$name.png")
    }
}
