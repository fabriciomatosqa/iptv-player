package br.com.fabriciomatos.iptvplayer

/**
 * Team badge providers are selected by sport, never just by a team name:
 * "Giants" must not return a baseball logo for an American football matchup.
 */
fun sportsBadgeEndpoints(sport: SportKind, competitionId: String): List<String> = when(sport) {
    SportKind.AMERICAN_FOOTBALL -> when(competitionId) {
        "nfl" -> listOf("football/nfl")
        "ncaa.fb" -> listOf("football/college-football")
        else -> listOf("football/college-football", "football/nfl")
    }
    SportKind.BASKETBALL -> when(competitionId) {
        "nba" -> listOf("basketball/nba")
        "wnba" -> listOf("basketball/wnba")
        else -> listOf("basketball/nba", "basketball/wnba", "basketball/mens-college-basketball")
    }
    SportKind.BASEBALL -> when(competitionId) {
        "mlb" -> listOf("baseball/mlb")
        else -> listOf("baseball/mlb", "baseball/college-baseball")
    }
    SportKind.HOCKEY -> listOf("hockey/nhl")
    // Tennis/motorsport are not team-versus-team contests.
    else -> emptyList()
}

fun sportsDatabaseCategory(sport: SportKind): String? = when(sport) {
    SportKind.AMERICAN_FOOTBALL -> "American Football"
    SportKind.BASKETBALL -> "Basketball"
    SportKind.BASEBALL -> "Baseball"
    SportKind.HOCKEY -> "Ice Hockey"
    SportKind.VOLLEYBALL -> "Volleyball"
    SportKind.HANDBALL -> "Handball"
    else -> null
}

/** Strict names first; allow a unique college nickname suffix: BYU == BYU Cougars. */
fun sportsTeamAliasMatch(request: String, candidate: String): Boolean {
    val team=teamKey(request)
    val alias=teamKey(candidate)
    if(team.isBlank() || alias.isBlank())return false
    if(team==alias)return true
    if(team.length<3 || !alias.startsWith("$team "))return false
    val suffix=alias.removePrefix("$team ").split(' ')
    return suffix.size in 1..3 && suffix.all(String::isNotBlank)
}
