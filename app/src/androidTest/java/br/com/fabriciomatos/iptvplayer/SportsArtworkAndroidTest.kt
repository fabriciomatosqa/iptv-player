package br.com.fabriciomatos.iptvplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SportsArtworkAndroidTest {
    private fun team(name:String, location:String, logo:String):JSONObject =
        JSONObject().put("team",JSONObject().put("displayName",name)
            .put("location",location).put("logos",JSONArray().put(JSONObject().put("href",logo))))

    @Test fun espnTeamCatalogFindsBothCollegeTeamBadgesAndKeepsSportIndependent() {
        val payload=JSONObject().put("sports",JSONArray().put(JSONObject().put("leagues",
            JSONArray().put(JSONObject().put("teams",JSONArray()
                .put(team("BYU Cougars","BYU","https://a.espncdn.com/byu.png"))
                .put(team("Notre Dame Fighting Irish","Notre Dame","https://a.espncdn.com/nd.png")))))))
        val bundle=sportsBundle(payload,JSONObject())
        val teams=bundle.getJSONArray("teams")
        assertEquals("https://a.espncdn.com/byu.png",selectSportsTeamBadge("BYU",teams))
        assertEquals("https://a.espncdn.com/nd.png",selectSportsTeamBadge("Notre Dame",teams))
        assertEquals("",selectSportsTeamBadge("Carolina Panthers",teams))
        assertEquals("",selectSportsTeamBadge("Baylor",teams))
    }

    @Test fun officialNhlTeamCatalogResolvesVegasAndTampaCrests() {
        val vegas="https://a.espncdn.com/i/teamlogos/nhl/500/vgk.png"
        val tampa="https://a.espncdn.com/i/teamlogos/nhl/500/tb.png"
        val payload=JSONObject().put("sports",JSONArray().put(JSONObject().put("leagues",
            JSONArray().put(JSONObject().put("teams",JSONArray()
                .put(team("Vegas Golden Knights","Vegas",vegas))
                .put(team("Tampa Bay Lightning","Tampa Bay",tampa)))))))
        val teams=sportsBundle(payload,JSONObject()).getJSONArray("teams")
        assertEquals(vegas,selectSportsTeamBadge("Vegas Golden Knights",teams))
        assertEquals(tampa,selectSportsTeamBadge("Tampa Bay Lightning",teams))
        assertEquals(listOf("hockey/nhl"),sportsBadgeEndpoints(SportKind.HOCKEY,"nhl"))
        assertTrue(sportsBadgeEndpoints(SportKind.HOCKEY,"khl").isEmpty())
    }

    @Test fun duplicateAmbiguousNamesAndWrongSportNeverProduceIncorrectEscudos() {
        val sameName=JSONArray()
            .put(JSONObject().put("names",JSONArray().put("Giants"))
                .put("badge","https://a.espncdn.com/football.png"))
            .put(JSONObject().put("names",JSONArray().put("Giants"))
                .put("badge","https://a.espncdn.com/baseball.png"))
        assertEquals("",selectSportsTeamBadge("Giants",sameName))
        val db=JSONObject().put("teams",JSONArray()
            .put(JSONObject().put("strSport","Baseball").put("strTeam","Giants")
                .put("strBadge","https://r2.thesportsdb.com/baseball.png")))
        assertEquals("",selectSportsDatabaseBadge("Giants","American Football",db))
        assertEquals("https://r2.thesportsdb.com/baseball.png",
            selectSportsDatabaseBadge("Giants","Baseball",db))
    }
}
