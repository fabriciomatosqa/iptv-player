package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewFeaturesTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="https://example.test/features.m3u"
    @Before fun reset()=resetCatalog(context)
    private fun seed() {
        PlaylistRepository(context).also {
            it.saveSource(source,"Teste de recursos")
            it.appendCache(source,Download(Playlist(listOf(Channel("Filme livre","https://example.test/movie/free","Filmes"),
                Channel("Filme privado","https://example.test/movie/adult","🔞 Adultos"))),"",""));it.close()
        }
    }
    @Test fun adultCategoryRequiresSetupAndPasswordAndLocksOnLeavingApp() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000){model.initialized}
            compose.onNode(hasText("Filmes") and hasText("2 filmes")).performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Filme livre").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Filme privado").assertDoesNotExist()
            compose.onNodeWithText("Conteúdo +18").performClick()
            compose.onNodeWithText("Nova senha").performTextInput("2580")
            compose.onNodeWithText("Confirmar senha").performTextInput("2580")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Salvar").performClick()
            compose.waitUntil(10000){model.adultUnlocked && !model.parentalDialog}
            compose.waitUntil(10000){compose.onAllNodesWithText("Filme privado").fetchSemanticsNodes().isNotEmpty()}
            scenario.onActivity {model.foreground(false)}
            compose.waitUntil(10000){!model.adultUnlocked}
            compose.onNodeWithText("Filme privado").assertDoesNotExist()
            compose.onNodeWithText("Conteúdo +18").performClick()
            compose.onNodeWithText("Senha atual").performTextInput("0000")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Desbloquear").performClick()
            compose.waitUntil(10000){model.parentalError!=null && !model.parentalBusy}
            assertFalse(model.adultUnlocked)
            compose.onNodeWithText("Cancelar").performClick()
            // Playback is guarded even when an adult URL is supplied outside category navigation.
            scenario.onActivity {model.play(Channel("Filme privado","https://example.test/movie/adult","Adultos"))}
            compose.waitUntil(5000){model.parentalDialog}
            assertNull(model.current)
            compose.onNodeWithText("Senha atual").performTextClearance()
            compose.onNodeWithText("Senha atual").performTextInput("2580")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Desbloquear").performClick()
            compose.waitUntil(10000){model.current?.adult==true}
            scenario.onActivity {model.foreground(false)}
            assertNull(model.current)
        }
        assertTrue(AppSettings(context).hasPassword())
    }
    @Test fun languageSettingsTranslateInterfaceAndSurviveActivityReopening() {
        seed()
        assertEquals(20,appLanguages.size)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000){model.initialized}
            compose.onNodeWithTag("settings_gear").performClick()
            compose.onNodeWithText("Idioma").performClick()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("Language").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("en",AppSettings(context).language())
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
            compose.waitUntil(15000){model.initialized}
            compose.onNodeWithText("Movies").assertExists()
            scenario.onActivity {model.chooseLanguage("ar")}
            compose.waitUntil(15000){compose.onAllNodesWithText("أفلام").fetchSemanticsNodes().isNotEmpty()}
            scenario.onActivity {assertEquals(android.view.View.LAYOUT_DIRECTION_RTL,it.resources.configuration.layoutDirection);model.chooseLanguage("pt-BR")}
            compose.waitUntil(15000){compose.onAllNodesWithText("Filmes").fetchSemanticsNodes().isNotEmpty()}
        }
    }
    @Test fun passwordsAreHashedAndRepeatedWrongAttemptsAreThrottled() {
        val settings=AppSettings(context)
        assertTrue(settings.setPassword("","2580"))
        assertFalse(settings.setPassword("wrong","3690"))
        val now=System.currentTimeMillis()
        repeat(5){assertFalse(settings.verifyPassword("wrong",now))}
        assertFalse(settings.verifyPassword("2580",now))
        assertTrue(settings.verifyPassword("2580",now+30001))
        assertTrue(settings.setPassword("2580","3690"))
        assertFalse(context.getSharedPreferences("aurora_settings",0).all.values.contains("3690"))
        assertFalse(settings.verifyPassword("2580"));assertTrue(settings.verifyPassword("3690"))
    }
    @Test fun futureGamesMapToLiveChannelsAndGuideCacheSurvivesReopening() {
        val now=xmltvTime("20261012090000 -0300")
        val xml="""<tv><channel id="sport"><display-name>TV Aurora</display-name></channel>
            <programme channel="sport" start="20261012080000 -0300" stop="20261012110000 -0300"><title>Futebol: Aurora x Norte</title></programme>
            <programme channel="sport" start="20261014180000 -0300" stop="20261014200000 -0300"><title>Champions League: Leste vs Oeste</title></programme>
            <programme channel="missing" start="20261014180000 -0300" stop="20261014200000 -0300"><title>Futebol: Não disponível x Outro</title></programme>
            <programme channel="sport" start="20261012110000 -0300" stop="20261012120000 -0300"><title>Arquitetos Brasileiros</title></programme></tv>"""
        val guide=parseGuide(xml.byteInputStream(),source,setOf("sport"),now)
        val channel=Channel("TV Aurora FHD","https://example.test/live/sport","Esportes",tvgId="sport")
        val games=footballMatches(guide,listOf(channel),now)
        assertEquals(2,games.size);assertTrue(games.last().programme.start>now+86400000L)
        assertEquals(channel.url,games.first().channels.single().url)
        val repository=PlaylistRepository(context);repository.saveGuide(source,guide);repository.close()
        val reopened=PlaylistRepository(context)
        assertEquals(games,footballMatches(reopened.savedGuide(source),listOf(channel),now));reopened.close()
    }
    @Test fun unknownAnimeNeverDisplaysAnUnrelatedProviderPhoto() {
        val repository=PlaylistRepository(context)
        repository.appendCache(source,Download(Playlist(listOf(Channel("Anime desconhecido S01E01","https://example.test/series/1","Crunchyroll","https://example.test/unrelated-actor.jpg"))),"",""))
        assertEquals("",repository.series(BrowseQuery(source,MediaKind.SERIES)).single().imageUrl)
        assertEquals("",repository.channels(BrowseQuery(source,MediaKind.SERIES)).single().imageUrl)
        assertEquals(0,repository.matchingCount(BrowseQuery(source,MediaKind.MOVIES,group=ADULT_GROUP)))
        repository.close()
    }
}
