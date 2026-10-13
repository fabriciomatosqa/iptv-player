package br.com.fabriciomatos.iptvplayer

data class PlaybackProgress(val positionMs: Long, val durationMs: Long = 0, val completed: Boolean = false) {
    val resumePositionMs: Long get() = if (completed || positionMs < 0 || durationMs > 0 && positionMs >= durationMs) 0 else positionMs
}

internal data class PlaybackCheckpoint(val source: String, val url: String, val progress: PlaybackProgress)
