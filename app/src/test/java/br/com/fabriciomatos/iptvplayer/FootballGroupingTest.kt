package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class FootballGroupingTest {
    private fun channel(name:String,id:String)=Channel(name,"https://example.test/live/$id","Sports",tvgId=id)
    private fun entry(id:String,start:Long,hint:BroadcastHint=BroadcastHint.UNKNOWN) =
        GuideMatch(id,Programme("Fenerbahçe x Alanyaspor - Campeonato Turco de Futebol",start,start+2*3600000L,broadcast=hint))
    @Test fun sameFixtureWithDifferentEpgMinutesSharesOneCardAndAllStreams() {
        val now=1800000000000L
        val channels=listOf(channel("ESPN 2 FHD","e"),channel("ESPN 2 HD","f"),channel("SPORTV HD","s"),
            channel("SPORTV SD","t"))
        val items=listOf(entry("e",now),entry("f",now+5*60000L),entry("s",now+15*60000L),entry("t",now+12*60000L))
        val matches=footballMatches(Guide(matches=items),channels,now)
        assertEquals(1,matches.size)
        assertEquals(4,matches.single().channels.size)
        assertEquals(listOf("ESPN 2 FHD","SPORTV HD"),footballFeaturedChannels(matches.single().channels).map {it.name})
    }
    @Test fun distinctFixturesAndReplaysRemainSeparate() {
        val now=1800000000000L
        val channels=listOf(channel("ESPN 2 FHD","e"),channel("SPORTV HD","s"),channel("ESPN 2 HD","f"))
        val matches=footballMatches(Guide(matches=listOf(entry("e",now),entry("s",now+95*60000L),
            entry("f",now+4*60000L,BroadcastHint.REPLAY))),channels,now)
        assertEquals(3,matches.size)
    }
    @Test fun reversedTeamsAndDuplicateUrlsAreHandledSafely() {
        val now=1800000000000L
        val reversed=GuideMatch("s",Programme("Alanyaspor x Fenerbahçe - Campeonato Turco de Futebol",
            now+2*60000L,now+2*3600000L))
        val matches=footballMatches(Guide(matches=listOf(entry("e",now),reversed)),
            listOf(channel("ESPN 2 FHD","e"),channel("SPORTV HD","s")),now)
        assertEquals(1,matches.size)
        assertEquals(2,matches.single().channels.size)
    }
}
