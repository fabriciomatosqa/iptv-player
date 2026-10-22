package br.com.fabriciomatos.iptvplayer

import java.net.URI

data class EpisodeSynopsis(val id: String, val season: Int, val number: Int, val title: String, val plot: String)
data class CastMember(val name: String, val imageUrl: String = "")

/** Optional provider metadata. Old cached JSON remains readable with empty defaults. */
data class ContentDetails(
    val plot: String = "",
    val episodes: List<EpisodeSynopsis> = emptyList(),
    val backdropUrl: String = "",
    val cast: List<CastMember> = emptyList(),
    val year: String = "",
    val duration: String = "",
    val genre: String = "",
    val rating: String = "",
    val director: String = ""
)
data class Synopsis(
    val plot: String = "",
    val episode: EpisodeSynopsis? = null,
    val failed: Boolean = false,
    val backdropUrl: String = "",
    val cast: List<CastMember> = emptyList(),
    val year: String = "",
    val duration: String = "",
    val genre: String = "",
    val rating: String = "",
    val director: String = ""
)

fun playbackContentId(address: String): String? = runCatching {
    URI(address).path.substringAfterLast('/').substringBefore('.').takeIf {it.matches(Regex("[0-9]+"))}
}.getOrNull()

fun selectEpisodeSynopsis(details: ContentDetails, channel: Channel): EpisodeSynopsis? {
    val id=playbackContentId(channel.url)
    if(id!=null)details.episodes.singleOrNull {it.id==id}?.let {return it}
    val episode=seriesIdentity(channel).episode
    if(episode.number<=0)return null
    val matches=details.episodes.filter {it.season==episode.season && it.number==episode.number}
    // Multiple versions can share a synopsis; disagreeing records must not attach the wrong plot.
    if(matches.map {it.plot}.distinct().size!=1)return null
    return matches.firstOrNull()
}
