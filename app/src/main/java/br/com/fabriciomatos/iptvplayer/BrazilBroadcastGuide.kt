package br.com.fabriciomatos.iptvplayer

import java.util.Locale

/** A broadcaster EPG, not a public fixtures calendar. No playlist URL or account data is sent. */
internal object BrazilBroadcastGuide {
    const val ADDRESS="https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz"
    private val networks=setOf("espn","espn2","espn3","espn4","espn5","espn6",
        "sportv","sportv2","sportv3","bandsports","tntsports","premiere","premiere2",
        "premiere3","premiere4","premiere5","premiere6","premiere7","premiere8","premiereclubes")

    /** Provider-assigned Brazilian EPG ID remains the first and most authoritative key. */
    fun network(id:String):String? {
        val value=id.trim().lowercase(Locale.ROOT)
        if(!value.endsWith(".br"))return null
        val key=value.removeSuffix(".br").replace(Regex("[. _-]"),"")
        return key.takeIf {it in networks}
    }

    /**
     * The 1.12.18 TV guide accepted unambiguous display-name aliases for channels without
     * usable EPG IDs. Keep that safety valve for lists with blank/opaque IDs: exact well-known
     * broadcaster names only, never a fuzzy partial match or an arbitrary sports group.
     */
    private fun explicitBroadcasterName(name:String):String? {
        var label=normalized(name)
        label=label.replace(Regex("^(br|bra|brasil|brazil) "), "")
        label=label.replace(Regex("^(tv|canais|canal) "), "")
        label=label.replace(Regex("(?: (?:full hd|fhd|uhd|4k|hd|sd|h265|h264|hevc|1080p|720p|480p|alt))+$"), "")
        val key=label.replace(" ","")
        return key.takeIf {it in networks}
    }

    internal fun broadcaster(channel:Channel):String? {
        if(channel.kind!=MediaKind.LIVE || channel.adult)return null
        network(channel.tvgId)?.let {return it}
        val id=channel.tvgId.trim().lowercase(Locale.ROOT)
        // An explicit unknown/foreign XMLTV authority is not overridden by name guessing.
        if(id.endsWith(".br") || Regex("""\.[a-z]{2,3}$""").containsMatchIn(id))return null
        return explicitBroadcasterName(channel.name)
    }

    fun eligible(channels:List<Channel>)=channels.any {broadcaster(it)!=null}

    fun link(public:Guide,channels:List<Channel>):Guide {
        val ids=(public.programmes.keys+public.matches.map {it.channelId}).distinct()
            .mapNotNull {id->network(id)?.let {it to id}}.groupBy({it.first},{it.second})
        val byChannel=public.matches.groupBy {it.channelId}
        val shows=linkedMapOf<String,List<Programme>>()
        val names=linkedMapOf<String,String>()
        val matches=ArrayList<GuideMatch>()
        fun mark(p:Programme)=p.copy(metadata=p.metadata.copy(guideSource="epgshare-br"))
        channels.forEach {channel ->
            val key=broadcaster(channel) ?: return@forEach
            val publicId=ids[key]?.distinct()?.singleOrNull() ?: return@forEach
            val channelId=channel.tvgId.ifBlank {"aurora-epgshare-br:"+key}
            public.programmes[publicId]?.let {shows[channelId]=it.map(::mark)}
            if(channel.tvgId.isBlank()) {
                // Guide.channelId() uses these exact, normalized aliases for blank tvg-id.
                names[normalized(channel.name)]=channelId
                names[guideChannelName(channel.name)]=channelId
            }
            byChannel[publicId].orEmpty().forEach {
                matches.add(GuideMatch(channelId,mark(it.programme)))
            }
        }
        return Guide(shows,names,matches.distinctBy {
            it.channelId+":"+it.programme.start+":"+it.programme.title
        })
    }

    suspend fun load(channels:List<Channel>,now:Long):GuideRead {
        if(!eligible(channels))return GuideRead(attempt=GuideAttempt(GuideSource.EPGSHARE_BR,GuideStatus.NOT_CONFIGURED))
        val read=readGuide(ADDRESS,emptySet(),now,GuideSource.EPGSHARE_BR)
        val linked=link(read.guide,channels)
        val games=linkedSportsCount(linked,channels,now)
        val status=if(read.attempt.status in listOf(GuideStatus.NETWORK,GuideStatus.AUTH,GuideStatus.HTTP,GuideStatus.INVALID))read.attempt.status
            else guideStatus(read.attempt.counts,games)
        return GuideRead(linked,read.attempt.copy(status=status))
    }
}
