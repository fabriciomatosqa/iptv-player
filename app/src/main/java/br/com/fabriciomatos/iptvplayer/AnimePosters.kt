package br.com.fabriciomatos.iptvplayer

/** Verified anime covers for titles whose supplied artwork is known to be unrelated. */
fun knownAnimePoster(title: String): String? = when (canonicalAnimeTitle(title)) {
    "black clover" -> "https://image.tmdb.org/t/p/w780/p3rUhlE81nWxPqpPR8F2u7a01Tl.jpg"
    "assassination classroom" -> "https://image.tmdb.org/t/p/w780/fIOYLPOorKvP4wut9y0edpnWROz.jpg"
    else -> null
}
fun isAnimationGenre(genre: String) = Regex("\\b(animacao|animation|anime)\\b").containsMatchIn(normalized(genre))
fun posterAlias(title: String) = normalized(title.replace(Regex("\\s*[\\[(](?:19|20)\\d{2}[\\])]\\s*$"), ""))
