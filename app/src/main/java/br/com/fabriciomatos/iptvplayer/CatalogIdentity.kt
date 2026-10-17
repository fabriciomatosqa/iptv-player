package br.com.fabriciomatos.iptvplayer

/** This provider keeps its 64-character URL-safe stream token when rotating CDN hosts.
 * Keep the full path, format, query and fragment. Never merge by title or logo. */
private val rotatingPlayPath = Regex("/play/[A-Za-z0-9_-]{64}(?:/(?:ts|m3u8))?(?:[?#].*)?")

internal fun catalogContentKey(url: String): String {
    val scheme = url.indexOf("://")
    val start = if (scheme >= 0) url.indexOf('/', scheme + 3) else -1
    val path = if (start >= 0) url.substring(start) else ""
    return if ((url.startsWith("http://", true) || url.startsWith("https://", true)) && rotatingPlayPath.matches(path)) "play:$path" else "url:$url"
}

/** SQL equivalent for the one-time, disk-based migration of million-row caches. */
internal fun catalogContentKeySql(column: String): String {
    val authority = "substr($column,instr($column,'://')+3)"
    val path = "substr($authority,instr($authority,'/'))"
    val token = "substr($path,7,64)"
    val suffix = "substr($path,71)"
    val endings = listOf("", "/ts", "/m3u8").flatMap { end ->
        listOf("$suffix='$end'", "substr($suffix,1,${end.length+1})='$end?'", "substr($suffix,1,${end.length+1})='$end#'")
    }.joinToString(" OR ")
    return "CASE WHEN (lower($column) LIKE 'http://%' OR lower($column) LIKE 'https://%') AND instr($authority,'/')>0 AND substr($path,1,6)='/play/' AND length($token)=64 AND $token NOT GLOB '*[^A-Za-z0-9_-]*' AND ($endings) THEN 'play:'||$path ELSE 'url:'||$column END"
}
