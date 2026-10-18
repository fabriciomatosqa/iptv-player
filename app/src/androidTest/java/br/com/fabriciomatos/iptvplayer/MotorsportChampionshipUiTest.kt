package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class MotorsportChampionshipUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun formulaOneChampionshipReturnsToTheSameExistingBroadcasts() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        resetCatalog(context)
        val now=System.currentTimeMillis()
        val year=Calendar.getInstance().get(Calendar.YEAR)
        val source="https://example.test/championship.m3u"
        val channel=Channel("Example TV","https://example.test/f1","Sports",tvgId="f1")
        val guide=Guide(matches=listOf(GuideMatch("f1",Programme("F1 - GP do Brasil - Corrida",
            now-60000,now+7200000,broadcast=BroadcastHint.LIVE))))
        PlaylistRepository(context).also { repo ->
            repo.saveSource(source,"Test")
            repo.appendCache(source,Download(Playlist(listOf(channel),"https://example.test/guide.xml"),"",""))
            repo.saveGuide(source,guide)
            repo.recordAttempt(source,now)
            val paths=listOf("$year/","$year/driverStandings/","$year/constructorStandings/")+
                (1..3).map { "$year/results/$it/" }
            paths.forEach { path ->
                val data=JSONObject().put("series","f1").put("total","0").put("offset","0")
                    .put("RaceTable",JSONObject().put("season",year).put("Races",org.json.JSONArray()))
                repo.saveArtwork("motorsport-jolpica-v1:$path",JSONObject().put("checked",now).put("data",data).toString())
            }
            repo.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AuroraViewModel
            scenario.onActivity { model=ViewModelProvider(it)[AuroraViewModel::class.java] }
            compose.waitUntil(15000) { model.initialized && model.sports.any { it.competitionId=="f1" } }
            compose.onNodeWithTag("home_football").performClick()
            compose.onNodeWithTag("sports_kind_motorsport").performScrollTo().performClick()
            compose.onNodeWithTag("football_tab_f1").performScrollTo().performClick()
            compose.onNodeWithTag("motorsport_event").assertExists()
            val before=model.sports.toList()
            compose.onNodeWithTag("open_f1_championship").performClick()
            compose.onNodeWithTag("motorsport_championship").assertExists()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("championship_loading").fetchSemanticsNodes().isEmpty() }
            val device=UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
            val headings=listOf("FIA Formula One World Championship","Classificação dos pilotos",
                "Classificação dos construtores","Próxima corrida",
                "Calendário da temporada · horários do aparelho","Estatísticas e disputa pelo título")
            headings.forEachIndexed { index,heading ->
                compose.onNodeWithTag("championship_sections").performScrollToIndex(index)
                compose.onNodeWithTag("championship_section_$index")
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.onNodeWithTag("championship_section_$index").assertIsSelected()
                compose.onNodeWithTag("championship_content").performScrollToNode(hasText(heading))
                compose.waitForIdle()
                device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/f1-championship-$index.png")
            }
            compose.onNodeWithTag("championship_sections").performScrollToIndex(1)
            compose.onNodeWithTag("championship_section_1")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("championship_content").performScrollToNode(hasText("Classificação: indisponível"))
            compose.onNodeWithText("Classificação: indisponível").assertExists()
            device.pressBack()
            compose.onNodeWithTag("motorsport_championship").assertDoesNotExist()
            compose.onNodeWithTag("motorsport_event").assertExists()
            assertEquals("f1",model.sportsCategory)
            assertEquals(SportKind.MOTORSPORT,model.selectedSport)
            assertEquals(before,model.sports)
            compose.onNodeWithTag("open_f1_championship").performClick()
            compose.onNodeWithTag("championship_broadcasts").performClick()
            compose.onNodeWithTag("motorsport_event").assertExists()
        }
    }
}
