package br.com.fabriciomatos.iptvplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Jolpica/Ergast: public F1 data only. Never receives playlist URLs, credentials or channel names. */
internal class MotorsportChampionshipRepository(
    private val cached: (String) -> String?,
    private val save: (String,String) -> Unit,
    private val read: (String) -> JSONObject = ::readChampionshipJson,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock=Mutex()
    private val attempts=mutableMapOf<String,Long>()
    private var lastRequest=0L

    // The shared artwork cache retains payloads for offline fallback; freshness is independent.
    private suspend fun payload(path: String): Pair<JSONObject?,Boolean> {
        val key="motorsport-jolpica-v1:$path"
        val stored=runCatching { cached(key)?.let(::JSONObject) }.getOrNull()
        val now=clock()
        val stamp=stored?.optLong("checked") ?: 0L
        if(stored!=null && now-stamp in 0 until 6*3600000L)
            return stored.optJSONObject("data") to false
        // Also persist failed-attempt backoff, so reopening the screen cannot hammer the service.
        val attempted=maxOf(attempts[path] ?: 0L,stored?.optLong("attempted") ?: 0L)
        if(now-attempted in 0 until 15*60000L)
            return stored?.optJSONObject("data") to true
        delay((300L-(now-lastRequest)).coerceAtLeast(0))
        attempts[path]=clock();lastRequest=clock()
        return try {
            val data=read("https://api.jolpi.ca/ergast/f1/$path?limit=100").getJSONObject("MRData")
            check(data.optString("series")=="f1")
            check(data.getInt("total")<=100 && data.optInt("offset")==0)
            data.put("_auroraChecked",clock())
            save(key,JSONObject().put("checked",clock()).put("attempted",clock()).put("data",data).toString())
            data to false
        } catch(e: CancellationException) { throw e } catch(_: Exception) {
            runCatching { save(key,(stored ?: JSONObject()).put("attempted",clock()).toString()) }
            stored?.optJSONObject("data") to true
        }
    }

    suspend fun load(year: Int): ChampionshipSeason = withContext(Dispatchers.IO) { lock.withLock {
        require(year in 1950..2200)
        val schedule=payload("$year/")
        val drivers=payload("$year/driverStandings/")
        val teams=payload("$year/constructorStandings/")
        // Position-filtered results fit in one page per podium position, even for a whole season.
        val podiums=(1..3).map { payload("$year/results/$it/") }
        parseChampionshipSeason(year,schedule.first,drivers.first,teams.first,podiums.map { it.first },
            listOf(schedule,drivers,teams).plus(podiums).any { it.second })
    } }

    suspend fun results(year: Int, round: Int): List<ChampionshipStanding>? = withContext(Dispatchers.IO) { lock.withLock {
        require(year in 1950..2200 && round in 1..100)
        val response=payload("$year/$round/results/").first ?: return@withLock null
        val table=response.optJSONObject("RaceTable") ?: return@withLock null
        if(table.optInt("season")!=year || table.optInt("round")!=round) return@withLock null
        val rows=table.optJSONArray("Races")?.optJSONObject(0)?.optJSONArray("Results")
            ?: return@withLock emptyList()
        rows.objects().mapNotNull { row ->
            val driver=row.optJSONObject("Driver") ?: return@mapNotNull null
            val position=row.optInt("position",0)
            if(position<=0) return@mapNotNull null
            ChampionshipStanding(driver.optString("driverId"),position,driverName(driver),
                row.optJSONObject("Constructor")?.optString("name").orEmpty(),
                row.optDouble("points",0.0),null)
        }.sortedBy { it.position }
    } }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun driverName(driver: JSONObject) = listOf(driver.optString("givenName"),driver.optString("familyName"))
    .filter(String::isNotBlank).joinToString(" ")

internal fun parseChampionshipSeason(
    year: Int, schedule: JSONObject?, drivers: JSONObject?, teams: JSONObject?,
    podiums: List<JSONObject?>, partial: Boolean = false
): ChampionshipSeason {
    fun standings(data: JSONObject?): JSONObject? = data?.optJSONObject("StandingsTable")
        ?.optJSONArray("StandingsLists")?.objects()?.singleOrNull { it.optInt("season")==year }
    fun resultRaces(data: JSONObject?): List<JSONObject> = data?.optJSONObject("RaceTable")
        ?.optJSONArray("Races")?.objects().orEmpty().filter { it.optInt("season")==year }
    val driverList=standings(drivers)
    val teamList=standings(teams)
    val winnerRaces=resultRaces(podiums.getOrNull(0)).filter {
        it.optJSONArray("Results")?.objects()?.any { row ->
            row.optInt("position")==1 && row.optJSONObject("Driver")?.optString("driverId").orEmpty().isNotBlank()
        }==true
    }
    val completed=winnerRaces.map { it.optInt("round") }.toSet()
    val driverRows=driverList?.optJSONArray("DriverStandings")?.objects().orEmpty()
    val podiumComplete=podiums.size==3 && podiums.all { it!=null } &&
        podiums.all { resultRaces(it).map { race -> race.optInt("round") }.toSet()==completed } &&
        driverRows.isNotEmpty() && driverRows.all { it.has("wins") } &&
        driverRows.sumOf { it.optInt("wins") }==completed.size &&
        driverList?.optInt("round")==completed.maxOrNull()
    val podiumCounts=podiums.flatMap { resultRaces(it) }.flatMap {
        it.optJSONArray("Results")?.objects().orEmpty()
    }.mapNotNull { it.optJSONObject("Driver")?.optString("driverId") }.groupingBy { it }.eachCount()
    fun rows(list: JSONObject?, field: String, driver: Boolean): List<ChampionshipStanding> =
        list?.optJSONArray(field)?.objects().orEmpty().mapNotNull { row ->
            val entity=row.optJSONObject(if(driver)"Driver" else "Constructor") ?: return@mapNotNull null
            val position=row.optInt("position",0)
            val points=row.optDouble("points",Double.NaN)
            val id=entity.optString(if(driver)"driverId" else "constructorId")
            if(position<=0 || !points.isFinite() || id.isBlank()) return@mapNotNull null
            ChampionshipStanding(id,position,if(driver)driverName(entity) else entity.optString("name"),
                if(driver)row.optJSONArray("Constructors")?.objects()?.joinToString(" / ") { it.optString("name") }.orEmpty() else "",
                points,row.optInt("wins").takeIf { row.has("wins") && it>=0 },
                if(driver && podiumComplete)podiumCounts[id] ?: 0 else null)
        }.sortedBy { it.position }
    val table=schedule?.optJSONObject("RaceTable")?.takeIf { it.optInt("season")==year }
    val races=table?.optJSONArray("Races")?.objects().orEmpty().filter { it.optInt("season")==year }.mapNotNull { race ->
        val round=race.optInt("round")
        val date=race.optString("date")
        if(round<=0 || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) return@mapNotNull null
        val circuit=race.optJSONObject("Circuit")
        val sessions=listOf(
            Triple("FirstPractice","practice-1","Treino Livre 1"),
            Triple("SecondPractice","practice-2","Treino Livre 2"),
            Triple("ThirdPractice","practice-3","Treino Livre 3"),
            Triple("Qualifying","qualifying","Classificação"),
            Triple("SprintQualifying","sprint-quali","Classificação Sprint"),
            Triple("SprintShootout","sprint-quali","Classificação Sprint"),
            Triple("Sprint","sprint","Sprint")
        ).mapNotNull { (field,id,name) -> race.optJSONObject(field)?.let {
            ChampionshipSession(id,name,it.optString("date"),championshipStart(it.optString("date"),it.optString("time")))
        } }.distinctBy { it.id } + ChampionshipSession("race","Corrida",date,championshipStart(date,race.optString("time")))
        ChampionshipRace(round,race.optString("raceName"),circuit?.optJSONObject("Location")?.optString("country").orEmpty(),
            circuit?.optString("circuitName").orEmpty(),date,championshipStart(date,race.optString("time")),
            sessions.sortedWith(compareBy<ChampionshipSession> { it.date }.thenBy { it.start ?: Long.MAX_VALUE }),round in completed)
    }.distinctBy { it.round }.sortedWith(compareBy<ChampionshipRace> { it.date }.thenBy { it.start ?: Long.MAX_VALUE })
    val stamps=(listOfNotNull(schedule,drivers,teams)+podiums.filterNotNull())
        .map { it.optLong("_auroraChecked") }.filter { it>0 }
    return ChampionshipSeason(year=year,races=races,drivers=rows(driverList,"DriverStandings",true),
        teams=rows(teamList,"ConstructorStandings",false),standingsRound=driverList?.optInt("round"),
        checked=stamps.minOrNull() ?: 0L,partial=partial)
}

private fun readChampionshipJson(address: String): JSONObject {
    val connection=(URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout=8000;readTimeout=8000
        setRequestProperty("User-Agent","AuroraIPTV/${BuildConfig.VERSION_NAME} Android")
        setRequestProperty("Accept","application/json")
    }
    return try {
        check(connection.responseCode in 200..299)
        connection.inputStream.use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer);if(count<0)break
                check(output.size()+count<=2*1024*1024);output.write(buffer,0,count)
            }
            JSONObject(output.toString("UTF-8"))
        }
    } finally { connection.disconnect() }
}
