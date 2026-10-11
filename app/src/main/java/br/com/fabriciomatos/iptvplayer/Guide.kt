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
data class Guide(val programmes: Map<String, List<Programme>> = emptyMap(), val names: Map<String, String> = emptyMap()) {
    fun forChannel(channel: Channel): List<Programme> = programmes[channel.tvgId]
        ?: programmes[names[normalized(channel.name)]] ?: emptyList()
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
        setRequestProperty("User-Agent", "AuroraIPTV/1.1 Android")
    }
    try {
        if (connection.responseCode !in 200..299) return Guide()
        val input = connection.inputStream.buffered()
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val stream = if (gzip) GZIPInputStream(input) else input
        return stream.use {
            val parser = Xml.newPullParser()
            parser.setInput(it, null)
            val programmes = HashMap<String, MutableList<Programme>>()
            val names = HashMap<String, String>()
            var id = ""; var start = 0L; var end = 0L; var title = ""; var image = ""
            var entry = ""; var accepted = false; var iterations = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (iterations++ % 512 == 0) coroutine.ensureActive()
                if (parser.eventType == XmlPullParser.DOCDECL) return@use Guide()
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> { entry = "channel"; id = parser.getAttributeValue(null, "id").orEmpty() }
                        "display-name" -> if (entry == "channel") names[normalized(parser.nextText())] = id
                        "programme" -> {
                            entry = "programme"
                            id = parser.getAttributeValue(null, "channel").orEmpty()
                            start = xmltvTime(parser.getAttributeValue(null, "start").orEmpty())
                            end = xmltvTime(parser.getAttributeValue(null, "stop").orEmpty())
                            title = ""; image = ""
                            accepted = (channelIds.isEmpty() || id in channelIds) && end > now && start < now + 86400000L
                        }
                        "title" -> if (entry == "programme" && accepted && title.isEmpty()) title = parser.nextText().take(300)
                        "icon" -> if (entry == "programme" && accepted) image = M3uParser.resolve(source, parser.getAttributeValue(null, "src").orEmpty())
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    if (parser.name == "programme" && accepted && title.isNotBlank() && end > start) {
                        programmes.getOrPut(id) { ArrayList() }.add(Programme(title, start, end, image))
                    }
                    if (parser.name == "programme" || parser.name == "channel") entry = ""
                }
                parser.next()
            }
            Guide(programmes.mapValues { (_, items) -> items.sortedBy { p -> p.start }.take(16) }, names)
        }
    } finally { connection.disconnect() }
}
