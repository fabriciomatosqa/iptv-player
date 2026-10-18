package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class FootballFormattingTest {
    @Test fun weekdaysAndMonthsAreWrittenInFullAndThePhoneTimezoneIsRespected() {
        val date=java.time.Instant.parse("2026-10-13T02:30:00Z").toEpochMilli()
        val locale=Locale.forLanguageTag("pt-BR")
        assertEquals("Segunda-Feira - 12 de Outubro",footballDay(date,locale,TimeZone.getTimeZone("America/Sao_Paulo")))
        assertEquals("23:30",footballTime(date,locale,TimeZone.getTimeZone("America/Sao_Paulo")))
        assertEquals("Terça-Feira - 13 de Outubro",footballDay(date,locale,TimeZone.getTimeZone("UTC")))
        assertEquals("Tuesday - 13 October",footballDay(date,Locale.ENGLISH,TimeZone.getTimeZone("UTC")))
    }
    @Test fun broadcastsExpireAtTheirOriginalEndEvenWhenTheGameHasOvertime() {
        val now=1000000000L
        val p=Programme("Fluminense x Botafogo",now-7200000L,now)
        val match=FootballMatch("test",p,emptyList(),FootballDetails("Campeonato Brasileiro","Fluminense","Botafogo"))
        assertEquals(1,filterFootball(listOf(match),FOOTBALL_GAMES,"",now+3599999L).size)
        assertTrue(filterFootball(listOf(match),FOOTBALL_GAMES,"",now+3600000L).isEmpty())
        val live=FootballScore("1","bra.1","Brasileirão",p.start,ScoreTeam("1",listOf("Fluminense")),ScoreTeam("2",listOf("Botafogo")),1,0,MatchState.LIVE,"120:00",now+3600000L)
        assertTrue(filterFootball(listOf(match),FOOTBALL_GAMES,"",now+3600000L,FootballFeed(listOf(live))).isEmpty())
        assertTrue(filterFootball(listOf(match),FOOTBALL_GAMES,"",now+3840001L,FootballFeed(listOf(live))).isEmpty())
    }
    @Test fun blinkingLiveTransmissionRequiresACurrentGameAndNeverAppliesToAReplay() {
        val now=1000000000L;val p=Programme("Fluminense x Botafogo",now-60000,now+3600000)
        val score=FootballScore("1","bra.1","Brasileirão",now-60000,ScoreTeam("1",listOf("Fluminense")),ScoreTeam("2",listOf("Botafogo")),1,0,MatchState.LIVE,"1:00",now)
        assertTrue(transmissionIsLive(p,score,now))
        assertFalse(transmissionIsLive(p.copy(broadcast=BroadcastHint.REPLAY),score,now))
        assertFalse(transmissionIsLive(p,score.copy(state=MatchState.FINISHED),now))
        assertFalse(transmissionIsLive(p,score.copy(checked=now-300000),now))
        assertFalse(transmissionIsLive(p.copy(start=now+60000),score,now))
        assertFalse(transmissionIsLive(p,null,now))
        assertTrue(transmissionIsLive(p.copy(broadcast=BroadcastHint.LIVE),null,now))
    }
}
