package br.com.fabriciomatos.iptvplayer

import java.util.Locale

/** A broadcaster schedule, never a public fixture/score calendar. No playlist URL is sent. */
internal object BrazilBroadcastGuide {
    const val ADDRESS="https://epgshare01.online/epgshare01/epg_ripper_BR1.xml.gz"
    private val networks=setOf("espn","espn2","espn3","espn4","espn5","espn6",
        "sportv","sportv2","sportv3","bandsports","tntsports","premiere","premiere2",
        "premiere3","premiere4","premiere5","premiere6","premiere7","premiere8","premiereclubes")
    /** Require a Brazilian XMLTV ID, not a similar-looking channel name or playlist group. */
    fun network(id:String):String? {
        val value=id.trim().lowercase(Locale.ROOT)
        if(!value.endsWith(".br"))return null
        val key=value.removeSuffix(".br").replace(Regex("[. _-]"),"")
        return key.takeIf {it in networks}
    }
    fun eligible(channels:List<Channel>)=channels.any {it.kind==MediaKind.LIVE && !it.adult && network(it.tvgId)!=null}
    fun link(public:Guide,channels:List<Channel>):Guide {
        val ids=(public.programmes.keys+public.matches.map {it.channelId}).distinct()
            .mapNotNull {id->network(id)?.let {it to id}}.groupBy({it.first},{it.second})
        val shows=linkedMapOf<String,List<Programme>>();val matches=ArrayList<GuideMatch>()
        fun mark(p:Programme)=p.copy(metadata=p.metadata.copy(guideSource="epgshare-br"))
        channels.filter {it.kind==MediaKind.LIVE && !it.adult}.forEach {channel ->
            val key=network(channel.tvgId) ?: return@forEach
            val id=ids[key]?.distinct()?.singleOrNull() ?: return@forEach
            public.programmes[id]?.let {shows[channel.tvgId]=it.map(::mark)}
            public.matches.filter {it.channelId==id}.forEach {matches.add(GuideMatch(channel.tvgId,mark(it.programme)))}
        }
        return Guide(shows,matches=matches.distinctBy {it.channelId+":"+it.programme.start+":"+it.programme.title})
    }
    suspend fun load(channels:List<Channel>,now:Long):GuideRead {
        if(!eligible(channels))return GuideRead(attempt=GuideAttempt(GuideSource.EPGSHARE_BR,GuideStatus.NOT_CONFIGURED))
        val read=readGuide(ADDRESS,emptySet(),now,GuideSource.EPGSHARE_BR)
        val linked=link(read.guide,channels)
        val games=footballMatches(linked,channels,now).count {it.broadcastEnd>now}
        val status=if(read.attempt.status in listOf(GuideStatus.NETWORK,GuideStatus.AUTH,GuideStatus.HTTP,GuideStatus.INVALID))read.attempt.status
            else guideStatus(read.attempt.counts,games)
        return GuideRead(linked,read.attempt.copy(status=status))
    }
}
