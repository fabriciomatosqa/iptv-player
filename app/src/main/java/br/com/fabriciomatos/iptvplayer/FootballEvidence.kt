package br.com.fabriciomatos.iptvplayer

/** Only reuse evidence for the same channel slot and exact pair of teams. Explicit new evidence wins. */
fun retainFootballEvidence(current: Programme, previous: Programme?): Programme {
    if(previous == null || current.start != previous.start) return current
    fun details(p: Programme) = footballDetails(p.title,p.category,p.metadata)
    val old = details(previous) ?: return current
    val fresh = details(current) ?: footballDetails(current.title,"Futebol",current.metadata) ?: return current
    fun teams(d: FootballDetails) = setOf(footballScoreTeamKey(d.home),footballScoreTeamKey(d.away))
    if(teams(old) != teams(fresh)) return current
    // A newly identified competition, division or gender is authoritative and must not inherit a conflicting fixture.
    if(fresh.competition.isNotBlank() && old.competition.isNotBlank() &&
        footballCompetitionIdentity(fresh.copy(gender=FootballGender.UNKNOWN)) != footballCompetitionIdentity(old.copy(gender=FootballGender.UNKNOWN))) return current
    val evidence = if(fresh.gender == FootballGender.UNKNOWN && old.gender != FootballGender.UNKNOWN)
        old.gender.takeIf { it == FootballGender.WOMEN || it == FootballGender.MEN }?.let { if(it == FootballGender.WOMEN) "feminino" else "masculino" }.orEmpty()
        else current.metadata.retainedEvidence
    return current.copy(metadata=current.metadata.copy(
        competition=current.metadata.competition.ifBlank { if(fresh.competition.isBlank()) old.competition else "" },
        retainedEvidence=evidence))
}
