package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class SportsHubRulesTest {
    private val now=1794358800000L
    private fun channel(id:String,name:String="Canal $id HD",tvgId:String=id)=
        Channel(name,"https://example.test/live/$id","Esportes",tvgId=tvgId,typeHint="live")
    private fun slot(title:String,start:Long=now,end:Long=start+7200000L,
        hint:BroadcastHint=BroadcastHint.UNKNOWN,competition:String="",sport:String="")=
        Programme(title,start,end,broadcast=hint,metadata=FootballMetadata(competition=competition,sport=sport))
    private fun schedule(entries:List<GuideMatch>,channels:List<Channel>)=
        buildSportsSchedule(Guide(matches=entries),channels,now)

    @Test fun emptyOrGenericEpgDoesNotCreateSportsOrEmptyCompetitions() {
        val result=schedule(listOf(GuideMatch("a",slot("ESPN - Programação")),
            GuideMatch("a",slot("NBA - Debate: Lakers x Warriors")),
            GuideMatch("a",slot("F1 - Melhores momentos do GP do Brasil"))),listOf(channel("a")))
        assertTrue(result.football.isEmpty());assertTrue(result.other.isEmpty())
        assertTrue(sportsHubCatalog(result.football,result.other,now).disciplines.isEmpty())
        assertTrue(footballVisibleTabs(emptyList()).none {it.key==FOOTBALL_BRAZIL})
    }

    @Test fun hierarchyContainsOnlyEpgSportsAndTheirActualCompetitions() {
        val result=schedule(listOf(
            GuideMatch("a",slot("Lakers x Warriors - NBA")),
            GuideMatch("b",slot("Franca x Minas - NBB")),
            GuideMatch("c",slot("Flamengo x Palmeiras - Copa do Brasil"))),
            listOf(channel("a"),channel("b"),channel("c")))
        val catalog=sportsHubCatalog(result.football,result.other,now)
        assertEquals(listOf(SportKind.FOOTBALL,SportKind.BASKETBALL),catalog.disciplines.map {it.sport})
        assertEquals(setOf("nba","nbb"),catalog.disciplines.last().competitions.map {it.id}.toSet())
        assertEquals(2,catalog.disciplines.last().count)
        assertFalse(catalog.disciplines.any {it.sport==SportKind.MOTORSPORT})
    }

    @Test fun aliasesConsolidateStreamsButKeepIndividualSlotsAndExclusiveEnd() {
        val a=slot("NBA: Lakers x Warriors",hint=BroadcastHint.LIVE)
        val b=slot("Warriors vs Lakers - National Basketball Association",start=now+600000L,
            hint=BroadcastHint.LIVE)
        val channels=listOf(channel("a","Canal A HD"),channel("b","Canal B FHD"))
        val event=schedule(listOf(GuideMatch("a",a),GuideMatch("b",b)),channels).other.single()
        assertEquals(2,event.channels.size)
        assertEquals(a,event.programmeFor(channels[0]));assertEquals(b,event.programmeFor(channels[1]))
        assertEquals(listOf(channels[0]),sportsLiveChannels(event,now+1))
        assertEquals(listOf(channels[1]),sportsLiveChannels(event,a.end))
        assertTrue(sportsLiveChannels(event,b.end).isEmpty())
    }

    @Test fun conflictingIdsAndAmbiguousAliasesNeverBorrowAnotherChannelsProgramme() {
        val p=slot("Lakers x Warriors - NBA")
        val guide=Guide(names=mapOf("ESPN HD" to "a","ESPN FHD" to "b"),
            matches=listOf(GuideMatch("a",p),GuideMatch("b",p)))
        val wrong=channel("wrong","ESPN HD",tvgId="foreign.id")
        val ambiguous=channel("blank","ESPN HD",tvgId="")
        assertTrue(sportsEvents(guide,listOf(wrong,ambiguous),now).isEmpty())
        assertEquals(1,sportsEvents(guide,listOf(channel("a","ESPN HD")),now).size)
    }

    @Test fun similarParticipantsDifferentLeaguesAndDifferentSlotsRemainSeparate() {
        val entries=listOf(
            GuideMatch("a",slot("Alpha x Beta - NBA")),
            GuideMatch("b",slot("Alpha B x Beta - NBA")),
            GuideMatch("c",slot("Alpha x Beta - NBB")),
            GuideMatch("a",slot("Alpha x Beta - NBA",start=now+1800000L)))
        assertEquals(4,schedule(entries,listOf(channel("a"),channel("b"),channel("c"))).other.size)
    }

    @Test fun replayRequiresEpgEvidenceAndDoesNotMergeWithOriginalBroadcast() {
        val original=slot("Alpha x Beta - NBA",hint=BroadcastHint.LIVE)
        val replay=original.copy(broadcast=BroadcastHint.REPLAY)
        val events=schedule(listOf(GuideMatch("a",original),GuideMatch("b",replay)),
            listOf(channel("a"),channel("b"))).other
        assertEquals(2,events.size)
        assertEquals(SportsBroadcastState.REPLAY,sportsBroadcastState(replay,now))
        val finished=FootballScore("id","bra.1","Brasileirão",now-86400000L,
            ScoreTeam("a",listOf("Alpha")),ScoreTeam("b",listOf("Beta")),1,0,MatchState.FINISHED,checked=now)
        assertEquals(BroadcastMode.UNCONFIRMED,broadcastMode(original.copy(broadcast=BroadcastHint.UNKNOWN),finished))
        assertFalse(transmissionIsLive(original,finished,now))
    }

    @Test fun expiredInvalidAndBeyondWindowProgrammesDoNotPopulateTheHub() {
        val entries=listOf(
            GuideMatch("a",slot("Alpha x Beta - NBA",end=now-FOOTBALL_RETENTION_MS)),
            GuideMatch("a",slot("Alpha x Beta - NBA",start=now+SPORTS_LOOKAHEAD_MS)),
            GuideMatch("a",slot("Alpha x Beta - NBA",end=now-1)),
            GuideMatch("a",slot("Alpha x Beta - NBA",start=0)),
            GuideMatch("a",slot("Alpha x Beta - NBA",start=now-30*86400000L,end=now+30*86400000L)))
        assertTrue(schedule(entries,listOf(channel("a"))).other.isEmpty())
    }

    @Test fun femaleMaleYouthAndDivisionEvidenceIsPreservedOutsideFootball() {
        val entries=listOf(
            GuideMatch("a",slot("Alpha x Beta - Superliga de Vôlei Feminina")),
            GuideMatch("b",slot("Alpha x Beta - Superliga de Vôlei Masculina")),
            GuideMatch("c",slot("Alpha x Beta - Superliga de Vôlei Sub-20")),
            GuideMatch("d",slot("Alpha x Beta - Superliga de Vôlei",competition="Superliga de Vôlei")
                .copy(metadata=FootballMetadata(competition="Superliga de Vôlei",division="Série B"))))
        val events=schedule(entries,('a'..'d').map {channel(it.toString())}).other
        assertEquals(4,events.size)
        assertEquals(4,events.map {it.competitionId}.toSet().size)
        assertEquals(4,sportsHubCatalog(emptyList(),events,now).disciplines.single().competitions.size)
    }

    @Test fun eachGrandPrixKeepsEveryRealSessionAndDoesNotMergeAnotherStage() {
        val entries=listOf(
            GuideMatch("a",slot("F1 - GP do Brasil - Treino Livre 1")),
            GuideMatch("b",slot("Fórmula 1 - Grande Prêmio do Brasil - Practice 1",start=now+60000L)),
            GuideMatch("c",slot("F1 - GP do Brasil - Classificação",start=now+86400000L)),
            GuideMatch("d",slot("F1 - GP do Brasil - Corrida",start=now+2*86400000L)),
            GuideMatch("e",slot("F1 - GP de Mônaco - Corrida",start=now+2*86400000L)))
        val events=schedule(entries,('a'..'e').map {channel(it.toString())}).other
        assertEquals(4,events.size)
        val championship=sportsHubCatalog(emptyList(),events,now).disciplines.single().competitions.single()
        assertEquals("f1",championship.id)
        assertEquals(3,championship.events.count {it.stage?.stage=="GP do Brasil"})
        assertTrue(events.all {it.home.isEmpty() && it.away.isEmpty()})
    }

    @Test fun unknownSportMetadataCreatesADisciplineOnlyForConcreteEvents() {
        val entries=listOf(GuideMatch("a",slot("Alpha x Beta - Copa Aurora",sport="Badminton",competition="Copa Aurora")),
            GuideMatch("b",slot("Etapa 4 - Tour de France",sport="Ciclismo")))
        val events=schedule(entries,listOf(channel("a"),channel("b"))).other
        assertEquals(setOf("Badminton","Ciclismo"),sportsHubCatalog(emptyList(),events,now).disciplines.map {it.title}.toSet())
    }

    @Test fun activePlaylistAndAdultBoundariesCannotLeakStreams() {
        val guide=Guide(matches=listOf(GuideMatch("a",slot("Alpha x Beta - NBA")),
            GuideMatch("b",slot("Gamma x Delta - NBA"))))
        assertEquals("Alpha",buildSportsSchedule(guide,listOf(channel("a")),now).other.single().home)
        assertEquals("Gamma",buildSportsSchedule(guide,listOf(channel("b")),now).other.single().home)
        val adult=Channel("NBA","https://example.test/adult","+18",tvgId="a",typeHint="live")
        assertTrue(buildSportsSchedule(guide,listOf(adult),now).other.isEmpty())
    }

    @Test fun searchFiltersAndDateHeadingsUseActualSchedulesAndSelectedZone() {
        val events=schedule(listOf(GuideMatch("a",slot("Alpha x Beta - NBA",hint=BroadcastHint.LIVE)),
            GuideMatch("b",slot("Gamma x Delta - NBA",start=now+86400000L))),listOf(channel("a"),channel("b"))).other
        val catalog=sportsHubCatalog(emptyList(),events,now)
        assertEquals(2,sportsHubProjection(catalog,"NBA",SportsHubFilter.ALL,now,emptyMap()).disciplines.single().count)
        assertEquals(1,sportsHubProjection(catalog,"",SportsHubFilter.LIVE,now,emptyMap()).disciplines.single().count)
        assertEquals(1,sportsHubProjection(catalog,"Gamma",SportsHubFilter.UPCOMING,now,emptyMap()).disciplines.single().count)
        val instant=xmltvTime("20261111013000 +0000")
        val zone=TimeZone.getTimeZone("America/Sao_Paulo")
        assertEquals("2026-11-10",sportsDateKey(instant,zone))
        assertEquals("22:30",footballTime(instant,Locale.ROOT,zone))
        assertEquals("TERÇA-FEIRA, 10 DE NOVEMBRO DE 2026",sportsDayHeading(instant,Locale.forLanguageTag("pt-BR"),zone))
    }

    @Test fun publicCalendarAndScoresCannotPopulateTheHubOrProveAChannel() {
        val events=(0 until 400).map {i->FootballScore("$i","bra.1","Brasileirão",now+i*1000,
            ScoreTeam("a$i",listOf("Home $i")),ScoreTeam("b$i",listOf("Away $i")),null,null,MatchState.SCHEDULED)}
        assertTrue(footballRelevantScores(emptyList(),events).isEmpty())
        assertTrue(footballListings(emptyList(),FootballFeed(events),FOOTBALL_GAMES,"",now).isEmpty())
        assertTrue(footballRequestDays(emptyList(),now).isEmpty())
        assertTrue(sportsHubCatalog(emptyList(),emptyList(),now).disciplines.isEmpty())
    }

    @Test fun scoreRequestsAreScopedToTheEpgDayInsteadOfAWeekOrSeason() {
        val channel=channel("a")
        val p=slot("Flamengo x Palmeiras - Brasileirão")
        val match=FootballMatch("a",p,listOf(channel),FootballDetails("Brasileirão","Flamengo","Palmeiras"))
        val expected=setOf(footballDateKey(now-15*60000L),footballDateKey(now+90*60000L))
        assertEquals(expected,footballRequestDays(listOf(match),now).toSet())
        val unrelated=(0 until 400).map {i->FootballScore("$i","bra.1","Brasileirão",now,
            ScoreTeam("a$i",listOf("Home $i")),ScoreTeam("b$i",listOf("Away $i")),null,null,MatchState.SCHEDULED)}
        val actual=unrelated.first().copy(id="actual",home=ScoreTeam("f",listOf("Flamengo")),away=ScoreTeam("p",listOf("Palmeiras")))
        assertEquals(listOf(actual),footballRelevantScores(listOf(match),unrelated+actual))
    }

    @Test fun largeCatalogUsesOnlyLivePlaylistChannelsAndBoundedGuideEvents() {
        val start=System.nanoTime()
        val channels=(0 until 100000).map {i->Channel("Movie $i","https://example.test/movie/$i.mp4","Filmes",typeHint="movie")}+channel("a")
        val p=slot("Alpha x Beta - NBA")
        val guide=Guide(matches=(0 until 20000).map {GuideMatch("unsubscribed$it",p)}+GuideMatch("a",p))
        val result=buildSportsSchedule(guide,channels,now)
        assertEquals(1,result.other.size);assertEquals(listOf(channel("a")),result.other.single().channels)
        println("Sports scale: 100001 channels, 20001 EPG slots, one linked event in ${(System.nanoTime()-start)/1000000} ms")
    }

    @Test fun partialGuideFailureKeepsFutureSessionsAndFreshCorrectionsReplaceOnlyTheirIntervals() {
        val old=slot("Alpha x Beta - NBA",start=now+3*86400000L)
        val other=slot("Gamma x Delta - NBA",start=now+4*86400000L)
        val cached=Guide(matches=listOf(GuideMatch("a",old),GuideMatch("b",other)))
        assertEquals(cached.matches.toSet(),mergeProviderGuide(cached,Guide(),Guide(),now).matches.toSet())
        val correction=old.copy(title="Alpha x Omega - NBA",start=old.start+900000L,end=old.end+900000L)
        val merged=mergeProviderGuide(cached,Guide(matches=listOf(GuideMatch("a",correction))),Guide(),now)
        assertEquals(setOf(correction,other),merged.matches.map {it.programme}.toSet())
    }

    @Test fun aCurrentNonFootballGuideDoesNotTriggerUnnecessaryProviderFallbacks() = kotlinx.coroutines.runBlocking {
        val p=slot("Alpha x Beta - NBA")
        val guide=Guide(matches=listOf(GuideMatch("a",p)))
        var apiCalls=0;var alternateCalls=0
        val result=recoverSportsGuide("https://example.test/list.m3u","https://example.test/guide.xml",
            listOf(channel("a")),Guide(),true,now,
            xml={_,_,_->GuideRead(guide,GuideAttempt(GuideSource.XMLTV,GuideStatus.READY,GuideCounts(1,1,1,1,1)))},
            api={_,_,_->apiCalls++;error("No fallback for an existing NBA transmission")},
            brazil={_,_->alternateCalls++;error("No alternate guide for current provider data")})
        assertEquals(0,apiCalls);assertEquals(0,alternateCalls)
        assertEquals(1,result.diagnostics.linked);assertEquals(GuideStatus.READY,result.diagnostics.status)
    }

    @Test fun rawReplayAndStructuredSportEvidenceCannotBeOverriddenByResultsOrEditorialText() {
        val p=slot("Alpha x Beta - NBA",hint=BroadcastHint.LIVE).copy(metadata=FootballMetadata(subtitle="Reprise"))
        assertFalse(transmissionIsLive(p,null,now))
        assertEquals(SportsBroadcastState.REPLAY,sportsBroadcastState(p,now))
        val explicit=slot("Alpha x Beta - Copa Aurora",sport="Badminton",competition="Copa Aurora")
            .copy(category="NBA",metadata=FootballMetadata(sport="Badminton",competition="Copa Aurora",description="Notícias da NBA"))
        val result=schedule(listOf(GuideMatch("a",explicit)),listOf(channel("a"))).other.single()
        assertEquals(SportKind.OTHER,result.sport);assertEquals("Badminton",result.disciplineName)
        assertNull(footballDetails("Pré-jogo: Flamengo x Palmeiras - Copa do Brasil"))
    }

    @Test fun boundedScoreCachePrefersTheNewObservationAndRejectsAnOldMatch() {
        val match=FootballMatch("a",slot("Flamengo x Palmeiras - Brasileirão"),listOf(channel("a")),
            FootballDetails("Brasileirão","Flamengo","Palmeiras"))
        val old=FootballScore("same","bra.1","Brasileirão",now,ScoreTeam("f",listOf("Flamengo")),
            ScoreTeam("p",listOf("Palmeiras")),0,0,MatchState.LIVE,checked=now-120000)
        val latest=old.copy(homeGoals=1,checked=now)
        assertEquals(listOf(latest),footballRelevantScores(listOf(match),listOf(old,latest)))
        assertNull(matchFootballScore(match,FootballFeed(listOf(latest.copy(kickoff=now-86400000L)))))
        assertFalse(footballHasLiveTransmission(match.copy(programme=match.programme.copy(start=now+3600000L)),latest,now))
    }

    @Test fun motorsportRowIndexPreservesChronologyDaysStagesAndDistinctRaces() {
        val entries=listOf(
            GuideMatch("a",slot("F2 - GP do Brasil - Corrida 1",start=now+3600000)),
            GuideMatch("b",slot("F2 - GP do Brasil - Corrida 2",start=now+5400000)),
            GuideMatch("c",slot("F2 - GP do Brasil - Classificação",start=now+86400000)))
        val events=schedule(entries,('a'..'c').map {channel(it.toString())}).other
        assertEquals(3,events.size)
        assertNotEquals(motorsportFixtureKey(entries[0].programme,"f2"),motorsportFixtureKey(entries[1].programme,"f2"))
        val catalog=sportsHubCatalog(emptyList(),events,now)
        val rows=sportsHubRows(catalog.disciplines.single().competitions.single().events,TimeZone.getTimeZone("UTC"))
        assertEquals(3,rows.filterIsInstance<SportsHubRow.Event>().size)
        assertEquals(rows.size,rows.map {it.key}.toSet().size)
        assertEquals(events.map {it.id},rows.filterIsInstance<SportsHubRow.Event>().map {it.event.other!!.id})
        assertTrue(rows.first() is SportsHubRow.Day)
    }

    @Test fun genericSportLabelsAreNotChampionshipsButShortExplicitAndStructuredTitlesAre() {
        val generic=slot("Alpha x Beta",sport="Basquete").copy(category="Basketball")
        assertEquals("Campeonato não informado",sportsCompetition(generic,SportKind.BASKETBALL).second)
        val named=generic.copy(metadata=generic.metadata.copy(competition="Copa Aurora"),category="NBA")
        assertEquals("Copa Aurora",sportsCompetition(named,SportKind.BASKETBALL).second)
        val title=slot("Alpha x Beta - Campeonato Japonês de Basquete")
        assertEquals("Campeonato Japonês de Basquete",sportsCompetition(title,SportKind.BASKETBALL).second)
        assertNotNull(footballDetails("Flamengo × Palmeiras - Copa do Brasil"))
    }
}
