package br.com.fabriciomatos.iptvplayer

import kotlin.math.abs

/** Discard unrelated daily fixtures before publishing a feed to Compose. */
fun footballRelevantScores(matches: List<FootballMatch>, events: List<FootballScore>): List<FootballScore> {
    fun pair(a: String, b: String) = listOf(footballScoreTeamKey(a), footballScoreTeamKey(b)).sorted().joinToString(":")
    val targets = matches.filter { it.channels.isNotEmpty() }.groupBy { pair(it.details.home, it.details.away) }
    if (targets.isEmpty()) return emptyList()
    return events.filter { event ->
        event.home.names.any { home -> event.away.names.any { away ->
            targets[pair(home, away)].orEmpty().any { match ->
                val leagues = footballScoreLeagues(match.details)
                val compatibleGender = event.gender != FootballGender.CONFLICT &&
                    (event.gender != FootballGender.WOMEN || match.details.gender == FootballGender.WOMEN) &&
                    (event.gender != FootballGender.MEN || match.details.gender != FootballGender.WOMEN)
                compatibleGender && match.details.ageGroup.isBlank() &&
                    (event.league in leagues || leagues.isEmpty() && !isCompetitionValue(match.details.competition)) &&
                    (abs(event.kickoff - match.programme.start) <= 90 * 60000L ||
                        footballExplicitReplay(match.programme) && event.state == MatchState.FINISHED &&
                            match.programme.start - event.kickoff in 0..7 * 86400000L)
            }
        } }
    }.groupBy { it.league + ":" + it.id }.values.map {it.maxBy(FootballScore::checked)}
}
