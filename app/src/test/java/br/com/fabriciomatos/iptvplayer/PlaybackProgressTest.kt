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
}
