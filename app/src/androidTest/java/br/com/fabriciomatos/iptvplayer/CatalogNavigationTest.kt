package br.com.fabriciomatos.iptvplayer

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CatalogNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun moviesAndEpisodesReturnToTheirCategorySearchAndScrollPosition() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        resetCatalog(context)
        val video=File(context.cacheDir,"navigation.mp4")
        instrumentation.context.assets.open("smoke.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        val source="https://example.test/navigation.m3u"
        val rows=buildList {
            repeat(360) {i->add(Channel("Filme terror %03d".format(i),"${video.toURI()}#movie$i","Terror",typeHint="movie"))}
            add(Channel("Filme drama","${video.toURI()}#drama","Drama",typeHint="movie"))
            repeat(360) {i->add(Channel("Série suspense %03d S01E01".format(i),"${video.toURI()}#series$i","Suspense",typeHint="series"))}
            add(Channel("Outra série S01E01","${video.toURI()}#other","Comédia",typeHint="series"))
        }
        val repository=PlaylistRepository(context)
        repository.saveSource(source,"Navegação");repository.appendCache(source,Download(Playlist(rows),"",""))
        // Resolve anchors in the entire catalog, even after the 240-row in-memory window is dropped.
        val movies=BrowseQuery(source,MediaKind.MOVIES,group="Terror",groupAliases=listOf("Terror"))
        assertEquals(340,repository.catalogOffset(movies,rows[340].url))
        val shows=BrowseQuery(source,MediaKind.SERIES,group="Suspense",search="suspense")
        val distantSeries=repository.series(shows,340,1).single()
        assertEquals(340,repository.catalogOffset(shows,distantSeries.id))
        assertNull(repository.catalogOffset(movies,rows[360].url))
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model: AuroraViewModel
            scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java];it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(15000) {model.initialized && model.catalog.total==722}
            scenario.onActivity {model.browse(MediaKind.MOVIES);model.selectGroup("Terror")}
            waitForText("Filme terror 000")
            compose.onNodeWithTag("channel_list").performScrollToIndex(40)
            val movieKey=model.catalogPositionKey()
            compose.waitUntil(10000) {model.catalogAnchor(movieKey)?.item==rows[40].url}
            val movieAnchor=model.catalogAnchor(movieKey)!!
            compose.onNodeWithText(rows[41].name).performClick()
            compose.waitUntil(15000) {model.current?.url==rows[41].url && model.isPlaying}
            compose.onNodeWithContentDescription("Voltar").performClick()
            compose.waitUntil(10000) {model.current==null && model.group=="Terror" && !model.filtering}
            compose.onNode(hasText("Terror") and isSelected()).assertExists()
            compose.onNodeWithText("Filme drama").assertDoesNotExist()
            waitForText(rows[40].name)
            assertEquals(movieAnchor.item,model.catalogAnchor(movieKey)?.item)
            assertEquals(movieAnchor.offset,model.catalogAnchor(movieKey)?.offset)

            scenario.onActivity {model.browse(MediaKind.SERIES);model.selectGroup("Suspense");model.search("suspense")}
            waitForText("Série suspense 000")
            compose.onNodeWithTag("series_grid").performScrollToIndex(20)
            val seriesKey=model.catalogPositionKey()
            compose.waitUntil(10000) {model.catalogAnchor(seriesKey)?.index==20}
            val seriesAnchor=model.catalogAnchor(seriesKey)!!
            val selected=repository.series(shows,21,1).single()
            compose.onNodeWithText(selected.title).performClick()
            val episode=repository.channels(shows.copy(seriesId=selected.id,group=null,search="",season=1)).single()
            waitForText(episode.name)
            compose.onNodeWithText(episode.name).performClick()
            compose.waitUntil(15000) {model.isPlaying && model.current?.url==episode.url}
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(10000) {model.openedSeriesId==null && model.current==null && !model.filtering}
            assertEquals(Page.LIBRARY,model.page);assertEquals("Suspense",model.group);assertEquals("suspense",model.search)
            compose.onNode(hasText("Suspense") and isSelected()).assertExists()
            waitForText(repository.seriesDetail(source,seriesAnchor.item)!!.title)
            compose.waitUntil(10000) {model.catalogAnchor(seriesKey)?.item==seriesAnchor.item}
            assertEquals(seriesAnchor.offset,model.catalogAnchor(seriesKey)?.offset)
            val device=UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
            device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/series-category-return.png")
        }
        repository.close()
    }
    private fun waitForText(text: String)=compose.waitUntil(15000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
}
