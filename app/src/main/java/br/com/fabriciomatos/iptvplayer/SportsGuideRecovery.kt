package br.com.fabriciomatos.iptvplayer

internal data class SportsGuideRecovery(val guide:Guide,val diagnostics:GuideDiagnostics,val received:Boolean)

/**
 * Alternate broadcaster schedules may fill only unused timeslots for real
 * playlist channels. They must never replace an overlapping provider programme,
 * even when that programme is not a football match.
 *
 * For channels without tvg-id, keep the provider's already-resolved guide ID,
 * rather than replacing the name alias and silently hiding its existing slots.
 */
internal fun unoccupiedBrazilianGuide(existing: Guide, alternate: Guide, channels: List<Channel>): Guide {
    val ids=(alternate.programmes.keys+alternate.matches.map { it.channelId }).toSet()
    val linked=channels.filter { it.kind==MediaKind.LIVE && !it.adult }
        .mapNotNull { channel ->
            alternate.channelId(channel)?.takeIf { it in ids }?.let { it to channel }
        }.groupBy({ it.first }, { it.second })
    val target=ids.associateWith { id ->
        val existingIds=linked[id].orEmpty().mapNotNull(existing::channelId).distinct()
        if(existingIds.size>1) null else existingIds.singleOrNull() ?: id
    }
    // Never combine two different public broadcasters into one provider ID.
    val collisions=target.values.filterNotNull().groupingBy { it }.eachCount()
        .filterValues { it>1 }.keys
    val occupied=existing.matches.groupBy({ it.channelId }, { it.programme })
    fun accepts(id: String, programme: Programme): Boolean {
        val mapped=target[id] ?: return false
        if(mapped in collisions) return false
        return (existing.programmes[mapped].orEmpty()+occupied[mapped].orEmpty()).none {
            it.start<programme.end && programme.start<it.end
        }
    }
    val programmes=linkedMapOf<String,List<Programme>>()
    alternate.programmes.forEach { (id, rows) ->
        val mapped=target[id] ?: return@forEach
        val accepted=rows.filter { accepts(id,it) }
        if(accepted.isNotEmpty()) programmes[mapped]=accepted
    }
    val names=alternate.names.mapNotNull { (name,id) ->
        target[id]?.takeUnless { it in collisions }?.let { name to it }
    }.toMap().filterKeys { it !in existing.names }
    val matches=alternate.matches.mapNotNull { entry ->
        if(!accepts(entry.channelId,entry.programme))null
        else target[entry.channelId]?.let { GuideMatch(it,entry.programme) }
    }
    return Guide(programmes,names,matches)
}

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
    // A valid provider may have current news/movies but no linked sports fixtures.
    // Recover only uncovered broadcaster slots, without displacing provider schedules.
    if(alternate && footballMatches(guide,channels,now).none { it.broadcastEnd>now } &&
        BrazilBroadcastGuide.eligible(channels)) {
        val other=brazil(channels,now);attempts.add(other.attempt)
        val available=unoccupiedBrazilianGuide(guide,other.guide,channels)
        guide=mergeProviderGuide(guide,Guide(),available,now)
    }
    val linked=footballMatches(guide,channels,now).count {it.broadcastEnd>now}
    val received=attempts.any {it.counts.current>0 && it.status !in listOf(GuideStatus.NETWORK,GuideStatus.INVALID,GuideStatus.AUTH,GuideStatus.HTTP)}
    return SportsGuideRecovery(guide,GuideDiagnostics(attempts,linked,now,guide.matches.any {it.programme.end>now && it.programme.metadata.guideSource=="epgshare-br"}),received)
}
