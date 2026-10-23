package br.com.fabriciomatos.iptvplayer

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Only called after an exact film match against its Portuguese Wikipedia page.
 * Wikidata's ptwiki sitelink then identifies the film unambiguously; P161 gives
 * cast member identities and P18 their photos (when available).
 * No IPTV provider URL, account data or stream identifier leaves the device.
 */
internal fun wikidataFilmCast(
    verifiedWikipediaTitle: String,
    request: (String)->JSONObject
): List<CastMember> {
    if (verifiedWikipediaTitle.length !in 3..150) return emptyList()
    val title=URLEncoder.encode(verifiedWikipediaTitle,"UTF-8")
    val movie = request("https://www.wikidata.org/w/api.php?action=wbgetentities" +
        "&sites=ptwiki&titles=$title&props=claims&format=json")
    val films=movie.optJSONObject("entities") ?: return emptyList()
    if(films.length()!=1) return emptyList()
    val info=films.optJSONObject(films.keys().next()) ?: return emptyList()
    val claims=info.optJSONObject("claims")?.optJSONArray("P161") ?: return emptyList()
    val actors=(0 until minOf(claims.length(),24)).mapNotNull { i ->
        val item=claims.optJSONObject(i)?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")?.optJSONObject("value")
        item?.optString("id")?.takeIf { it.matches(Regex("Q[0-9]+")) }
    }.distinct()
    if(actors.isEmpty())return emptyList()
    val ids=actors.joinToString("|")
    val data=request("https://www.wikidata.org/w/api.php?action=wbgetentities" +
        "&ids=${URLEncoder.encode(ids,"UTF-8")}&props=labels%7Cclaims" +
        "&languages=pt%7Cen&format=json").optJSONObject("entities") ?: return emptyList()
    return actors.mapNotNull { id ->
        val person=data.optJSONObject(id) ?: return@mapNotNull null
        val labels=person.optJSONObject("labels") ?: return@mapNotNull null
        val name=labels.optJSONObject("pt")?.optString("value")
            .orEmpty().ifBlank {labels.optJSONObject("en")?.optString("value").orEmpty()}.trim()
        if(name.length !in 4..100) return@mapNotNull null
        val file=person.optJSONObject("claims")?.optJSONArray("P18")
            ?.optJSONObject(0)?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")?.optString("value").orEmpty()
        val image=if(file.isNotBlank() && !file.contains('/'))
            "https://commons.wikimedia.org/wiki/Special:FilePath/" +
                URLEncoder.encode(file.replace(' ','_'),"UTF-8") + "?width=185"
        else ""
        CastMember(name,image)
    }.distinctBy {normalized(it.name)}
}
