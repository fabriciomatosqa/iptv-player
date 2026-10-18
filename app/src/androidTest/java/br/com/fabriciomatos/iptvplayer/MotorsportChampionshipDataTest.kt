package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorsportChampionshipDataTest {
    private fun schedule()=JSONObject("""{"series":"f1","total":"2","offset":"0","RaceTable":{"season":"2026","Races":[
        {"season":"2026","round":"2","raceName":"Future GP","date":"2026-08-02","Circuit":{"circuitName":"Circuit B","Location":{"country":"B"}},"Sprint":{"date":"2026-08-01","time":"10:00:00Z"}},
        {"season":"2026","round":"1","raceName":"Example GP","date":"2026-03-01","time":"15:00:00Z","FirstPractice":{"date":"2026-02-27","time":"10:00:00Z"},"Circuit":{"circuitName":"Circuit A","Location":{"country":"A"}}}
    ]}}""")
    private fun drivers()=JSONObject("""{"series":"f1","total":"2","offset":"0","StandingsTable":{"StandingsLists":[{"season":"2026","round":"1","DriverStandings":[
        {"position":"2","points":"18.5","wins":"0","Driver":{"driverId":"b","givenName":"Driver","familyName":"B"},"Constructors":[{"name":"Team B"}]},
        {"position":"1","points":"25","wins":"1","Driver":{"driverId":"a","givenName":"Driver","familyName":"A"},"Constructors":[{"name":"Team A"}]}
    ]}]}}""")
    private fun podium(position: Int)=JSONObject("""{"series":"f1","total":"1","offset":"0","RaceTable":{"season":"2026","Races":[
        {"season":"2026","round":"1","Results":[{"position":"$position","Driver":{"driverId":"${if(position==1)"a" else if(position==2)"b" else "c"}"}}]}
    ]}}""")

    @Test fun parsesOfficialOrderCalendarSprintAndCompletePodiums() {
        val season=parseChampionshipSeason(2026,schedule(),drivers(),null,(1..3).map(::podium))
        assertEquals(listOf(1,2),season.races.map { it.round })
        assertEquals(listOf("a","b"),season.drivers.map { it.id })
        assertEquals(18.5,season.drivers[1].points,0.0)
        assertEquals(1,season.drivers.first().podiums)
        assertTrue(season.races.first().completed)
        assertFalse(season.races.last().completed)
        assertNull(season.races.last().start)
        assertEquals(listOf("sprint","race"),season.races.last().sessions.map { it.id })
        assertTrue(season.teams.isEmpty())
    }

    @Test fun partialPodiumsAndWrongSeasonsNeverProduceFabricatedStatistics() {
        val season=parseChampionshipSeason(2026,schedule(),drivers(),null,listOf(podium(1),null,podium(3)),true)
        assertTrue(season.partial)
        assertTrue(season.drivers.all { it.podiums==null })
        assertTrue(parseChampionshipSeason(2027,schedule(),drivers(),null,(1..3).map(::podium)).races.isEmpty())
        assertTrue(parseChampionshipSeason(2027,schedule(),drivers(),null,(1..3).map(::podium)).drivers.isEmpty())
    }

    @Test fun cacheAvoidsRepeatedRequestsAndSurvivesNetworkFailures() = runBlocking {
        val store=mutableMapOf<String,String>()
        var now=1800000000000L
        var calls=0
        var offline=false
        fun repository()=MotorsportChampionshipRepository(store::get,{ key,value -> store[key]=value },{
            calls++
            if(offline)error("Offline")
            val body=when {
                "driverStandings" in it -> drivers()
                "constructorStandings" in it -> JSONObject("""{"series":"f1","total":"0","offset":"0","StandingsTable":{"StandingsLists":[]}}""")
                "results/" in it -> podium(it.substringAfter("results/").take(1).toInt())
                else -> schedule()
            }
            JSONObject().put("MRData",body)
        },{now})
        val repo=repository()
        assertEquals(2,repo.load(2026).races.size)
        assertEquals(6,calls)
        assertFalse(repo.load(2026).partial)
        assertEquals(6,calls)
        now+=7*3600000L;offline=true
        val stale=repo.load(2026)
        assertTrue(stale.partial)
        assertEquals(2,stale.races.size)
        assertEquals(12,calls)
        repository().load(2026) // persisted failure backoff also applies after reopening
        assertEquals(12,calls)
    }

    @Test fun emptyOrFailedResponsesStayUnavailable() = runBlocking {
        val repo=MotorsportChampionshipRepository({null},{_,_->},{error("Unavailable")})
        val season=repo.load(2026)
        assertTrue(season.partial)
        assertTrue(season.drivers.isEmpty())
        assertTrue(season.races.isEmpty())
    }
}
