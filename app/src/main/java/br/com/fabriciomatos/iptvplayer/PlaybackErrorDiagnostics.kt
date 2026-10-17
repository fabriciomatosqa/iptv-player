package br.com.fabriciomatos.iptvplayer

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException

/** No exception message, media URL, headers or server body may appear in this diagnostic. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackDiagnosticText(error: PlaybackException): String {
    val httpStatus = generateSequence(error as Throwable?) { it.cause }
        .take(12).filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode
    val format = (error as? ExoPlaybackException)?.rendererFormat
    val safeCodec = when (format?.sampleMimeType) {
        "video/dolby-vision" -> "Dolby Vision"
        "video/hevc" -> "H.265/HEVC"
        "video/avc" -> "H.264/AVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mp4v-es" -> "MPEG-4"
        else -> null
    }
    return playbackDiagnosticText(error.errorCode, httpStatus, safeCodec,
        format?.width ?: 0, format?.height ?: 0)
}

/** Pure and privacy-preserving, so diagnostic formatting can be unit-tested. */
internal fun playbackDiagnosticText(
    code: Int, httpStatus: Int? = null, codec: String? = null, width: Int = 0, height: Int = 0
): String {
    val group = when (code) {
        in 2000..2999 -> "NETWORK"
        in 3000..3999 -> "FORMAT"
        in 4000..4999 -> "CODEC"
        in 5000..5999 -> "AUDIO"
        in 6000..6999 -> "DRM"
        else -> "PLAYER"
    }
    val parts = mutableListOf("E" + code, group)
    if(httpStatus != null && httpStatus in 100..599 && group == "NETWORK")parts += "HTTP " + httpStatus
    if(group == "CODEC") {
        if(codec in setOf("Dolby Vision","H.265/HEVC","H.264/AVC","AV1","VP9","MPEG-4"))parts += codec!!
        if(width in 1..16384 && height in 1..16384)parts += width.toString() + "×" + height
    }
    return parts.joinToString(" · ")
}
