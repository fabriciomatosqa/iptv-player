package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballIncidentsTest {
    @Test fun parsesRealisticGoalsAndCardsWithoutInventingUnreportedIncidents() {
        val json=JSONObject("""
            {"general":{"matchId":"9876"},
             "content":{"matchFacts":{"events":{"events":[
               {"type":"Goal","time":23,"player":{"name":"Jogador Um"},"isHome":true},
               {"type":"Card","card":"Yellow","time":40,"nameStr":"Jogador Dois","isHome":false},
               {"type":"Card","card":"Red","time":72,"overloadTime":2,"player":{"name":"Jogador Três"},"isHome":true},
               {"type":"Substitution","time":60,"player":{"name":"Reserva"},"isHome":true}
             ]}}}}
        """.trimIndent())
        val incidents=parseFotMobIncidents(json,"9876")!!
        assertEquals(3,incidents.size)
        assertEquals(FootballIncident("23'","Jogador Um",FootballIncidentKind.GOAL,true),incidents[0])
        assertEquals(FootballIncident("40'","Jogador Dois",FootballIncidentKind.YELLOW_CARD,false),incidents[1])
        assertEquals(FootballIncident("72' +2","Jogador Três",FootballIncidentKind.RED_CARD,true),incidents[2])
        assertNull(parseFotMobIncidents(json,"9877"))
        assertEquals(incidents,decodeFootballIncidents(encodeFootballIncidents(incidents)))
    }
}
