package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Synthetic regression fixtures: no provider credentials or stream addresses. */
class FootballBroadcasterDiagnosisTest {
    private val now=xmltvTime("20261018180000 -0300")
    private val title="Vila Nova x Novorizontino - Campeonato Brasileiro - Série B"
    private val score=FootballScore("fotmob:1","bra.2","Série B",now,
        ScoreTeam("1",listOf("Vila Nova")),ScoreTeam("2",listOf("Novorizontino")),0,1,MatchState.LIVE,"1:00",now)
    private fun channel(name: String="ESPN 4 FHD", id: String="espn.4.br", stream: String=id)=
        Channel(name,"https://example.test/live/$stream","Esportes",tvgId=id)
    private fun slot(start: Long=now,end: Long=start+7200000L,hint: BroadcastHint=BroadcastHint.UNKNOWN)=
        Programme(title,start,end,broadcast=hint)
    private fun guide(p: Programme=slot(),names: Map<String,String> = emptyMap())=
        Guide(mapOf("espn.4.br" to listOf(p)),names,listOf(GuideMatch("espn.4.br",p)))

    @Test fun vilaNovaAtEighteenNeverMovesTheEspnSlotFromTwentyThree() {
        val future=slot(xmltvTime("20261018230000 -0300"),xmltvTime("20261019010000 -0300"))
        val other=Programme("NFL RedZone",xmltvTime("20261018140000 -0300"),xmltvTime("20261018203000 -0300"))
        val g=guide(future).copy(programmes=mapOf("espn.4.br" to listOf(other,future)))
        val channels=(1..6).map { channel(stream="espn$it") }
        val match=footballMatches(g,channels,now).single()
        assertEquals(future,match.programme)
        assertEquals(6,match.channels.size)
        assertEquals(other,g.forChannel(channels.first()).single { it.start<=now && now<it.end })
        assertTrue(filterFootball(listOf(match),FOOTBALL_LIVE,"",now,FootballFeed(listOf(score),now)).isEmpty())
        assertFalse(transmissionIsLive(future,score,now))
        assertFalse(transmissionIsLive(future,score.copy(checked=future.start),future.start))
        assertEquals(listOf(match),filterFootball(listOf(match),FOOTBALL_GAMES,"",now))
    }
    @Test fun onlyCompatibleActiveIntervalsConfirmALiveTransmission() {
        val p=slot(now-600000L)
        assertTrue(transmissionIsLive(p,score,now))
        assertFalse(transmissionIsLive(p,score, p.end))
        assertFalse(transmissionIsLive(p,score.copy(kickoff=now-5*3600000L),now))
        assertFalse(transmissionIsLive(p,score.copy(state=MatchState.POSTPONED),now))
        assertFalse(transmissionIsLive(p,score.copy(state=MatchState.CANCELLED),now))
        assertFalse(transmissionIsLive(p,score.copy(checked=now-300000L),now))
        assertFalse(transmissionIsLive(p.copy(broadcast=BroadcastHint.REPLAY),score,now))
    }
    @Test fun missingIdsNeedAnUnambiguousExactBroadcasterName() {
        val c=channel(id="",stream="no-id")
        assertEquals(listOf(c),footballMatches(guide(names=mapOf("espn 4" to "espn.4.br")),listOf(c),now).single().channels)
        val disney=channel("DISNEY+ 9","","disney9")
        val catalog=listOf(c,disney)
        assertEquals(listOf(c),footballMatches(guide(names=mapOf("espn 4" to "espn.4.br")),catalog,now).single().channels)
        assertTrue(guide().forChannel(disney).isEmpty())
        assertEquals(2,catalog.filter { it.kind==MediaKind.LIVE }.size)
        assertTrue(footballMatches(guide(),listOf(disney),now).isEmpty())
    }
    @Test fun conflictingIdentifiersAreNeverOverriddenBySimilarNamesOrGroups() {
        val correct=channel()
        val wrong=channel("ESPN 4 HD","different.id")
        val similar=channel("ESPN 40 HD","","similar")
        val g=guide(names=mapOf("espn 4" to "espn.4.br"))
        assertEquals(listOf(correct),footballMatches(g,listOf(correct,wrong,similar,correct),now).single().channels)
        assertTrue(g.forChannel(wrong).isEmpty())
        assertTrue(g.forChannel(similar).isEmpty())
    }
    @Test fun ambiguousNormalizedAliasesDoNotGuessAChannel() {
        val g=guide(names=mapOf("espn 4 hd" to "espn.4.br","espn 4 sd" to "other.id"))
        val c=channel(id="",stream="no-id")
        assertTrue(g.forChannel(c).isEmpty())
        assertTrue(footballMatches(g,listOf(c),now).isEmpty())
        assertEquals(listOf(channel()),footballMatches(g,listOf(channel()),now).single().channels)
    }
    @Test fun finalScoresDoNotChangeFutureSchedulesOrProveAReplay() {
        val p=slot(now+5*3600000L)
        val match=footballMatches(guide(p),listOf(channel()),now).single()
        val final=score.copy(state=MatchState.FINISHED)
        assertNotNull(matchFootballScore(match,FootballFeed(listOf(final),now)))
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(p,final))
        assertFalse(compactFootballMatch(match,final,now))
        assertFalse(transmissionIsLive(p,final,p.start))
        assertEquals(p,match.programme)
    }
    @Test fun xmltvOffsetsAndDeviceTimezonesRepresentTheSameInstant() {
        assertEquals(now,xmltvTime("20261018210000 +0000"))
        assertEquals(now,xmltvTime("20261018230000 +0200"))
        assertEquals("18:00",footballTime(now,Locale.ROOT,TimeZone.getTimeZone("America/Sao_Paulo")))
        assertEquals("23:00",footballTime(now,Locale.ROOT,TimeZone.getTimeZone("Europe/Paris")))
    }
    @Test fun midnightCrossingUsesAbsoluteStartAndEndWithoutExtendingTheSlot() {
        val p=slot(xmltvTime("20261018233000 -0300"),xmltvTime("20261019013000 -0300"),BroadcastHint.LIVE)
        assertFalse(transmissionIsLive(p,null,p.start-1))
        assertTrue(transmissionIsLive(p,null,xmltvTime("20261019003000 -0300")))
        assertFalse(transmissionIsLive(p,null,p.end))
    }
    @Test fun groupedChannelsKeepIndependentIntervalsAndOnlyActiveOnesAppearLive() {
        val a=channel()
        val b=channel("RedeTV HD","redetv.br")
        val p=slot(now-600000L)
        val future=slot(now+600000L)
        val g=Guide(matches=listOf(GuideMatch(a.tvgId,p),GuideMatch(b.tvgId,future)))
        val match=footballMatches(g,listOf(a,b),now).single()
        assertEquals(2,match.channels.size)
        assertEquals(future,match.programmeFor(b))
        assertEquals(listOf(a),footballLiveChannels(match,score,now))
        val filtered=filterFootball(listOf(match),FOOTBALL_LIVE,"",now,FootballFeed(listOf(score),now)).single()
        assertEquals(listOf(a),filtered.channels)
        val later=now+1200000L
        assertEquals(2,footballLiveChannels(match,score.copy(checked=later),later).size)
        val afterFirst=p.end
        assertEquals(listOf(b),footballLiveChannels(match,score.copy(checked=afterFirst),afterFirst))
        assertEquals(listOf(b),filterFootball(listOf(match),FOOTBALL_LIVE,"",afterFirst,
            FootballFeed(listOf(score.copy(checked=afterFirst)),afterFirst)).single().channels)
        assertFalse(footballHasLiveTransmission(match,score.copy(checked=future.end),future.end))
    }
}
