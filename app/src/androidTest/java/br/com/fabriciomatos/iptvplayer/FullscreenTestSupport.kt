package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag

/** Playback can start before Compose replaces the details and registers fullscreen Back. */
internal fun ComposeTestRule.awaitFullscreenPlayer() {
    waitUntil(15000) {
        onAllNodesWithTag("video_back").fetchSemanticsNodes().isNotEmpty() &&
            onAllNodesWithTag("landscape_title_details").fetchSemanticsNodes().isEmpty()
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
