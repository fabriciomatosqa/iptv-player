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

/** Keep already verified broadcaster slots until they expire when an IPTV provider
 * returns a partial XMLTV file. Never carry over a slot contradicted by fresh EPG. */
internal fun mergeProviderGuide(previous: Guide, fresh: Guide, short: Guide, now: Long): Guide {
    val authoritative=fresh.matches+short.matches
    val validPrevious=previous.matches.filter {old ->
        old.programme.end>now && old.programme.start<now+86400000L &&
            authoritative.none {it.channelId==old.channelId && it.programme.start==old.programme.start} &&
            fresh.programmes[old.channelId].orEmpty().none {new ->
                new.start<old.programme.end && old.programme.start<new.end
            }
    }
    return Guide(
        (if(fresh.programmes.isEmpty())previous.programmes else fresh.programmes)+short.programmes,
        previous.names+fresh.names+short.names,
        (authoritative+validPrevious).distinctBy {
            it.channelId+":"+it.programme.start+":"+it.programme.title
        })
}

internal data class ProviderGuideStream(val id:String,val name:String,val epgId:String)
internal data class ProviderGuideRows(val programmes:List<Programme>,val counts:GuideCounts)
internal class GuideHttpException(val code:Int):java.io.IOException("Guide HTTP failure")

/** Provider TV guide fallback. Public scores never establish a broadcast channel. */
internal object XtreamSportsGuide {
    private val sportsBroadcaster = Regex("""(?i)\b(?:espn(?:\s*\d+)?|sportv(?:\s*\d+)?|sport\s*tv|premiere(?:\s*\d+)?|tnt\s*sports?|band\s*sports|dazn|caze\s*tv|nsports|fox\s*sports|bein\s*sports|eleven\s*sports|pfc)\b""")
    private val streamNumber = Regex("""^[0-9]{1,12}$""")
    private val base64Text = Regex("""^[A-Za-z0-9+/]+={0,2}$""")
    private val coreBroadcaster=Regex("(?i)espn|sportv|premiere|tnt|band.?sports")

    internal fun streamId(source: String, channel: Channel): String? = runCatching {
        val playlist = URL(source);val stream = URL(channel.url)
        val sameOrigin=playlist.host.equals(stream.host,true) && playlist.protocol==stream.protocol &&
            (if(playlist.port<0)playlist.defaultPort else playlist.port)==(if(stream.port<0)stream.defaultPort else stream.port)
        val credentials=playlist.query.orEmpty().split('&').mapNotNull {
            val key=it.substringBefore('=')
            if(key=="username" || key=="password")key to java.net.URLDecoder.decode(it.substringAfter('=',""),"UTF-8") else null
        }.toMap()
        val parts=stream.path.split('/').filter(String::isNotBlank).map {java.net.URLDecoder.decode(it.replace("+","%2B"),"UTF-8")}
        val account=if(parts.firstOrNull()=="live")parts.drop(1) else parts
        val matchingAccount=account.size==3 && !credentials["username"].isNullOrBlank() &&
            account[0]==credentials["username"] && account[1]==credentials["password"]
        if(!sameOrigin && !matchingAccount)null
        else parts.lastOrNull()?.substringBefore('.')?.takeIf {streamNumber.matches(it)}
    }.getOrNull()

    internal fun eligible(channel: Channel) = channel.kind==MediaKind.LIVE && !channel.adult &&
        (sportsBroadcaster.containsMatchIn(channel.name) ||
            ((normalized(channel.group).contains("esporte") || normalized(channel.group).contains("sport")) && channel.name.length in 3..80))

    /** Opaque delivery URLs need an ID supplied by the account's API, never guessed from a token. */
    internal fun mappedStream(channel:Channel,rows:List<ProviderGuideStream>):String? {
        val sameName=rows.filter {normalized(it.name)==normalized(channel.name)}
        val exact=sameName.filter {channel.tvgId.isBlank() || it.epgId.equals(channel.tvgId,true)}
        return exact.map {it.id}.distinct().singleOrNull()
    }
    suspend fun load(source:String,channels:List<Channel>,now:Long=System.currentTimeMillis(),
        readJson:(String)->JSONObject=::read):Guide=inspect(source,channels,now,readJson=readJson).guide

    suspend fun inspect(source:String,channels:List<Channel>,now:Long=System.currentTimeMillis(),
        readChannels:suspend (String)->List<ProviderGuideStream> = ProviderApi::liveGuideStreams,
        readJson:(String)->JSONObject=::read):GuideRead = withContext(Dispatchers.IO) {
        val endpoint=ProviderApi.endpoint(source,"get_short_epg")
            ?: return@withContext GuideRead(attempt=GuideAttempt(GuideSource.PROVIDER_API,GuideStatus.NOT_CONFIGURED))
        val representatives=channels.filter(::eligible).sortedBy {if(coreBroadcaster.containsMatchIn(it.name))0 else 1}
            .distinctBy {guideChannelName(it.name)}.take(48)
        val failures=java.util.Collections.synchronizedList(ArrayList<GuideStatus>())
        val apiRows=if(representatives.any {streamId(source,it)==null}) {
            try {readChannels(source)} catch(e:CancellationException) {throw e}
            catch(_:Exception) {failures.add(GuideStatus.NETWORK);emptyList()}
        } else emptyList()
        val ids=representatives.mapNotNull {c->(streamId(source,c) ?: mappedStream(c,apiRows))?.let {c to it}}
        if(ids.isEmpty())return@withContext GuideRead(attempt=GuideAttempt(GuideSource.PROVIDER_API,
            failures.firstOrNull() ?: GuideStatus.UNLINKED,GuideCounts(channels=representatives.size)))
        val results=ConcurrentHashMap<String,ProviderGuideRows>()
        val semaphore=Semaphore(4)
        val finished=withTimeoutOrNull(35000L) {
            coroutineScope {
                ids.map { (channel,id) -> async {semaphore.withPermit {
                    try {
                        val short=parseRows(readJson("$endpoint&stream_id=$id&limit=24"),now)
                        val table=if(short.counts.current==0 || short.programmes.isEmpty()) {
                            val address=ProviderApi.endpoint(source,"get_simple_data_table")!!
                            parseRows(readJson("$address&stream_id=$id"),now)
                        } else null
                        val rows=if(table!=null && (table.counts.current>short.counts.current || short.counts.programmes==0))table else short
                        results[guideChannelName(channel.name)]=rows
                    } catch(e:CancellationException) {throw e}
                    catch(e:GuideHttpException) {failures.add(if(e.code==401 || e.code==403)GuideStatus.AUTH else GuideStatus.HTTP)}
                    catch(_:org.json.JSONException) {failures.add(GuideStatus.INVALID)}
                    catch(_:Exception) {failures.add(GuideStatus.NETWORK)}
                }}}.awaitAll()
            };true
        } ?: false
        if(!finished)failures.add(GuideStatus.NETWORK)
        val programmes=LinkedHashMap<String,List<Programme>>();val names=LinkedHashMap<String,String>();val matches=ArrayList<GuideMatch>()
        for(channel in channels.filter(::eligible)) {
            val rows=results[guideChannelName(channel.name)] ?: continue
            // The API verified this broadcaster; HD/FHD variants share its exact identity.
            val id=channel.tvgId.ifBlank {"aurora-short:"+guideChannelName(channel.name)}
            names[normalized(channel.name)]=id;names[guideChannelName(channel.name)]=id
            programmes[id]=rows.programmes
            rows.programmes.filter {footballProgrammeCandidate(it) || sportsProgrammeCandidate(it)}.forEach {matches.add(GuideMatch(id,it))}
        }
        val guide=Guide(programmes,names,matches.distinctBy {it.channelId+":"+it.programme.start+":"+it.programme.title})
        val counts=GuideCounts(results.size,results.values.sumOf {it.counts.programmes},
            results.values.sumOf {it.counts.current},results.values.sumOf {it.counts.candidates})
        val linked=footballMatches(guide,channels,now).count {it.broadcastEnd>now}
        val status=if(counts.programmes==0 && failures.isNotEmpty())failures.first() else guideStatus(counts,linked)
        GuideRead(guide,GuideAttempt(GuideSource.PROVIDER_API,status,counts))
    }

    internal fun parse(json:JSONObject,now:Long)=parseRows(json,now).programmes.filter {footballProgrammeCandidate(it) || sportsProgrammeCandidate(it)}
    internal fun parseRows(json:JSONObject,now:Long):ProviderGuideRows {
        val rows=json.optJSONArray("epg_listings") ?: throw org.json.JSONException("Missing guide rows")
        var current=0
        val result=buildList {
            // Detailed API tables are chronological: do not discard future games after row 24.
            for(i in 0 until minOf(rows.length(),2048)) {
                val item=rows.optJSONObject(i) ?: continue
                val start=timestamp(item,"start_timestamp","start_tz","start")
                val end=timestamp(item,"stop_timestamp","end_timestamp","end")
                if(start==0L || end<=start || start>now+7*86400000L)continue
                if(end>now)current++
                if(end<now-6*3600000L)continue
                val title=decode(item.optString("title")).trim().take(1000)
                if(title.isBlank())continue
                val description=decode(item.optString("description")).trim().take(4000)
                val category=item.optString("category").take(200)
                add(Programme(title,start,end,category=category,broadcast=programmeBroadcastHint(title,category),
                    metadata=FootballMetadata(originalTitle=title,description=description)))
            }
        }.distinctBy {it.start to it.title}
        return ProviderGuideRows(result,GuideCounts(programmes=rows.length(),current=current,
            candidates=result.count {it.end>now && (footballProgrammeCandidate(it) || sportsProgrammeCandidate(it))}))
    }
    private fun timestamp(item:JSONObject,vararg keys:String):Long {
        for(key in keys) {
            val raw=item.optString(key).trim();if(raw.isBlank())continue
            val numeric=raw.toLongOrNull()
            if(numeric!=null && numeric>1_000_000_000L)return if(numeric>100_000_000_000L)numeric else numeric*1000
            for(pattern in listOf("yyyy-MM-dd HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd HH:mm:ss")) {
                val date=runCatching {SimpleDateFormat(pattern,Locale.ROOT).apply {
                    isLenient=false;timeZone=TimeZone.getDefault()
                }.parse(raw)?.time}.getOrNull()
                if(date!=null)return date
            }
        };return 0L
    }
    private fun decode(raw:String):String {
        if(raw.length<8 || raw.length>8192 || raw.length%4!=0 || !base64Text.matches(raw))return raw
        return runCatching {
            val result=String(Base64.decode(raw,Base64.DEFAULT),Charsets.UTF_8)
            if(result.isBlank() || result.any {it.isISOControl() && it!='\n' && it!='\t'} || '\uFFFD' in result)raw else result
        }.getOrDefault(raw)
    }
    private fun read(address:String):JSONObject {
        val connection=(URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout=5000;readTimeout=6000
            setRequestProperty("User-Agent","AuroraIPTV/1.12 Android")
            setRequestProperty("Accept","application/json");setRequestProperty("Accept-Encoding","gzip")
        }
        try {
            val code=connection.responseCode
            if(code !in 200..299)throw GuideHttpException(code)
            val input=connection.inputStream.buffered();input.mark(2)
            val compressed=input.read()==0x1f && input.read()==0x8b;input.reset()
            val stream=if(compressed)GZIPInputStream(input) else input
            stream.use { source ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val size=source.read(buffer);if(size<0)break
                    check(output.size()+size<=4*1024*1024);output.write(buffer,0,size)}
                return JSONObject(output.toString("UTF-8"))
            }
        } finally {connection.disconnect()}
    }
}
