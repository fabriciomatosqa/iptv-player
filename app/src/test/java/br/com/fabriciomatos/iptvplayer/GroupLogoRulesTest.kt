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

    @Test fun existingTvgLogosCoverPpvGroupScreenshots() {
        val examples=listOf(
            Triple("PPV | MAX ⚽","MAX 01","max"),
            Triple("PPV | NFL","(00) |US| NFL Network HD","nfl"),
            Triple("PPV | INSPORTS ⚽","NSPORTS 1 HD","nsports"),
            Triple("PPV | PARAMOUNT+ ⚽","PARAMOUNT+ 1 FHD","paramount"),
            Triple("UFC PARAMOUNT+ ⭐","UFC - HD","ufc"),
            Triple("PPV | AMAZON PRIME ⚽","AMAZON PRIME 1","prime"),
            Triple("PPV | DISNEY+ ⚽","DISNEY+ FHD","disney")
        )
        for((group,title,key) in examples) {
            val url="https://provider.example/"+key+".png"
            val channels=(1..4).map {ChannelLogoCandidate(title+" "+it,url)}
            assertEquals(group,url,providerGroupLogo(group,channels))
        }
        val two=listOf(ChannelLogoCandidate("GLOBO RJ","https://provider/globo.png"),
            ChannelLogoCandidate("GLOBO SP","https://provider/globo.png"))
        assertEquals("https://provider/globo.png",providerGroupLogo("GLOBO | CAPITAIS",two))
        assertEquals("https://provider/globo.png",providerGroupLogo("GLOBO | REGIONAIS",two))
        assertEquals("GLOBO | CAPITAIS",groupDisplayName("GLOBO | CAPITAIS"))
        assertEquals("GLOBO | REGIONAIS",groupDisplayName("GLOBO | REGIONAIS"))
    }

    @Test fun avoidUnrelatedGroupAndSportLogos() {
        val logo="https://provider.example/nfl.png"
        val channels=listOf(
            ChannelLogoCandidate("Jets x Browns",logo),
            ChannelLogoCandidate("Miami x Bengals",logo),
            ChannelLogoCandidate("NFL Network",logo),
            ChannelLogoCandidate("Other","https://provider.example/other.png")
        )
        assertEquals(logo,providerGroupLogo("PPV | NFL",channels))
        assertEquals("",providerGroupLogo("PPV | MIXED",listOf(
            ChannelLogoCandidate("A","https://example/a.png"),
            ChannelLogoCandidate("B","https://example/b.png"))))
        assertEquals("",providerGroupLogo("ESPORTES",channels))
        val espn=Channel("ESPN HD","https://example.test/live","Esportes",
            imageUrl="https://provider.example/espn.png")
        assertEquals("",sportsLeagueLogo("NHL",listOf(espn)))
        assertEquals("",sportsLeagueLogo("Fórmula 1",listOf(espn)))
        val nfl=Channel("NFL Network","https://example.test/nfl","PPV | NFL",
            imageUrl=logo)
        assertEquals(logo,sportsLeagueLogo("NFL",listOf(nfl)))
    }

    @Test fun unrelatedChannelsAndGenericGroupsNeverSupplyLogos() {
        assertEquals(0, groupLogoMatchScore("REDE BANDEIRANTES", "RECORD TV"))
        assertEquals(0, groupLogoMatchScore("REDE RECORD", "BAND CURITIBA HD"))
        assertEquals(0, groupLogoMatchScore("ESPORTES", "SPORTV HD"))
        assertEquals(0, groupLogoMatchScore("CANAIS", "GLOBO FHD"))
    }
}
