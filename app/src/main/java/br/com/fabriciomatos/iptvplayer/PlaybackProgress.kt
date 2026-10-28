package br.com.fabriciomatos.iptvplayer

enum class PlaybackWatchState { NEW, IN_PROGRESS, COMPLETED }

data class PlaybackProgress(val positionMs: Long, val durationMs: Long = 0, val completed: Boolean = false) {
    val resumePositionMs: Long get() = if (completed || positionMs < 0 || durationMs > 0 && positionMs >= durationMs) 0 else positionMs
    val shouldOfferResume: Boolean get() = resumePositionMs > 0
    val watchState: PlaybackWatchState get() = when {
        completed || durationMs > 0 && positionMs >= durationMs -> PlaybackWatchState.COMPLETED
        positionMs > 0 -> PlaybackWatchState.IN_PROGRESS
        else -> PlaybackWatchState.NEW
    }
    /** Unknown durations cannot produce a misleading percentage or divide by zero. */
    val fraction: Float get() = when {
        watchState == PlaybackWatchState.COMPLETED -> 1f
        durationMs > 0 -> (positionMs.coerceAtLeast(0).toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        else -> 0f
    }
}

internal data class PlaybackCheckpoint(val source: String, val url: String, val progress: PlaybackProgress)
