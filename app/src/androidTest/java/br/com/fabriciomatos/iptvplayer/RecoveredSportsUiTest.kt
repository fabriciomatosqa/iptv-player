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
class RecoveredSportsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun recoveredBroadcastKeepsOriginalCardsWatchButtonsChannelPickerAndSourceDiagnostic() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val source="https://example.test/recovered.m3u";val now=System.currentTimeMillis()
        val channels=listOf("FHD","HD [ALT]").mapIndexed {i,q->Channel("ESPN 5 $q",
            "https://delivery.example.test/play/opaque-$i","Esportes",tvgId="espn.5.br")}
        val p=Programme("VT - Porto x Estoril",now+3600000,now+10800000,broadcast=BroadcastHint.REPLAY,
            metadata=FootballMetadata(description="Futebol português",guideSource="epgshare-br"))
        val guide=Guide(matches=listOf(GuideMatch("espn.5.br",p)))
        val repo=PlaylistRepository(context)
        try {
            repo.saveSource(source,"Guia recuperado");repo.appendCache(source,Download(Playlist(channels),"",""));repo.saveGuide(source,guide)
            val diagnostic=GuideDiagnostics(listOf(GuideAttempt(GuideSource.XMLTV,GuideStatus.EMPTY,GuideCounts(channels=269)),
                GuideAttempt(GuideSource.PROVIDER_API,GuideStatus.STALE,GuideCounts(2,95)),
                GuideAttempt(GuideSource.EPGSHARE_BR,GuideStatus.READY,GuideCounts(2,1,1,1))),1,now)
            repo.saveArtwork("guide-diagnostic:"+PlaylistRepository.digest(source),diagnostic.encode())
            footballRequestDays(footballMatches(guide,channels,now),now).forEach {day->
                repo.saveArtwork(footballScoreCacheKey("fotmob",day),encodeFootballFeed(FootballFeed(checked=now)).toString())
            }
        } finally {repo.close()}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000) {model.initialized && model.football.size==1}
            // Opening the existing recovered cache must not need public fixture generation.
            scenario.onActivity {model.page=Page.FOOTBALL}
            compose.openSportsDiscipline()
            compose.openSportsCompetition("unknown")
            compose.onNodeWithTag("football_match").assertExists()
            compose.onNodeWithText("Porto × Estoril").assertExists()
            compose.onNodeWithTag("sports_guide_source").assertExists()
            // Replays intentionally start compact; expand the existing card before checking its actions.
            compose.onNodeWithTag("football_match").performClick()
            compose.onNodeWithText("Porto",useUnmergedTree=true).assertExists()
            compose.onNodeWithText("Estoril",useUnmergedTree=true).assertExists()
            compose.onNodeWithTag("football_compact_watch").assertExists()
            compose.onNodeWithTag("football_all_channels").performScrollTo().performClick()
            compose.onNodeWithText("ESPN 5 HD [ALT]").assertExists()
            assertEquals(2,model.football.single().channels.size)
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            scenario.onActivity {model.openSettings();model.settingsSection=4}
            compose.onNodeWithTag("guide_diagnostics").performScrollTo().assertExists()
            compose.onNodeWithText("O guia contém apenas programação vencida.",substring=true).assertExists()
            assertTrue(model.guideDiagnostics.alternate)
            val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
            device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/recovered-guide-diagnostics.png")
        }
    }
}
