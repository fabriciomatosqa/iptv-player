package br.com.fabriciomatos.iptvplayer

import org.json.JSONArray
import org.json.JSONObject

enum class FootballIncidentKind { GOAL, YELLOW_CARD, RED_CARD }
data class FootballIncident(val minute: String, val player: String, val kind: FootballIncidentKind, val isHome: Boolean)

/** Match events are attributed only to an exactly matched FotMob ID; ignore unknown incident types. */
internal fun parseFotMobIncidents(json: JSONObject, matchId: String): List<FootballIncident>? {
    if(json.optJSONObject("general")?.optString("matchId")!=matchId)return null
    val items=json.optJSONObject("content")?.optJSONObject("matchFacts")?.optJSONObject("events")
        ?.optJSONArray("events") ?: return null
    return buildList {
        for(i in 0 until minOf(items.length(),300)) {
            val item=items.optJSONObject(i) ?: continue
            val rawType=normalized(item.optString("type"))
            val card=normalized(item.optString("card")+" "+item.optString("cardDescription"))
            val kind=when {
                rawType=="goal" -> FootballIncidentKind.GOAL
                rawType.contains("redcard") || rawType=="red card" ||
                    (rawType=="card" && card.contains("red")) -> FootballIncidentKind.RED_CARD
                rawType.contains("yellowcard") || rawType=="yellow card" ||
                    (rawType=="card" && card.contains("yellow")) -> FootballIncidentKind.YELLOW_CARD
                else -> null
            } ?: continue
            val minute=item.optInt("time",-1)
            if(minute !in 0..150 || !item.has("isHome"))continue
            val player=item.optJSONObject("player")?.optString("name").orEmpty()
                .ifBlank {item.optString("nameStr")}.trim()
            if(player.length !in 2..100)continue
            val extra=item.optInt("overloadTime",0).takeIf {it in 1..30}
            add(FootballIncident("$minute'"+(extra?.let {" +$it"} ?: ""),player,kind,item.optBoolean("isHome")))
        }
    }
}
internal fun encodeFootballIncidents(events: List<FootballIncident>): JSONObject = JSONObject().put("events",JSONArray(events.map {
    JSONObject().put("minute",it.minute).put("player",it.player).put("kind",it.kind.name).put("home",it.isHome)
}))
internal fun decodeFootballIncidents(json: JSONObject): List<FootballIncident> {
    val events=json.getJSONArray("events")
    return (0 until minOf(events.length(),300)).mapNotNull {i->
        val row=events.optJSONObject(i) ?: return@mapNotNull null
        val kind=runCatching {FootballIncidentKind.valueOf(row.getString("kind"))}.getOrNull() ?: return@mapNotNull null
        val minute=row.optString("minute");val player=row.optString("player")
        if(minute.length !in 2..10 || player.length !in 2..100)return@mapNotNull null
        FootballIncident(minute,player,kind,row.optBoolean("home"))
    }
}
