package br.com.fabriciomatos.iptvplayer

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Provider TV guide fallback. Public scores never establish a broadcast channel. */
internal object XtreamSportsGuide {
    private val sportsBroadcaster = Regex("""(?i)\b(?:espn(?:\s*\d+)?|sportv(?:\s*\d+)?|sport\s*tv|premiere(?:\s*\d+)?|tnt\s*sports?|band\s*sports|dazn|caze\s*tv|nsports|fox\s*sports|bein\s*sports|eleven\s*sports|pfc)\b""")
    private val streamNumber = Regex("""^[0-9]{1,12}$""")
    private val base64Text = Regex("""^[A-Za-z0-9+/]+={0,2}$""")
    private val queryLimit = 48
    private val parallelRequests = Semaphore(4)

    internal fun streamId(source: String, channel: Channel): String? = runCatching {
        val playlist = URL(source)
        val stream = URL(channel.url)
        if(!playlist.host.equals(stream.host, true) ||
            playlist.port != stream.port || playlist.protocol != stream.protocol) return null
        val candidate=stream.path.substringAfterLast('/').substringBefore('.')
        candidate.takeIf {streamNumber.matches(it)}
    }.getOrNull()

    private fun eligible(channel: Channel) =
        channel.kind==MediaKind.LIVE && !channel.adult &&
            (sportsBroadcaster.containsMatchIn(channel.name) ||
                ((normalized(channel.group).contains("esporte") ||
                    normalized(channel.group).contains("sport")) && channel.name.length in 3..80))

    /** Query one broadcaster schedule and share only with its exact HD/FHD variants. */
    suspend fun load(source: String, channels: List<Channel>, now: Long = System.currentTimeMillis(),
        readJson: (String) -> JSONObject = ::read): Guide = withContext(Dispatchers.IO) {
        val endpoint = ProviderApi.endpoint(source,"get_short_epg") ?: return@withContext Guide()
        val representatives=channels.asSequence().filter(::eligible)
            .mapNotNull {channel->streamId(source,channel)?.let {channel to it}}
            .distinctBy {guideChannelName(it.first.name)}
            .take(queryLimit).toList()
        if(representatives.isEmpty())return@withContext Guide()
        val byBroadcaster=ConcurrentHashMap<String,List<Programme>>()
        withTimeoutOrNull(30000L) {
            coroutineScope {
                representatives.map { (channel,id) -> async {
                    parallelRequests.withPermit {
                        try {
                            val listing=readJson("$endpoint&stream_id=$id&limit=12")
                            val programmes=parse(listing,now)
                            if(programmes.isNotEmpty())byBroadcaster[guideChannelName(channel.name)]=programmes
                        } catch(e: CancellationException) {throw e}
                        catch(_: Exception) { /* Other broadcaster queries still run. */ }
                    }
                }}.awaitAll()
            }
        }
        if(byBroadcaster.isEmpty())return@withContext Guide()
        val names=LinkedHashMap<String,String>()
        val matches=ArrayList<GuideMatch>()
        val programmes=LinkedHashMap<String,List<Programme>>()
        for(channel in channels) {
            val content=byBroadcaster[guideChannelName(channel.name)] ?: continue
            if(!eligible(channel) || streamId(source,channel)==null)continue
            val id=channel.tvgId.ifBlank { "aurora-short:"+guideChannelName(channel.name) }
            names[normalized(channel.name)]=id
            names[guideChannelName(channel.name)]=id
            programmes[id]=content
            content.forEach { matches.add(GuideMatch(id,it)) }
        }
        Guide(programmes,names,matches.distinctBy {
            it.channelId+":"+it.programme.start+":"+it.programme.title
        })
    }

    internal fun parse(json: JSONObject, now: Long): List<Programme> {
        val rows=json.optJSONArray("epg_listings") ?: return emptyList()
        return buildList {
            for(i in 0 until minOf(rows.length(),24)) {
                val item=rows.optJSONObject(i) ?: continue
                val start=timestamp(item,"start_timestamp","start_tz","start")
                val end=timestamp(item,"stop_timestamp","end_timestamp","end")
                if(start==0L || end<=start || end<now-6*3600000L || start>now+3*86400000L)continue
                val title=decode(item.optString("title")).trim().take(500)
                if(title.isBlank())continue
                val description=decode(item.optString("description")).trim().take(1500)
                val programme=Programme(title,start,end,metadata=FootballMetadata(
                    originalTitle=title,description=description))
                if(hasFootballMatchup(title) || sportsProgrammeCandidate(programme)) add(programme)
            }
        }.distinctBy {it.start to it.title}
    }

    private fun timestamp(item: JSONObject,vararg keys: String): Long {
        for(key in keys) {
            val raw=item.optString(key).trim()
            if(raw.isBlank())continue
            val numeric=raw.toLongOrNull()
            if(numeric!=null && numeric>1_000_000_000L) {
                return if(numeric>100_000_000_000L)numeric else numeric*1000
            }
            val date=runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).apply {
                    isLenient=false;timeZone=TimeZone.getDefault()
                }.parse(raw)?.time
            }.getOrNull()
            if(date!=null)return date
        }
        return 0L
    }

    private fun decode(raw: String): String {
        if(raw.length<8 || raw.length>8192 || raw.length%4!=0 || !base64Text.matches(raw))return raw
        return runCatching {
            val result=String(Base64.decode(raw,Base64.DEFAULT),Charsets.UTF_8)
            if(result.isBlank() || result.any {it.isISOControl() && it!='\n' && it!='\t'} ||
                '\uFFFD' in result)raw else result
        }.getOrDefault(raw)
    }

    private fun read(address: String): JSONObject {
        val connection=(URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout=6000;readTimeout=7000
            setRequestProperty("User-Agent","AuroraIPTV/1.12 Android")
            setRequestProperty("Accept","application/json")
            setRequestProperty("Accept-Encoding","gzip")
        }
        try {
            if(connection.responseCode !in 200..299)return JSONObject()
            val stream=if(connection.contentEncoding.equals("gzip",true))
                GZIPInputStream(connection.inputStream) else connection.inputStream
            stream.use {input ->
                val buffer=ByteArray(8192)
                val output=ByteArrayOutputStream()
                while(true) {
                    val size=input.read(buffer)
                    if(size<0)break
                    check(output.size()+size<=1024*1024)
                    output.write(buffer,0,size)
                }
                return JSONObject(output.toString("UTF-8"))
            }
        } finally {connection.disconnect()}
    }
}
