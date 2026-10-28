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
