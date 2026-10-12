package br.com.fabriciomatos.iptvplayer

const val FIFA_BADGE = "https://a.espncdn.com/i/leaguelogos/soccer/500/4.png"

data class NationalTeam(val country: String, val aliases: Set<String>, val badge: String = "")

// Federation crests, not the country flags used by some sports feeds.
private val nationalTeams = listOf(
    NationalTeam("Brazil", setOf("Brasil", "Brazil", "BRA"), "https://r2.thesportsdb.com/images/media/team/badge/jl6dip1726167280.png"),
    NationalTeam("Argentina", setOf("Argentina", "ARG"), "https://r2.thesportsdb.com/images/media/team/badge/3zplhu1726167477.png"),
    NationalTeam("USA", setOf("Estados Unidos", "Estados Unidos da América", "United States", "United States of America", "USA", "EUA", "US"), "https://r2.thesportsdb.com/images/media/team/badge/21f0oi1597948195.png"),
    NationalTeam("Spain", setOf("Espanha", "Spain", "España", "ESP"), "https://r2.thesportsdb.com/images/media/team/badge/ncgqyr1726166942.png"),
    NationalTeam("Portugal", setOf("Portugal", "POR")), NationalTeam("France", setOf("França", "France", "FRA")),
    NationalTeam("Germany", setOf("Alemanha", "Germany", "GER")), NationalTeam("Italy", setOf("Itália", "Italy", "ITA")),
    NationalTeam("England", setOf("Inglaterra", "England", "ENG")), NationalTeam("Netherlands", setOf("Holanda", "Países Baixos", "Netherlands", "NED")),
    NationalTeam("Belgium", setOf("Bélgica", "Belgium", "BEL")), NationalTeam("Croatia", setOf("Croácia", "Croatia", "CRO")),
    NationalTeam("Uruguay", setOf("Uruguai", "Uruguay", "URU")), NationalTeam("Colombia", setOf("Colômbia", "Colombia", "COL")),
    NationalTeam("Chile", setOf("Chile", "CHI")), NationalTeam("Paraguay", setOf("Paraguai", "Paraguay", "PAR")),
    NationalTeam("Ecuador", setOf("Equador", "Ecuador", "ECU")), NationalTeam("Peru", setOf("Peru", "PER")),
    NationalTeam("Bolivia", setOf("Bolívia", "Bolivia", "BOL")), NationalTeam("Venezuela", setOf("Venezuela", "VEN")),
    NationalTeam("Mexico", setOf("México", "Mexico", "MEX")), NationalTeam("Canada", setOf("Canadá", "Canada", "CAN")),
    NationalTeam("Costa Rica", setOf("Costa Rica", "CRC")), NationalTeam("Panama", setOf("Panamá", "Panama", "PAN")),
    NationalTeam("Jamaica", setOf("Jamaica", "JAM")), NationalTeam("Honduras", setOf("Honduras", "HON")),
    NationalTeam("Japan", setOf("Japão", "Japan", "JPN")), NationalTeam("South Korea", setOf("Coreia do Sul", "South Korea", "Korea Republic", "KOR")),
    NationalTeam("China", setOf("China", "China PR", "CHN")), NationalTeam("Australia", setOf("Austrália", "Australia", "AUS")),
    NationalTeam("New Zealand", setOf("Nova Zelândia", "New Zealand", "NZL")), NationalTeam("Iran", setOf("Irã", "Irão", "Iran", "IRN")),
    NationalTeam("Saudi Arabia", setOf("Arábia Saudita", "Saudi Arabia", "KSA")), NationalTeam("Qatar", setOf("Catar", "Qatar", "QAT")),
    NationalTeam("United Arab Emirates", setOf("Emirados Árabes Unidos", "United Arab Emirates", "UAE")), NationalTeam("Iraq", setOf("Iraque", "Iraq", "IRQ")),
    NationalTeam("Morocco", setOf("Marrocos", "Morocco", "MAR")), NationalTeam("Egypt", setOf("Egito", "Egypt", "EGY")),
    NationalTeam("Senegal", setOf("Senegal", "SEN")), NationalTeam("Ghana", setOf("Gana", "Ghana", "GHA")),
    NationalTeam("Nigeria", setOf("Nigéria", "Nigeria", "NGA")), NationalTeam("Cameroon", setOf("Camarões", "Cameroon", "CMR")),
    NationalTeam("South Africa", setOf("África do Sul", "South Africa", "RSA")), NationalTeam("Ivory Coast", setOf("Costa do Marfim", "Ivory Coast", "Côte d'Ivoire", "CIV")),
    NationalTeam("Algeria", setOf("Argélia", "Algeria", "ALG")), NationalTeam("Tunisia", setOf("Tunísia", "Tunisia", "TUN")),
    NationalTeam("Angola", setOf("Angola", "ANG")), NationalTeam("Cape Verde", setOf("Cabo Verde", "Cape Verde", "CPV")),
    NationalTeam("Switzerland", setOf("Suíça", "Switzerland", "SUI")), NationalTeam("Austria", setOf("Áustria", "Austria", "AUT")),
    NationalTeam("Denmark", setOf("Dinamarca", "Denmark", "DEN")), NationalTeam("Sweden", setOf("Suécia", "Sweden", "SWE")),
    NationalTeam("Norway", setOf("Noruega", "Norway", "NOR")), NationalTeam("Finland", setOf("Finlândia", "Finland", "FIN")),
    NationalTeam("Poland", setOf("Polônia", "Polónia", "Poland", "POL")), NationalTeam("Serbia", setOf("Sérvia", "Serbia", "SRB")),
    NationalTeam("Ukraine", setOf("Ucrânia", "Ukraine", "UKR")), NationalTeam("Turkey", setOf("Turquia", "Turkey", "Türkiye", "TUR")),
    NationalTeam("Greece", setOf("Grécia", "Greece", "GRE")), NationalTeam("Scotland", setOf("Escócia", "Scotland", "SCO")),
    NationalTeam("Wales", setOf("País de Gales", "Wales", "WAL")), NationalTeam("Ireland", setOf("Irlanda", "Ireland", "Republic of Ireland", "IRL")),
    NationalTeam("Northern Ireland", setOf("Irlanda do Norte", "Northern Ireland", "NIR")), NationalTeam("Czech Republic", setOf("República Tcheca", "República Checa", "Chéquia", "Czech Republic", "Czechia", "CZE")),
    NationalTeam("Romania", setOf("Romênia", "Roménia", "Romania", "ROU")), NationalTeam("Hungary", setOf("Hungria", "Hungary", "HUN")),
    NationalTeam("Slovakia", setOf("Eslováquia", "Slovakia", "SVK")), NationalTeam("Slovenia", setOf("Eslovênia", "Eslovénia", "Slovenia", "SVN")),
    NationalTeam("Albania", setOf("Albânia", "Albania", "ALB")), NationalTeam("Bosnia and Herzegovina", setOf("Bósnia", "Bósnia e Herzegovina", "Bosnia and Herzegovina", "BIH")),
    NationalTeam("Russia", setOf("Rússia", "Russia", "RUS")), NationalTeam("Israel", setOf("Israel", "ISR"))
)

private fun nationalKey(name: String): String = normalized(name)
    .replace(Regex("^selecao(?:\\s+(?:de|da|do))?\\s+"), "")
    .replace(Regex("(?:\\s*(?:\\[|\\()?\\s*(?:feminin[oa]|masculin[oa]|women(?:'s)?|olimpic[oa]|sub[- ]?\\d{2}|u[- ]?\\d{2}|under[- ]?\\d{2})(?:\\]|\\))?)+$"), "")
    .trim()
private val nationalAliases = nationalTeams.flatMap { team -> team.aliases.map { nationalKey(it) to team } }.toMap()
fun nationalTeam(name: String): NationalTeam? = nationalAliases[nationalKey(name)]
fun nationalMatch(details: FootballDetails): Pair<NationalTeam, NationalTeam>? {
    val home=nationalTeam(details.home) ?: return null
    val away=nationalTeam(details.away) ?: return null
    return home to away
}
fun initialFootballArtwork(details: FootballDetails): FootballArtwork = nationalMatch(details)?.let {
    FootballArtwork(FIFA_BADGE,it.first.badge,it.second.badge)
} ?: FootballArtwork()
