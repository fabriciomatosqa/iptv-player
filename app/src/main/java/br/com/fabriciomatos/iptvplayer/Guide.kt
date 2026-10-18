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

suspend fun loadGuide(source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis()): Guide {
    val coroutine = currentCoroutineContext()
    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "AuroraIPTV/1.7 Android")
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
            var metadata = FootballMetadata()
            var entry = ""; var accepted = false; var iterations = 0; var category = "";var broadcast=BroadcastHint.UNKNOWN
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
                            title = ""; image = ""; category = "";broadcast=BroadcastHint.UNKNOWN;metadata=FootballMetadata()
                            // Retain recent football broadcasts to identify a later airing as a replay.
                            // Keep ordinary channel guide cards filtered below as before.
                            accepted = end > now-36*3600000L
                        }
                        "title" -> if (entry == "programme" && accepted && metadata.originalTitle.isEmpty()) {
                            val original = parser.nextText().take(1000)
                            metadata = metadata.copy(originalTitle=original)
                            title = listOf(original,metadata.subtitle).filter(String::isNotBlank).joinToString(" · ")
                        }
                        "desc" -> if(entry == "programme" && accepted) metadata=metadata.copy(description=parser.nextText().take(4000))
                        "competition", "league" -> if(entry == "programme" && accepted) metadata=metadata.copy(competition=parser.nextText().take(300))
                        "gender" -> if(entry == "programme" && accepted) metadata=metadata.copy(gender=parser.nextText().take(50))
                        "country" -> if(entry == "programme" && accepted) metadata=metadata.copy(country=parser.nextText().take(100))
                        "division" -> if(entry == "programme" && accepted) metadata=metadata.copy(division=parser.nextText().take(100))
                        "sport" -> if(entry == "programme" && accepted) metadata=metadata.copy(sport=parser.nextText().take(100))
                        "category" -> if (entry == "programme" && accepted) category += " " + parser.nextText().take(100)
                        "sub-title" -> if (entry == "programme" && accepted) {
                            val subtitle = parser.nextText().take(200)
                            metadata=metadata.copy(subtitle=subtitle)
                            title = listOf(metadata.originalTitle,subtitle).filter(String::isNotBlank).joinToString(" · ")
                        }
                        "icon" -> if (entry == "programme" && accepted) image = M3uParser.resolve(source, parser.getAttributeValue(null, "src").orEmpty())
                        "live" -> if(entry=="programme" && accepted && broadcast!=BroadcastHint.REPLAY)broadcast=BroadcastHint.LIVE
                        "previously-shown" -> if(entry=="programme" && accepted)broadcast=BroadcastHint.REPLAY
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    if (parser.name == "programme" && accepted && title.isNotBlank() && end > start) {
                        val hint=programmeBroadcastHint(title,category)
                        val programme = Programme(title, start, end, image, category.trim(),if(hint==BroadcastHint.REPLAY || broadcast==BroadcastHint.UNKNOWN)hint else broadcast,metadata)
                        if (isSportsProgramme(programme)) matches.add(GuideMatch(id, programme))
                        if (end>now && (channelIds.isEmpty() || id in acceptedIds) && start < now + 86400000L) {
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
