package br.com.fabriciomatos.iptvplayer

import org.json.JSONObject

fun encodeFootballMetadata(value: FootballMetadata): String = JSONObject().apply {
    put("title",value.originalTitle);put("subtitle",value.subtitle);put("description",value.description)
    put("competition",value.competition);put("gender",value.gender);put("country",value.country)
    put("division",value.division);put("sport",value.sport);put("retainedEvidence",value.retainedEvidence)
}.toString()
fun decodeFootballMetadata(value: String): FootballMetadata = runCatching {
    val json=JSONObject(value)
    FootballMetadata(json.optString("title"),json.optString("subtitle"),json.optString("description"),
        json.optString("competition"),json.optString("gender"),json.optString("country"),
        json.optString("division"),json.optString("sport"),json.optString("retainedEvidence"))
}.getOrDefault(FootballMetadata())
