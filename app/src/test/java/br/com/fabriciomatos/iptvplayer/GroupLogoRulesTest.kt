package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLogoRulesTest {
    @Test fun displayNamesRemoveOnlyProviderDiamonds() {
        assertEquals("PREMIERE", groupDisplayName("♦️ PREMIERE"))
        assertEquals("REDE BANDEIRANTES", groupDisplayName("♦ REDE BANDEIRANTES"))
        assertEquals("REDE RECORD", groupDisplayName("REDE ♦️ RECORD"))
        assertEquals("BAND MG HD", groupDisplayName("BAND MG HD"))
    }

    @Test fun providerNamesMatchTheirOwnChannelLogos() {
        assertTrue(groupLogoMatchScore("♦️ PREMIERE", "PREMIERE 2 HD") > 0)
        assertTrue(groupLogoMatchScore("♦️ REDE BANDEIRANTES", "BAND MG FHD") > 0)
        assertTrue(groupLogoMatchScore("♦️ REDE RECORD", "RECORD HD") > 0)
        assertTrue(groupLogoMatchScore("REDE GLOBO", "GLOBO RJ") > 0)
    }

    @Test fun unrelatedChannelsAndGenericGroupsNeverSupplyLogos() {
        assertEquals(0, groupLogoMatchScore("REDE BANDEIRANTES", "RECORD TV"))
        assertEquals(0, groupLogoMatchScore("REDE RECORD", "BAND CURITIBA HD"))
        assertEquals(0, groupLogoMatchScore("ESPORTES", "SPORTV HD"))
        assertEquals(0, groupLogoMatchScore("CANAIS", "GLOBO FHD"))
    }
}
