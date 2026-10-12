package br.com.fabriciomatos.iptvplayer

import android.util.Xml
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Programme(val title: String, val start: Long, val end: Long, val image: String = "")
data class GuideMatch(val channelId: String, val programme: Programme)
data class FootballMatch(val id: String, val programme: Programme, val channels: List<Channel>)
fun footballMatches(guide: Guide, channels: List<Channel>, now: Long = System.currentTimeMillis()): List<FootballMatch> {
    val live = channels.filter { it.kind == MediaKind.LIVE && !it.adult }
    val byId = live.filter { it.tvgId.isNotBlank() }.groupBy { it.tvgId }
    val byName = live.groupBy { guideChannelName(it.name) }
    val epgNames = guide.names.entries.groupBy({ it.value }, { guideChannelName(it.key) })
    val result = linkedMapOf<String, FootballMatch>()
    guide.matches.filter { it.programme.end > now }.forEach { entry ->
        val available = (byId[entry.channelId].orEmpty() + epgNames[entry.channelId].orEmpty().flatMap { byName[it].orEmpty() })
            .distinctBy { it.url }.sortedByDescending { if (it.name.contains("FHD", true)) 3 else if (it.name.contains("HD", true)) 2 else 1 }
        if (available.isNotEmpty()) {
            val specific=Regex("(?i)\\s+(?:x|vs\\.?|versus)\\s+").containsMatchIn(entry.programme.title)
            val id = normalized(entry.programme.title) + ":" + entry.programme.start + if(specific)"" else ":"+entry.channelId
            val previous = result[id]
            result[id] = FootballMatch(id, entry.programme, (previous?.channels.orEmpty() + available).distinctBy { it.url })
        }
    }
    return result.values.sortedWith(compareBy<FootballMatch> { it.programme.start }.thenBy { it.programme.title })
}
fun guideChannelName(name: String) = normalized(name.replace(Regex("(?i)\\b(?:fhd|uhd|hd|sd|4k|h265|h264|alt)\\b|[\\[\\]]"), " "))
data class Guide(val programmes: Map<String, List<Programme>> = emptyMap(), val names: Map<String, String> = emptyMap(), val matches: List<GuideMatch> = emptyList()) {
    fun forChannel(channel: Channel): List<Programme> = if (channel.kind != MediaKind.LIVE) emptyList() else programmes[channel.tvgId]
        ?: programmes[names[normalized(channel.name)]] ?: programmes[names[guideChannelName(channel.name)]] ?: emptyList()
}

fun xmltvTime(value: String): Long = runCatching {
    val parts = value.trim().split(Regex("\\s+"))
    val digits = parts.first()
    val pattern = if (digits.length == 12) "yyyyMMddHHmm" else "yyyyMMddHHmmss"
    val zone = parts.getOrNull(1) ?: "+0000"
    SimpleDateFormat("$pattern Z", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        .parse("$digits $zone")?.time ?: 0L
}.getOrDefault(0L)

suspend fun loadGuide(source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis()): Guide {
    val coroutine = currentCoroutineContext()
    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "AuroraIPTV/1.4 Android")
    }
    try {
        if (connection.responseCode !in 200..299) return Guide()
        val input = connection.inputStream.buffered()
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val stream = if (gzip) GZIPInputStream(input) else input
        return stream.use { parseGuide(it, source, channelIds, now) { coroutine.ensureActive() } }
    } finally { connection.disconnect() }
}
fun parseGuide(stream: java.io.InputStream, source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}): Guide {
        return stream.use {
            val parser = Xml.newPullParser()
            parser.setInput(it, null)
            val programmes = HashMap<String, MutableList<Programme>>()
            val names = HashMap<String, String>()
            val acceptedIds=channelIds.toMutableSet()
            val matches = ArrayList<GuideMatch>()
            var id = ""; var start = 0L; var end = 0L; var title = ""; var image = ""
            var entry = ""; var accepted = false; var iterations = 0; var category = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (iterations++ % 512 == 0) checkCancelled()
                if (parser.eventType == XmlPullParser.DOCDECL) return@use Guide()
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> { entry = "channel"; id = parser.getAttributeValue(null, "id").orEmpty() }
                        "display-name" -> if (entry == "channel") {
                            val name=parser.nextText()
                            names[normalized(name)]=id;names[guideChannelName(name)]=id
                            if(normalized(name) in channelIds || guideChannelName(name) in channelIds)acceptedIds.add(id)
                        }
                        "programme" -> {
                            entry = "programme"
                            id = parser.getAttributeValue(null, "channel").orEmpty()
                            start = xmltvTime(parser.getAttributeValue(null, "start").orEmpty())
                            end = xmltvTime(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""; image = ""; category = ""
                            accepted = end > now
                        }
                        "title" -> if (entry == "programme" && accepted && title.isEmpty()) title = parser.nextText().take(300)
                        "category" -> if (entry == "programme" && accepted) category += " " + parser.nextText().take(100)
                        "sub-title" -> if (entry == "programme" && accepted) {
                            val subtitle = parser.nextText().take(200)
                            if (subtitle.isNotBlank()) title += " · " + subtitle
                        }
                        "icon" -> if (entry == "programme" && accepted) image = M3uParser.resolve(source, parser.getAttributeValue(null, "src").orEmpty())
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    if (parser.name == "programme" && accepted && title.isNotBlank() && end > start) {
                        val programme = Programme(title, start, end, image)
                        if (isFootballProgramme(title, category)) matches.add(GuideMatch(id, programme))
                        if ((channelIds.isEmpty() || id in acceptedIds) && start < now + 86400000L) {
                            val items = programmes.getOrPut(id) { ArrayList() }
                            items.add(programme)
                            items.sortBy { p -> p.start }
                            while (items.size > 2) items.removeAt(items.lastIndex)
                        }
                    }
                    if (parser.name == "programme" || parser.name == "channel") entry = ""
                }
                parser.next()
            }
            Guide(programmes, names, matches.distinctBy { it.channelId + ":" + it.programme.start + ":" + it.programme.title })
        }

}
