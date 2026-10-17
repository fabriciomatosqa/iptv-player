package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendlyPlaybackTest {
    @Test fun displaysCauseSpecificAdviceWithoutClaimingEveryServerFailureIsAnAppBug() {
        assertEquals(R.string.playback_http_not_found,playbackFriendlyResource(2004,404))
        assertEquals(R.string.playback_http_unavailable,playbackFriendlyResource(2004,403))
        assertEquals(R.string.playback_http_unavailable,playbackFriendlyResource(2004,503))
        assertEquals(R.string.playback_network_failure,playbackFriendlyResource(2002))
        assertEquals(R.string.playback_format_failure,playbackFriendlyResource(3003))
        assertEquals(R.string.playback_codec_failure,playbackFriendlyResource(4005))
        assertEquals(R.string.playback_generic_failure,playbackFriendlyResource(-1))
    }
}
