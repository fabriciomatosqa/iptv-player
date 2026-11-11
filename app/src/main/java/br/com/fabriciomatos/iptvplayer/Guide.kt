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

suspend fun loadGuide(source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis()): Guide =
    readGuide(source,channelIds,now).guide

/** HTTP 200 is insufficient: count programmes before filtering by time or channel. */
internal suspend fun readGuide(source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis(),
    origin: GuideSource = GuideSource.XMLTV): GuideRead {
    if(source.isBlank())return GuideRead(attempt=GuideAttempt(origin,GuideStatus.NOT_CONFIGURED))
    val coroutine = currentCoroutineContext()
    var counts=GuideCounts()
    var http=0
    try {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AuroraIPTV/1.12 Android")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            http=connection.responseCode
            if(http !in 200..299)return GuideRead(attempt=GuideAttempt(origin,
                if(http==401 || http==403)GuideStatus.AUTH else GuideStatus.HTTP,http=http))
            val input = connection.inputStream.buffered()
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val stream = if (gzip) GZIPInputStream(input) else input
            val bounded=object: java.io.FilterInputStream(stream) {
                var bytes=0L
                fun count(n:Int):Int {if(n>0)bytes+=n;check(bytes<=64L*1024*1024);return n}
                override fun read():Int {val v=super.read();if(v>=0)count(1);return v}
                override fun read(b:ByteArray,off:Int,len:Int):Int=count(`in`.read(b,off,len))
            }
            val guide = bounded.use { parseGuide(it,source,channelIds,now,
                {coroutine.ensureActive()},{counts=it}) }
            return GuideRead(guide,GuideAttempt(origin,guideStatus(counts,0),counts,http))
        } finally { connection.disconnect() }
    } catch(e:kotlinx.coroutines.CancellationException) {throw e}
    catch(_:java.net.SocketTimeoutException) {return GuideRead(attempt=GuideAttempt(origin,GuideStatus.NETWORK,counts,http))}
    catch(_:org.xmlpull.v1.XmlPullParserException) {return GuideRead(attempt=GuideAttempt(origin,GuideStatus.INVALID,counts,http))}
    catch(_:IllegalStateException) {return GuideRead(attempt=GuideAttempt(origin,GuideStatus.INVALID,counts,http))}
    catch(_:Exception) {return GuideRead(attempt=GuideAttempt(origin,GuideStatus.NETWORK,counts,http))}
}
fun parseGuide(stream: java.io.InputStream, source: String, channelIds: Set<String>, now: Long = System.currentTimeMillis(), checkCancelled: () -> Unit = {}, inspect: (GuideCounts) -> Unit = {}): Guide {
        return stream.use {
            val parser = Xml.newPullParser()
            parser.setInput(it, null)
            val programmes = HashMap<String, MutableList<Programme>>()
            val names = HashMap<String, String>()
            val requestedIds=channelIds.map {it.trim().lowercase(Locale.ROOT)}.toSet()
            val acceptedKeys=requestedIds.toMutableSet()
            val matches = ArrayList<GuideMatch>()
            var id = ""; var start = 0L; var end = 0L; var title = ""; var image = ""
            var metadata = FootballMetadata()
            var totalChannels=0;var totalProgrammes=0;var currentProgrammes=0;var validProgrammes=0;var rootSeen=false
            var entry = ""; var accepted = false; var iterations = 0; var category = "";var broadcast=BroadcastHint.UNKNOWN
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (iterations++ % 512 == 0) checkCancelled()
                if (parser.eventType == XmlPullParser.DOCDECL) error("Unsupported guide document")
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if(!rootSeen) {check(parser.name=="tv");rootSeen=true}
                    when (parser.name) {
                        "channel" -> { totalChannels++;entry = "channel"; id = parser.getAttributeValue(null, "id").orEmpty() }
                        "display-name" -> if (entry == "channel") {
                            val name=parser.nextText()
                            listOf(normalized(name),guideChannelName(name)).distinct().forEach { alias ->
                                names[alias]=if(names.containsKey(alias) && names[alias]!=id) "" else id
                            }
                            if(normalized(name) in requestedIds || guideChannelName(name) in requestedIds) {
                                acceptedKeys.add(id.trim().lowercase(Locale.ROOT))
                            }
                        }
                        "programme" -> {
                            entry = "programme";totalProgrammes++
                            id = parser.getAttributeValue(null, "channel").orEmpty()
                            start = xmltvTime(parser.getAttributeValue(null, "start").orEmpty())
                            end = xmltvTime(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""; image = ""; category = "";broadcast=BroadcastHint.UNKNOWN;metadata=FootballMetadata()
                            // Retain source metadata without deriving replay status from previous slots.
                            // Keep ordinary channel guide cards filtered below as before.
                            if(start>0 && end>start)validProgrammes++
                            if(start>0 && end>start && end>now && start<now+7*86400000L)currentProgrammes++
                            accepted = start>0 && end>start && end > now-36*3600000L && start<now+7*86400000L
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
                        val subscribed=channelIds.isEmpty() || id.trim().lowercase(Locale.ROOT) in acceptedKeys
                        if (subscribed && (footballProgrammeCandidate(programme) || sportsProgrammeCandidate(programme))) matches.add(GuideMatch(id, programme))
                        if (end>now && subscribed && start < now + 86400000L) {
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
            check(rootSeen)
            inspect(GuideCounts(totalChannels,totalProgrammes,currentProgrammes,matches.count {it.programme.end>now},validProgrammes))
            Guide(programmes, names, matches.distinctBy { it.channelId + ":" + it.programme.start + ":" + it.programme.title })
        }

}
