package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class SportsArtworkRulesTest {
    @Test fun eachTeamSportUsesOnlyItsOwnLeagues() {
        assertEquals(listOf("football/nfl"),sportsBadgeEndpoints(SportKind.AMERICAN_FOOTBALL,"nfl"))
        assertEquals(listOf("football/college-football"),sportsBadgeEndpoints(SportKind.AMERICAN_FOOTBALL,"ncaa.fb"))
        assertEquals("football/college-football",
            sportsBadgeEndpoints(SportKind.AMERICAN_FOOTBALL,"american_football:other").first())
        assertEquals(listOf("basketball/nba"),sportsBadgeEndpoints(SportKind.BASKETBALL,"nba"))
        assertEquals(listOf("basketball/wnba"),sportsBadgeEndpoints(SportKind.BASKETBALL,"wnba"))
        assertEquals(listOf("baseball/mlb"),sportsBadgeEndpoints(SportKind.BASEBALL,"mlb"))
        assertEquals(listOf("hockey/nhl"),sportsBadgeEndpoints(SportKind.HOCKEY,"nhl"))
        assertTrue(sportsBadgeEndpoints(SportKind.MOTORSPORT,"f1").isEmpty())
        assertTrue(sportsBadgeEndpoints(SportKind.TENNIS,"tennis.atp").isEmpty())
    }

    @Test fun collegeTeamCanMatchItsNicknameWithoutGuessingUnrelatedTeams() {
        assertTrue(sportsTeamAliasMatch("BYU","BYU Cougars"))
        assertTrue(sportsTeamAliasMatch("Notre Dame","Notre Dame Fighting Irish"))
        assertTrue(sportsTeamAliasMatch("Carolina Panthers","Carolina Panthers"))
        assertFalse(sportsTeamAliasMatch("Carolina Panthers","Florida Panthers"))
        assertFalse(sportsTeamAliasMatch("BYU","Baylor Bears"))
        assertFalse(sportsTeamAliasMatch("LA","LA Lakers"))
        assertFalse(sportsTeamAliasMatch("Rangers","New York Rangers"))
    }

    @Test fun fallbackChecksProviderSportBeforeSelectingAnyLogo() {
        assertEquals("American Football",sportsDatabaseCategory(SportKind.AMERICAN_FOOTBALL))
        assertEquals("Baseball",sportsDatabaseCategory(SportKind.BASEBALL))
        assertEquals("Basketball",sportsDatabaseCategory(SportKind.BASKETBALL))
        assertEquals("Ice Hockey",sportsDatabaseCategory(SportKind.HOCKEY))
        assertNull(sportsDatabaseCategory(SportKind.MOTORSPORT))
    }
}
