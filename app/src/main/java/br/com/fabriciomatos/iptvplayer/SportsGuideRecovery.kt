package br.com.fabriciomatos.iptvplayer

internal data class SportsGuideRecovery(val guide:Guide,val diagnostics:GuideDiagnostics,val received:Boolean)

/** Injectable sources exercise the production recovery order without requiring credentials in CI. */
internal suspend fun recoverSportsGuide(playlist:String,address:String,channels:List<Channel>,saved:Guide,
    alternate:Boolean,now:Long=System.currentTimeMillis(),
    xml:suspend (String,Set<String>,Long)->GuideRead = {u,ids,t->readGuide(u,ids,t)},
    api:suspend (String,List<Channel>,Long)->GuideRead = {u,c,t->XtreamSportsGuide.inspect(u,c,t)},
    brazil:suspend (List<Channel>,Long)->GuideRead = BrazilBroadcastGuide::load):SportsGuideRecovery {
    val ids=channels.flatMap {listOf(it.tvgId,normalized(it.name),guideChannelName(it.name))}.filter(String::isNotBlank).toSet()
    val fresh=xml(address,ids,now)
    val games=footballMatches(fresh.guide,channels,now).count {it.broadcastEnd>now}
    val primary=fresh.attempt.let { a ->
        if(a.status in listOf(GuideStatus.EMPTY,GuideStatus.STALE,GuideStatus.NO_GAMES,GuideStatus.UNLINKED,GuideStatus.READY))
            a.copy(status=guideStatus(a.counts,games)) else a
    }
    val attempts=arrayListOf(primary)
    val short=if(games==0)api(playlist,channels,now).also {attempts.add(it.attempt)} else null
    var guide=mergeProviderGuide(if(alternate)saved else saved.withoutAlternate(),fresh.guide,short?.guide ?: Guide(),now)
    // No public fixtures and no conflicting alternate schedule when the provider has current data.
    if(alternate && primary.counts.current==0 && (short?.attempt?.counts?.current ?: 0)==0 && BrazilBroadcastGuide.eligible(channels)) {
        val other=brazil(channels,now);attempts.add(other.attempt)
        guide=mergeProviderGuide(guide,other.guide,Guide(),now)
    }
    val linked=footballMatches(guide,channels,now).count {it.broadcastEnd>now}
    val received=attempts.any {it.counts.current>0 && it.status !in listOf(GuideStatus.NETWORK,GuideStatus.INVALID,GuideStatus.AUTH,GuideStatus.HTTP)}
    return SportsGuideRecovery(guide,GuideDiagnostics(attempts,linked,now),received)
}
