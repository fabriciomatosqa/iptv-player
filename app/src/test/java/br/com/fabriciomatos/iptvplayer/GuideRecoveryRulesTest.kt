package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class GuideRecoveryRulesTest {
    @Test fun realEmptyAndExpiredResponsesHaveDifferentStatuses() {
        assertEquals(GuideStatus.EMPTY,guideStatus(GuideCounts(channels=269),0))
        assertEquals(GuideStatus.STALE,guideStatus(GuideCounts(2,52,0),0))
        assertEquals(GuideStatus.NO_GAMES,guideStatus(GuideCounts(2,52,20),0))
        assertEquals(GuideStatus.UNLINKED,guideStatus(GuideCounts(2,52,20,4),0))
        assertEquals(GuideStatus.READY,guideStatus(GuideCounts(2,52,20,4),2))
    }
    @Test fun alternateMappingRequiresAnExplicitKnownBrazilianBroadcasterId() {
        assertEquals("espn4",BrazilBroadcastGuide.network("ESPN.4.br"))
        assertEquals("espn4",BrazilBroadcastGuide.network("espn4.br"))
        assertNull(BrazilBroadcastGuide.network("ESPN.4.pt"))
        assertNull(BrazilBroadcastGuide.network("espn40.br"))
        assertNull(BrazilBroadcastGuide.network("ESPN 4 HD"))
        assertNull(BrazilBroadcastGuide.network("arbitrary.br"))
    }
    @Test fun describedMatchupsNeedOneConcisePairAndExplicitSportingEvidence() {
        val metadata=FootballMetadata(originalTitle="Campeonato Brasileiro Série B",description="Vila Nova x Novorizontino")
        val details=footballDetails("Campeonato Brasileiro Série B",metadata=metadata)!!
        assertEquals("Vila Nova",details.home);assertEquals("Novorizontino",details.away)
        assertNull(footballDetails("Cinema",metadata=metadata.copy(originalTitle="",description="Atores x Atrizes")))
        assertNull(footballDetails("Futebol",metadata=metadata.copy(description="Vila Nova x Novorizontino\nBrasil x Argentina")))
        assertNull(footballDetails("NFL",metadata=metadata.copy(description="Vikings x Lions")))
    }
    @Test fun replayMarkersAndFootballInDescriptionsDoNotBecomeFalseLiveGames() {
        val metadata=FootballMetadata(description="É a divisão de elite do futebol português.")
        val details=footballDetails("VT - Porto x Estoril",metadata=metadata)!!
        assertEquals("Porto",details.home);assertEquals("Estoril",details.away)
        assertEquals(BroadcastHint.REPLAY,programmeBroadcastHint("VT - Porto x Estoril",""))
        assertNull(footballDetails("VT - NFL: Vikings x Lions",metadata=metadata))
    }
    @Test fun refreshedGuideIdentifiersDoNotRequireDirectoryRebuildsOrEraseMissingIds() {
        val c=Channel("ESPN 4 FHD","https://example.test/live/1","Esportes",tvgId="old.id")
        val old=CatalogImportRow.prepare(c,1)
        val fresh=CatalogImportRow.prepare(c.copy(tvgId="espn.4.br"),2)
        assertTrue(fresh.differsFrom(old));assertFalse(fresh.changesDirectory(old))
        assertEquals("espn.4.br",fresh.preserving(old).channel.tvgId)
        assertEquals("old.id",CatalogImportRow.prepare(c.copy(tvgId=""),2).preserving(old).channel.tvgId)
    }
}
