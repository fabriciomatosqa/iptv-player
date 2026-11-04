package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrazilianFootballBadgeAndroidTest {
    private fun entry(name:String,sport:String="Soccer",country:String="Brazil",badge:String="https://example.test/badge.png") =
        JSONObject().put("strTeam",name).put("strSport",sport).put("strCountry",country).put("strBadge",badge)
    @Test fun brazilianSoccerOnlyAndExactNames() {
        val good=JSONObject().put("teams",JSONArray().put(entry("Vila Nova")))
        assertEquals("https://example.test/badge.png",selectBrazilianFootballBadge("Vila Nova",good))
        val falseBadges=JSONObject().put("teams",JSONArray().put(entry("Vila Nova",country="Portugal"))
            .put(entry("Vila Nova",sport="Basketball")).put(entry("Vila Nova Futebol Clube")))
        assertEquals("",selectBrazilianFootballBadge("Vila Nova",falseBadges))
    }
}
