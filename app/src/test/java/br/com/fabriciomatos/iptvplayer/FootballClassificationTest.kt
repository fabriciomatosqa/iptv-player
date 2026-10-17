package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class FootballClassificationTest {
    private val now=1800000000000L
    private fun game(comp: String, home: String="Aurora", away: String="Norte"): FootballMatch {
        val p=Programme("$home x $away - $comp",now,now+7200000,broadcast=BroadcastHint.LIVE)
        return FootballMatch(comp,p,emptyList(),FootballDetails(comp,home,away))
    }
    @Test fun originalPrefixesIdentifyBothReportedFixturesBeforeDisplayCleanup() {
        for((home,away) in listOf("Belgrano" to "Palmeiras","Corinthians" to "Caracas")) {
            for(prefix in listOf("Libertadores Feminino","Libertadores Feminina","Copa Libertadores Feminina")) {
                val details=footballDetails("$prefix - $home x $away")!!
                assertEquals(home,details.home);assertEquals(away,details.away)
                assertEquals(FootballGender.WOMEN,details.gender)
                assertEquals(FOOTBALL_WOMEN,footballTabKey(details))
                assertTrue(footballScoreLeagues(details).isEmpty())
            }
        }
    }
    @Test fun femaleCompetitionsAreOpenEndedAndAggregateWithoutLosingTheirIdentity() {
        val names=listOf("Brasileirão Feminino","Women's Champions League","Women's Super League",
            "Women's World Cup","Copa do Mundo Feminina","Taça Aurora Feminina")
        val games=names.map { game(it) }
        assertTrue(games.all { it.details.gender==FootballGender.WOMEN })
        assertEquals(listOf(FOOTBALL_LIVE,FOOTBALL_GAMES,FOOTBALL_WOMEN),footballTabs(games).map { it.key })
        assertEquals(names,games.map { it.details.competition })
        assertEquals(names.size-1,games.map { footballCompetitionIdentity(it.details) }.distinct().size)
    }
    @Test fun evidenceCanComeFromEachAvailableMetadataSource() {
        val metadata=listOf(FootballMetadata(competition="Libertadores Feminina"),
            FootballMetadata(originalTitle="Libertadores Feminina - Belgrano x Palmeiras"),
            FootballMetadata(subtitle="Libertadores Feminina"),FootballMetadata(description="Futebol feminino, Libertadores"),
            FootballMetadata(gender="female"))
        for(raw in metadata) assertEquals(FootballGender.WOMEN,footballDetails("Belgrano x Palmeiras","Futebol",raw)!!.gender)
        assertEquals(FootballGender.WOMEN,footballDetails("Belgrano x Palmeiras","Libertadores Feminina")!!.gender)
        assertEquals(FootballGender.WOMEN,footballDetails("Belgrano x Palmeiras","Futebol",channelNames=listOf("Libertadores Feminina HD"))!!.gender)
        assertEquals(FootballGender.WOMEN,footballDetails("Belgrano x Palmeiras","Futebol",playlistGroups=listOf("Jogos Femininos"))!!.gender)
    }
    @Test fun clubIdentityNeverDeterminesGenderAndExplicitCompetitionBeatsGenericGroup() {
        for(home in listOf("Palmeiras","Corinthians","Flamengo","Belgrano")) {
            assertEquals(FootballGender.UNKNOWN,footballDetails("$home x Santos","Futebol")!!.gender)
        }
        val men=footballDetails("Libertadores Masculina - Palmeiras x Flamengo","Jogos Femininos")!!
        assertEquals(FootballGender.MEN,men.gender)
        assertEquals(FOOTBALL_LIBERTADORES,footballTabKey(men))
        assertEquals(FootballGender.CONFLICT,footballDetails("Palmeiras x Santos","Futebol",FootballMetadata(gender="men women"))!!.gender)
    }
    @Test fun statusIsNeverACompetitionAndBothGeneralFiltersReuseTheSameRecords() {
        val unknown=footballDetails("Aurora x Norte","Futebol Ao Vivo")!!
        assertEquals("",unknown.competition)
        val women=game("Libertadores Feminina","Belgrano","Palmeiras")
        assertSame(women,filterFootball(listOf(women),FOOTBALL_LIVE,"",now).single())
        assertSame(women,filterFootball(listOf(women),FOOTBALL_WOMEN,"Palmeiras",now).single())
        assertSame(women,filterFootball(listOf(women),FOOTBALL_GAMES,"",now).single())
        assertEquals(FOOTBALL_WOMEN,canonicalFootballTab("__libertadores_women__"))
    }
    @Test fun aliasesDoNotMergeCountriesDivisionsOrGenders() {
        fun id(comp:String)=footballCompetitionIdentity(FootballDetails(comp,"Aurora","Norte"))
        assertEquals(id("Premier League"),id("Campeonato Inglês — Primeira Divisão"))
        assertEquals(id("Serie A"),id("Campeonato Italiano"))
        assertEquals(id("Primeira Liga"),id("Campeonato Português"))
        assertEquals(id("Sulamericana"),id("Sul-Americana"))
        assertEquals(id("Libertadores Feminino"),id("Libertadores Feminina"))
        for((a,b) in listOf("Premier League" to "Championship","Brasileirão" to "Brasileirão Feminino",
            "Libertadores" to "Libertadores Feminina","Brasileirão" to "Brasileirão Série B",
            "Bundesliga" to "2. Bundesliga","Serie A" to "Serie B Italiana","Champions League" to "AFC Champions League Elite")) assertNotEquals(id(a),id(b))
        val africa=FootballDetails("Premier League","Aurora","Norte",country="South Africa")
        assertNotEquals(id("Premier League"),footballCompetitionIdentity(africa))
        assertTrue(footballScoreLeagues(africa).isEmpty())
        assertNull(footballLeagueSlug("Brasileirão Série C"))
    }
    @Test fun discoveryUsesStablePriorityGroupsAndConfigurableOverrides() {
        val names=listOf("J1 League","Campeonato Português","Bundesliga","Premier League","Campeonato Italiano",
            "Championship","Campeonato Turco","Brasileirão","Libertadores","Sul-Americana","Libertadores Feminina","Copa do Mundo")
        val games=names.map { game(it) }
        val expected=listOf(FOOTBALL_LIVE,FOOTBALL_GAMES,FOOTBALL_BRAZIL,FOOTBALL_LIBERTADORES,FOOTBALL_SUDAMERICANA,
            FOOTBALL_WOMEN,FOOTBALL_FIFA,"eng.1","ita.1","ger.1","por.1","tur.1","eng.2","jpn.1")
        assertEquals(expected,footballTabs(games).map { it.key })
        assertEquals(footballTabs(games),footballTabs(games.reversed()))
        assertEquals("jpn.1",footballTabs(games,mapOf("jpn.1" to FootballCategoryPriority(0,0)))[2].key)
        val novel=footballDetails("Aurora x Norte - Taça das Ilhas")!!
        val dynamic=game(novel.competition)
        assertTrue(footballTabs(listOf(dynamic)).any { it.title=="Taça das Ilhas" })
        assertEquals(3,footballTabs(listOf(game("Premier League"),game("Campeonato Inglês"))).size)
    }
    @Test fun youthAndExplicitDivisionsRemainSeparateAndNeverUseSeniorScores() {
        val details=footballDetails("Aurora x Norte","Campeonato Brasileiro",FootballMetadata(division="Série B"))!!
        assertEquals("bra.2",footballTabKey(details))
        val youth=FootballDetails("Libertadores Sub-20","Palmeiras","Flamengo")
        assertTrue(footballScoreLeagues(youth).isEmpty())
        assertNotEquals(footballCompetitionIdentity(youth),footballCompetitionIdentity(youth.copy(ageGroup="17")))
    }
    @Test fun refreshRetainsEvidenceOnlyForAnUnchangedFixtureAndRespectsCorrections() {
        val old=Programme("Libertadores Feminina - Belgrano x Palmeiras",now,now+7200000)
        val clean=Programme("Belgrano x Palmeiras",now,now+7200000,category="Futebol")
        val retained=retainFootballEvidence(clean,old)
        assertEquals(clean.title,retained.title)
        assertEquals(FootballGender.WOMEN,footballDetails(retained.title,retained.category,retained.metadata)!!.gender)
        assertEquals(retained,retainFootballEvidence(retained,retained))
        val other=clean.copy(title="Corinthians x Caracas")
        assertEquals(other,retainFootballEvidence(other,old))
        val corrected=clean.copy(metadata=FootballMetadata(competition="Libertadores Masculina"))
        assertEquals(FootballGender.MEN,footballDetails(corrected.title,corrected.category,retainFootballEvidence(corrected,old).metadata)!!.gender)
    }
}
