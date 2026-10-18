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

    @Test fun providerPpvAndRegionalVariantsUseRealBrandLogosWithoutMergingGroups() {
        val samples = mapOf(
            "GLOBO | CAPITAIS" to ChannelBrand.GLOBO,
            "GLOBO | REGIONAIS" to ChannelBrand.GLOBO,
            "TV GLOBO SP FHD" to ChannelBrand.GLOBO,
            "REDE GLOBO RJ" to ChannelBrand.GLOBO,
            "PPV | AMAZON PRIME ⚽" to ChannelBrand.PRIME_VIDEO,
            "PPV | PRIME VIDEO 4K" to ChannelBrand.PRIME_VIDEO,
            "PPV | DAZN ⚽" to ChannelBrand.DAZN,
            "PPV | DISNEY+ ⚽" to ChannelBrand.DISNEY_PLUS,
            "PPV | DISNEY PLUS HD" to ChannelBrand.DISNEY_PLUS,
            "PPV | CAZÉTV ⚽" to ChannelBrand.CAZETV,
            "PPV | CAZE TV FHD" to ChannelBrand.CAZETV,
            "PPV | GOAT ⚽" to ChannelBrand.GOAT,
            "CANAL GOAT HD" to ChannelBrand.GOAT,
            "SBT HD" to ChannelBrand.SBT,
            "SBT | REGIONAIS" to ChannelBrand.SBT
        )
        samples.forEach { (group,expected) ->
            assertEquals(group,expected,channelBrand(group))
            assertEquals(expected.logoUrl,groupBrandLogoUrl(group))
            assertTrue(expected.logoUrl.startsWith("https://thumb.wikimedia.org/"))
            assertTrue(expected.logoUrl.endsWith(".png"))
        }
        assertEquals(groupBrandLogoUrl("GLOBO | CAPITAIS"),groupBrandLogoUrl("GLOBO | REGIONAIS"))
        assertEquals("GLOBO | CAPITAIS",groupDisplayName("GLOBO | CAPITAIS"))
        assertEquals("GLOBO | REGIONAIS",groupDisplayName("GLOBO | REGIONAIS"))
    }

    @Test fun notEverySimilarWordRefersToTheSameBroadcaster() {
        for(group in listOf("GLOBONEWS","GLOBO NEWS","GLOBOPLAY","GLOBO FILMES",
            "SBT NEWS","DISNEY CHANNEL","DISNEY JUNIOR","GOAT DOCUMENTARY",
            "PPV | DAZNITV","CANAIS","ESPORTES")) {
            assertEquals(group,null,channelBrand(group))
        }
        assertEquals(0,groupLogoMatchScore("PPV | DISNEY+","DISNEY CHANNEL"))
        assertEquals(0,groupLogoMatchScore("GLOBO | CAPITAIS","GLOBONEWS"))
        assertEquals(100,groupLogoMatchScore("GLOBO | REGIONAIS","GLOBO RJ HD"))
        assertEquals(100,groupLogoMatchScore("PPV | DISNEY+ ⚽","DISNEY+ 4K"))
        assertEquals(100,groupLogoMatchScore("PPV | PRIME VIDEO","AMAZON PRIME HD"))
    }

    @Test fun unrelatedChannelsAndGenericGroupsNeverSupplyLogos() {
        assertEquals(0, groupLogoMatchScore("REDE BANDEIRANTES", "RECORD TV"))
        assertEquals(0, groupLogoMatchScore("REDE RECORD", "BAND CURITIBA HD"))
        assertEquals(0, groupLogoMatchScore("ESPORTES", "SPORTV HD"))
        assertEquals(0, groupLogoMatchScore("CANAIS", "GLOBO FHD"))
    }
}
