package br.com.fabriciomatos.iptvplayer
import org.junit.Assert.assertEquals
import org.junit.Test
class PlaybackDiagnosticTest {
    @Test fun httpStatusAndCodecFailuresAreDistinct() {
        assertEquals("E2004 · NETWORK · HTTP 403",playbackDiagnosticText(2004,403))
        assertEquals("E2004 · NETWORK · HTTP 404",playbackDiagnosticText(2004,404))
        assertEquals("E2002 · NETWORK",playbackDiagnosticText(2002))
        assertEquals("E3003 · FORMAT",playbackDiagnosticText(3003,403))
        assertEquals("E4005 · CODEC · Dolby Vision · 3840×2160",playbackDiagnosticText(4005,codec="Dolby Vision",width=3840,height=2160))
        assertEquals("E4003 · CODEC · H.265/HEVC · 3840×2160",playbackDiagnosticText(4003,codec="H.265/HEVC",width=3840,height=2160))
    }
    @Test fun credentialsAndUntrustedTextAreNeverDisplayed() {
        assertEquals("E4001 · CODEC",playbackDiagnosticText(4001,codec="https://user:secret@host.test/movie"))
        assertEquals("E2004 · NETWORK",playbackDiagnosticText(2004,999,codec="Dolby Vision",width=99999,height=99999))
    }
}
