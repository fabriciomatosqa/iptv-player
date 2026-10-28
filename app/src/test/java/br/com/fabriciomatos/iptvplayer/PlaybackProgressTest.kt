package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    @Test fun incompleteContentResumesExactlyAndCompletedContentStartsAgain() {
        assertEquals(420000L,PlaybackProgress(420000,3600000).resumePositionMs)
        assertEquals(420000L,PlaybackProgress(420000).resumePositionMs)
        assertEquals(0L,PlaybackProgress(3600000,3600000,true).resumePositionMs)
        assertEquals(0L,PlaybackProgress(420000,300000).resumePositionMs)
        assertEquals(0L,PlaybackProgress(-1).resumePositionMs)
    }
    @Test fun twoMinutesOfAFortyMinuteEpisodeOfferResumeAndReportTheActualFraction() {
        val progress=PlaybackProgress(120000,2400000)
        assertTrue(progress.shouldOfferResume)
        assertEquals(PlaybackWatchState.IN_PROGRESS,progress.watchState)
        assertEquals(.05f,progress.fraction,.0001f)
    }
    @Test fun newCompletedAndInvalidBookmarksNeverOfferResume() {
        assertFalse(PlaybackProgress(0,2400000).shouldOfferResume)
        assertEquals(PlaybackWatchState.NEW,PlaybackProgress(0).watchState)
        assertEquals(0f,PlaybackProgress(0).fraction,0f)
        assertFalse(PlaybackProgress(2400000,2400000,true).shouldOfferResume)
        assertEquals(PlaybackWatchState.COMPLETED,PlaybackProgress(2400000,2400000,true).watchState)
        assertEquals(1f,PlaybackProgress(0,0,true).fraction,0f)
        assertFalse(PlaybackProgress(-1,2400000).shouldOfferResume)
        assertEquals(0f,PlaybackProgress(-1,2400000).fraction,0f)
        assertFalse(PlaybackProgress(2400001,2400000).shouldOfferResume)
        assertEquals(1f,PlaybackProgress(Long.MAX_VALUE,1).fraction,0f)
    }
    @Test fun unknownDurationsRemainResumableWithoutInventingAProgressPercentage() {
        val progress=PlaybackProgress(120000)
        assertTrue(progress.shouldOfferResume)
        assertEquals(PlaybackWatchState.IN_PROGRESS,progress.watchState)
        assertEquals(0f,progress.fraction,0f)
        assertEquals(0f,PlaybackProgress(120000,-1).fraction,0f)
    }
}
