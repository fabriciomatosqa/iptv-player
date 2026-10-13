package br.com.fabriciomatos.iptvplayer

import org.json.JSONObject

/** Stable competition IDs; country-specific names must not collide (e.g. the many Premier Leagues). */
private fun fotmobLeague(row: JSONObject): String {
    val name=normalized(row.optString("name"));val country=row.optString("ccode")
    return when(row.optInt("primaryId",row.optInt("id"))) {
        47->"eng.1";48->"eng.2";87->"esp.1";140->"esp.2";55->"ita.1";54->"ger.1";53->"fra.1"
        61->"por.1";57->"ned.1";71->"tur.1";268->"bra.1";8814->"bra.2";42->"uefa.champions"
        525->"afc.champions";73->"uefa.europa";112->"arg.1";130->"usa.1";230->"mex.1"
        else->when {
            name.contains("libertadores")->"conmebol.libertadores"
            name.contains("sudamericana")->"conmebol.sudamericana"
            name.contains("conference league")->"uefa.europa.conf"
            country=="BRA" && name.contains("copa do brasil")->"bra.copa_do_brazil"
            country=="BRA" && name.contains("paulista")->"bra.camp.paulista"
            country=="BRA" && name.contains("carioca")->"bra.camp.carioca"
            country=="INT" && (name=="friendlies" || name.contains("international friendl"))->"fifa.friendly"
            country=="INT" && name.contains("women") && name.contains("friendl")->"fifa.friendly.w"
            country=="INT" && name=="world cup"->"fifa.world"
            country=="INT" && name=="women s world cup"->"fifa.wwc"
            name.contains("world cup qualification") && !name.contains("women")->when {
                name.contains("uefa")->"fifa.worldq.uefa";name.contains("conmebol")->"fifa.worldq.conmebol"
                name.contains("concacaf")->"fifa.worldq.concacaf";name.contains("afc")->"fifa.worldq.afc"
                name.contains("caf")->"fifa.worldq.caf";name.contains("ofc")->"fifa.worldq.ofc";else->"fotmob:${row.optInt("primaryId",row.optInt("id"))}"
            }
            else->"fotmob:${row.optInt("primaryId",row.optInt("id"))}"
        }
    }
}

fun parseFotMobScores(json: JSONObject, checked: Long): List<FootballScore> {
    val leagues=json.optJSONArray("leagues") ?: error("Invalid football feed")
    return buildList {
        for(i in 0 until leagues.length()) {
            val league=leagues.optJSONObject(i) ?: continue
            val matches=league.optJSONArray("matches") ?: continue
            for(j in 0 until matches.length()) {
                val match=matches.optJSONObject(j) ?: continue
                val status=match.optJSONObject("status") ?: continue
                val home=match.optJSONObject("home") ?: continue;val away=match.optJSONObject("away") ?: continue
                val id=match.optString("id");val kickoff=footballIsoDate(status.optString("utcTime"))
                if(id.isBlank() || kickoff==0L)continue
                val reason=status.optJSONObject("reason");val key=reason?.optString("longKey").orEmpty()
                val live=status.optJSONObject("liveTime")
                val state=when {
                    status.optBoolean("cancelled") || key.contains("cancel",true)->MatchState.CANCELLED
                    key.contains("postpon",true) || key.contains("suspend",true) || key.contains("abandon",true)->MatchState.POSTPONED
                    status.optBoolean("finished")->MatchState.FINISHED
                    key.contains("halftime",true) || reason?.optString("short")=="HT" || live?.optString("short")=="HT"->MatchState.HALFTIME
                    status.optBoolean("started")->MatchState.LIVE
                    else->MatchState.SCHEDULED
                }
                fun team(row: JSONObject)=ScoreTeam(row.optString("id"),listOf("name","shortName","longName").map {row.optString(it)}.filter(String::isNotBlank).distinct())
                fun goals(row: JSONObject)=if(state in listOf(MatchState.LIVE,MatchState.HALFTIME,MatchState.FINISHED))
                    row.optString("score").toIntOrNull()?.takeIf {it in 0..99}else null
                val h=team(home);val a=team(away);if(h.names.isEmpty() || a.names.isEmpty())continue
                val clock=live?.optString("long").orEmpty().ifBlank {live?.optString("short").orEmpty()}
                add(FootballScore("fotmob:$id",fotmobLeague(league),league.optString("name"),kickoff,h,a,goals(home),goals(away),state,clock,checked))
            }
        }
    }
}
