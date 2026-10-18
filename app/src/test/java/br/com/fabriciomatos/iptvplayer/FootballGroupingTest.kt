package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class FootballGroupingTest {
    private fun channel(name:String,id:String)=Channel(name,"https://example.test/live/$id","Sports",tvgId=id)
    private fun entry(id:String,start:Long,hint:BroadcastHint=BroadcastHint.UNKNOWN) =
        GuideMatch(id,Programme("Fenerbahçe x Alanyaspor - Campeonato Turco de Futebol",start,start+2*3600000L,broadcast=hint))
    @Test fun sameFixtureWithDifferentEpgMinutesSharesOneCardAndAllStreams() {
        val now=1800000000000L
        val channels=listOf(channel("ESPN 2 FULL HD","e"),channel("ESPN 2 HD","f"),channel("SPORTV HD","s"),
            channel("SPORTV SD","t"))
        val items=listOf(entry("e",now),entry("f",now+5*60000L),entry("s",now+15*60000L),entry("t",now+12*60000L))
        val matches=footballMatches(Guide(matches=items),channels,now)
        assertEquals(1,matches.size)
        assertEquals(4,matches.single().channels.size)
        assertEquals(listOf("ESPN 2 FULL HD","SPORTV HD"),footballFeaturedChannels(matches.single().channels).map {it.name})
    }
    @Test fun distinctFixturesAndReplaysRemainSeparate() {
        val now=1800000000000L
        val channels=listOf(channel("ESPN 2 FHD","e"),channel("SPORTV HD","s"),channel("ESPN 2 HD","f"))
        val matches=footballMatches(Guide(matches=listOf(entry("e",now),entry("s",now+95*60000L),
            entry("f",now+4*60000L,BroadcastHint.REPLAY))),channels,now)
        assertEquals(3,matches.size)
    }
    @Test fun aPreviousEpgSlotAloneDoesNotProveAReplay() {
        val now=1800000000000L
        val yesterday=now-24*3600000L
        val second=now+4*3600000L
        val channels=listOf(channel("ESPN 4 FHD","past"),channel("ESPN 2 4K","future"))
        val previous=GuideMatch("past",Programme(
            "Sport x Fortaleza - Campeonato Brasileiro - Série B",
            yesterday,yesterday+2*3600000L))
        val later=GuideMatch("future",Programme(
            "Sport x Fortaleza - Campeonato Brasileiro - Série B",
            second,second+2*3600000L))
        val actual=footballMatches(Guide(matches=listOf(previous,later)),channels,now)
        assertEquals(1,actual.size)
        assertEquals(BroadcastHint.UNKNOWN,actual.single().programme.broadcast)
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(actual.single().programme,null))
        assertFalse(compactFootballMatch(actual.single(),null,now))
        assertEquals("Sport",actual.single().details.home)
    }
    @Test fun scheduledFixturesWithoutPriorEvidenceMustNotBeLabelledReplay() {
        val now=1800000000000L
        val scheduled=GuideMatch("future",Programme("Sport x Fortaleza - Campeonato Brasileiro - Série B",
            now+4*3600000L,now+6*3600000L))
        val matches=footballMatches(Guide(matches=listOf(scheduled)),
            listOf(channel("ESPN 2 FHD","future")),now)
        assertEquals(BroadcastHint.UNKNOWN,matches.single().programme.broadcast)
    }
    @Test fun womensLibertadoresChannelsFromDifferentFeedLabelsAreGrouped() {
        val now=1800000000000L
        val live=Guide(matches=listOf(
            GuideMatch("a",Programme("Libertadores Feminina - Belgrano x Palmeiras",now,now+2*3600000L)),
            GuideMatch("b",Programme("Belgrano x Palmeiras - Copa Libertadores Feminina",now+5*60000L,now+2*3600000L))))
        val matches=footballMatches(live,listOf(channel("ESPN 2 4K","a"),channel("ESPN 2 FHD","b")),now)
        assertEquals(1,matches.size)
        assertEquals(2,matches.single().channels.size)
        assertEquals(FOOTBALL_LIBERTADORES_WOMEN,footballTabKey(matches.single().details))
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
