package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class MotorsportChampionshipTest {
    private val start=championshipStart("2026-06-14","18:00:00Z")!!
    private fun race(round: Int=1, time: Long?=start, completed: Boolean=false) = ChampionshipRace(
        round,"Example Grand Prix","Example","Example Circuit","2026-06-14",time,
        listOf(ChampionshipSession("race","Corrida","2026-06-14",time)),completed)

    @Test fun datesWithoutTimesNeverInventMidnight() {
        assertNull(championshipStart("2026-06-14",""))
        assertNull(championshipStart("2026-02-30","18:00:00Z"))
        assertNull(championshipStart("2026-06-14","25:00:00Z"))
        assertEquals(1781460000000L,start)
    }

    @Test fun utcParsingIsIndependentOfDeviceTimeZone() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
            assertEquals(start,championshipStart("2026-06-14","18:00:00Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(start,championshipStart("2026-06-14","18:00:00Z"))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun onlyConfirmedResultsMarkRaceAsFinished() {
        assertEquals("Situação não informada",championshipRaceStatus(race(),start+86400000))
        assertEquals("Programada",championshipRaceStatus(race(),start-1))
        assertEquals("Finalizada · resultado disponível",championshipRaceStatus(race(completed=true),start+1))
    }

    @Test fun nextRaceSkipsFinishedAndPastUnconfirmedEvents() {
        val future=race(3,start+86400000)
        val season=ChampionshipSeason(year=2026,races=listOf(race(completed=true),race(2,start-86400000),future))
        assertEquals(future,season.nextRace(start))
        assertNull(season.nextRace(start+2*86400000))
    }

    @Test fun gapUsesOfficialPointsIncludingFractions() {
        val leader=ChampionshipStanding("a",1,"A","Team",25.5,1)
        val second=leader.copy(id="b",position=2,points=18.0)
        assertEquals(7.5,championshipGap(second,leader)!!,0.0)
        assertNull(championshipGap(second,null))
        assertNull(championshipGap(second,second))
    }

    @Test fun broadcastsRequireCategorySessionTimeAndNonReplay() {
        val programme=Programme("F1 - Example Grand Prix - Corrida",start,start+7200000,
            metadata=FootballMetadata(originalTitle="F1 - Example Grand Prix - Corrida"))
        val channel=Channel("Example TV","https://example.test/stream","Sports")
        val event=SportsEvent("race",SportKind.MOTORSPORT,"f1","Fórmula 1",programme.title,"","",programme,listOf(channel))
        val wrongCategory=event.copy(id="f2",competitionId="f2")
        val wrongSession=event.copy(id="practice",programme=programme.copy(title="F1 - Treino Livre 1",
            metadata=FootballMetadata(originalTitle="F1 - Treino Livre 1")))
        val wrongDay=event.copy(id="other",programme=programme.copy(start=start+86400000))
        val replay=event.copy(id="replay",programme=programme.copy(broadcast=BroadcastHint.REPLAY))
        val found=championshipBroadcasts(race(),listOf(event,wrongCategory,wrongSession,wrongDay,replay,event))
        assertEquals(listOf(event),found)
        assertSame(channel,found.single().channels.single())
        assertTrue(championshipBroadcasts(race(time=null),listOf(event)).isEmpty())
    }
}
