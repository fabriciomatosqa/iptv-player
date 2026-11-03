package br.com.fabriciomatos.iptvplayer

import org.json.JSONArray
import org.json.JSONObject

/** Only enums, counters and timestamps may reach diagnostics. Never store request URLs/errors. */
enum class GuideStatus { NOT_CONFIGURED, NETWORK, AUTH, HTTP, INVALID, EMPTY, STALE, NO_GAMES, UNLINKED, READY, INTERNAL }
enum class GuideSource { XMLTV, PROVIDER_API, EPGSHARE_BR }
data class GuideCounts(val channels: Int = 0, val programmes: Int = 0, val current: Int = 0, val candidates: Int = 0)
data class GuideAttempt(val source: GuideSource, val status: GuideStatus, val counts: GuideCounts = GuideCounts(), val http: Int = 0)
data class GuideRead(val guide: Guide = Guide(), val attempt: GuideAttempt)
data class GuideDiagnostics(val attempts: List<GuideAttempt> = emptyList(), val linked: Int = 0, val checked: Long = 0) {
    val alternate get() = attempts.any { it.source == GuideSource.EPGSHARE_BR && it.status == GuideStatus.READY }
    val status get() = if(linked>0) GuideStatus.READY else attempts.lastOrNull { it.status !in listOf(GuideStatus.NOT_CONFIGURED,GuideStatus.EMPTY) }?.status
        ?: attempts.lastOrNull()?.status ?: GuideStatus.NOT_CONFIGURED
    fun encode() = JSONObject().put("checked",checked).put("linked",linked).put("attempts",JSONArray().apply {
        attempts.forEach { a -> put(JSONObject().put("source",a.source.name).put("status",a.status.name)
            .put("http",a.http).put("channels",a.counts.channels).put("programmes",a.counts.programmes)
            .put("current",a.counts.current).put("candidates",a.counts.candidates)) }
    }).toString()
    companion object {
        fun decode(value: String): GuideDiagnostics = runCatching {
            val o=JSONObject(value);val rows=o.getJSONArray("attempts")
            GuideDiagnostics((0 until minOf(rows.length(),3)).map { i -> val a=rows.getJSONObject(i)
                GuideAttempt(GuideSource.valueOf(a.getString("source")),GuideStatus.valueOf(a.getString("status")),
                    GuideCounts(a.optInt("channels"),a.optInt("programmes"),a.optInt("current"),a.optInt("candidates")),a.optInt("http"))
            },o.optInt("linked"),o.optLong("checked"))
        }.getOrDefault(GuideDiagnostics())
    }
}

internal fun guideStatus(counts: GuideCounts, linked: Int) = when {
    counts.programmes==0 -> GuideStatus.EMPTY
    counts.current==0 -> GuideStatus.STALE
    linked>0 -> GuideStatus.READY
    counts.candidates>0 -> GuideStatus.UNLINKED
    else -> GuideStatus.NO_GAMES
}

internal fun Guide.withoutAlternate(): Guide = copy(
    programmes=programmes.mapValues { (_,p)->p.filter {it.metadata.guideSource!="epgshare-br"} }.filterValues {it.isNotEmpty()},
    matches=matches.filter {it.programme.metadata.guideSource!="epgshare-br"})

internal fun guideStatusResource(status:GuideStatus):Int = when(status) {
    GuideStatus.NOT_CONFIGURED->R.string.guide_not_configured
    GuideStatus.NETWORK->R.string.guide_network
    GuideStatus.AUTH->R.string.guide_auth
    GuideStatus.HTTP->R.string.guide_http
    GuideStatus.INVALID->R.string.guide_invalid
    GuideStatus.EMPTY->R.string.guide_empty
    GuideStatus.STALE->R.string.guide_stale
    GuideStatus.NO_GAMES->R.string.no_games
    GuideStatus.UNLINKED->R.string.guide_unlinked
    GuideStatus.INTERNAL->R.string.guide_internal
    GuideStatus.READY->R.string.guide_ready
}
