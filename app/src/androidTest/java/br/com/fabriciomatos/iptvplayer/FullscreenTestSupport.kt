package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Await the actual screen and acknowledge Android's first-use immersive tutorial. */
internal fun ComposeTestRule.awaitFullscreenPlayer() {
    waitUntil(15000) {
        onAllNodesWithTag("video_back").fetchSemanticsNodes().isNotEmpty() &&
            onAllNodesWithTag("landscape_title_details").fetchSemanticsNodes().isEmpty()
    }
    waitForIdle()
    val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val tutorial=By.res("android","immersive_cling_description")
    if(device.wait(Until.hasObject(tutorial),2000)) {
        checkNotNull(device.findObject(By.res("android","ok"))) {"Immersive tutorial has no confirmation button"}.click()
        check(device.wait(Until.gone(tutorial),3000)) {"Immersive tutorial did not close"}
    }
    waitForIdle()
}

/** Keep failure evidence from the actual native Back path, without exposing stream URLs. */
internal fun ComposeTestRule.awaitEpisodeReturn(model: AuroraViewModel, episodeTag: String? = null) {
    try {
        waitUntil(10000) {
            !model.fullscreen && model.seriesDetails.episodesVisible &&
                (episodeTag==null || onAllNodesWithTag(episodeTag).fetchSemanticsNodes().isNotEmpty())
        }
    } catch(failure: Throwable) {
        val device=androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/aurora-ui")
        device.executeShellCommand("screencap -p /sdcard/Download/aurora-ui/episode-back-failure.png")
        throw AssertionError("Episode Back failed: fullscreen=${model.fullscreen}, page=${model.page}, " +
            "episodes=${model.seriesDetails.episodesVisible}, details=${model.seriesDetails.series!=null}, " +
            "scroll=${model.seriesDetails.scroll.firstVisibleItemIndex}",failure)
    }
}
