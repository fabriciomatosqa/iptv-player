package br.com.fabriciomatos.iptvplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.openSportsDiscipline(id:String="football") {
    waitUntil(15000) {onAllNodesWithTag("sports_kind_$id").fetchSemanticsNodes().isNotEmpty()}
    onNodeWithTag("sports_kind_$id").performScrollTo().performClick()
}

internal fun ComposeTestRule.openSportsCompetition(id:String) {
    waitForIdle()
    onNodeWithTag("football_list").performScrollToNode(hasTestTag("sports_competition_$id"))
    onNodeWithTag("sports_competition_$id").performClick()
    waitForIdle()
}
