package br.com.fabriciomatos.iptvplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

@Composable internal fun MotorsportChampionshipScreen(model: AuroraViewModel, onBack: () -> Unit) {
    BackHandler(enabled=!model.settingsOpen && !model.fullscreen,onBack=onBack)
    val year=Calendar.getInstance().get(Calendar.YEAR)
    var season by remember(year) { mutableStateOf<ChampionshipSeason?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val locale=Locale.forLanguageTag(model.language)
    LaunchedEffect(year,attempt) {
        loading=true
        try { season=model.motorsportChampionship.load(year) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { season=season?.copy(partial=true) }
        finally { loading=false }
    }
    ChampionshipContent(season,loading,section,{ section=it },locale,onBack,{ attempt++ },
        model.sports, { race -> model.motorsportChampionship.results(year,race.round) },
        { events -> events.forEach { MotorsportEventCard(model,it,System.currentTimeMillis()) } })
}

/** Stateless entry point also lets UI tests use fixtures without network or player initialization. */
@Composable internal fun ChampionshipContent(
    season: ChampionshipSeason?, loading: Boolean, section: Int, select: (Int)->Unit,
    locale: Locale, onBack: ()->Unit, retry: ()->Unit, events: List<SportsEvent>,
    results: suspend (ChampionshipRace)->List<ChampionshipStanding>?,
    broadcasts: @Composable (List<SportsEvent>)->Unit
) {
    val colors=LocalAuroraColors.current
    val now=System.currentTimeMillis()
    val next=season?.nextRace(now)
    val sections=listOf("Visão geral","Pilotos","Equipes","Próxima corrida","Calendário","Estatísticas")
    Column(Modifier.fillMaxSize().testTag("motorsport_championship")) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick=onBack,modifier=Modifier.testTag("championship_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,"Voltar às transmissões")
            }
            Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                Text("Fórmula 1 · Campeonato",fontWeight=FontWeight.Bold,fontSize=20.sp)
                Text(season?.let { "Temporada ${it.year}" } ?: "Temporada atual",color=colors.muted,fontSize=12.sp)
            }
        }
        TextButton(onClick=onBack,modifier=Modifier.padding(horizontal=12.dp).testTag("championship_broadcasts")) {
            Text("Ver transmissões da Fórmula 1")
        }
        LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(sections.indices.toList()) { index ->
                FilterChip(section==index,{select(index)},label={Text(sections[index])},
                    modifier=Modifier.testTag("championship_section_$index"))
            }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("championship_loading"))
        key(section) {
            LazyColumn(Modifier.weight(1f).testTag("championship_content"),
                contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(season==null || season.races.isEmpty()) item {
                    ChampionshipPanel("Dados indisponíveis") {
                        Text(if(loading)"Consultando a temporada…" else "Não foi possível consultar o calendário desta temporada.")
                        if(!loading) OutlinedButton(onClick=retry) { Text("Tentar novamente") }
                    }
                }
                if(season!=null) {
                    if(season.partial) item {
                        Text("Alguns dados estão indisponíveis ou em cache. Consulte a data da atualização.",
                            color=colors.muted,fontSize=12.sp,modifier=Modifier.testTag("championship_partial"))
                        if(!loading) TextButton(onClick=retry) { Text("Tentar atualizar") }
                    }
                    when(section) {
                        0 -> {
                            item {
                                ChampionshipPanel(season.name) {
                                    if(season.races.isEmpty()) Unavailable("Resumo do calendário") else {
                                        Text("${season.races.size} etapas no calendário · ${season.races.count { it.completed }} com resultado confirmado")
                                        val remaining=season.races.count { !it.completed && (it.start?.let { t -> t>now } ?: (it.date>championshipToday(now))) }
                                        Text("$remaining etapas futuras · encerramento previsto: ${season.races.last().date}")
                                    }
                                    Text("Líder: ${season.drivers.firstOrNull { it.position==1 }?.name ?: "indisponível"}",color=colors.mint)
                                    Text("Equipe líder: ${season.teams.firstOrNull { it.position==1 }?.name ?: "indisponível"}")
                                    season.standingsRound?.let { Text("Classificação após a etapa $it",fontSize=12.sp,color=colors.muted) }
                                    if(season.checked>0) Text("Dados consultados: ${championshipDateTime(season.checked,locale)}",fontSize=11.sp,color=colors.muted)
                                }
                            }
                            item { ChampionshipPanel("Líderes do campeonato") {
                                season.drivers.take(3).forEach { Text("${it.position}. ${it.name} · ${championshipNumber(it.points,locale)} pts") }
                                if(season.drivers.isEmpty()) Unavailable("Classificação dos pilotos")
                                TextButton(onClick={select(1)}) { Text("Classificação completa") }
                            } }
                            item { NextChampionshipRace(next,locale,now,events,broadcasts) }
                        }
                        1 -> item { ChampionshipStandings(season.drivers,true,locale) }
                        2 -> item { ChampionshipStandings(season.teams,false,locale) }
                        3 -> item { NextChampionshipRace(next,locale,now,events,broadcasts) }
                        4 -> {
                            item { Text("Calendário da temporada · horários do aparelho",color=colors.muted,fontSize=12.sp) }
                            items(season.races,key={it.round}) { race ->
                                ChampionshipRaceItem(race,race.round==next?.round,locale,now,results,events,broadcasts)
                            }
                        }
                        5 -> item { ChampionshipStatistics(season,locale) }
                    }
                    item { Text("Fonte: Jolpica-F1 / Ergast · dados sujeitos à atualização da fonte. A grade IPTV informa somente as transmissões.",
                        color=colors.muted,fontSize=11.sp) }
                }
            }
        }
    }
}

@Composable private fun ChampionshipPanel(title: String, content: @Composable ColumnScope.()->Unit) {
    val colors=LocalAuroraColors.current
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=colors.panel),
        border=BorderStroke(1.dp,colors.mint.copy(alpha=.22f))) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,color=colors.text)
            content()
        }
    }
}

@Composable private fun Unavailable(subject: String) {
    Text("$subject: indisponível",color=LocalAuroraColors.current.muted,fontSize=12.sp)
}

internal fun championshipNumber(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits=3
}.format(value)

private fun championshipDateTime(time: Long, locale: Locale) = footballDay(time,locale)+" · "+footballTime(time,locale)
private fun sessionTime(session: ChampionshipSession, locale: Locale) = session.start?.let {
    championshipDateTime(it,locale)
} ?: "${session.date} · horário não informado"

@Composable private fun ChampionshipStandings(rows: List<ChampionshipStanding>, drivers: Boolean, locale: Locale) {
    val colors=LocalAuroraColors.current
    ChampionshipPanel(if(drivers)"Classificação dos pilotos" else "Classificação dos construtores") {
        if(rows.isEmpty()) Unavailable("Classificação") else {
            Text("Pontos oficiais · deslize a tabela para os lados",fontSize=11.sp,color=colors.muted)
            Column(Modifier.horizontalScroll(rememberScrollState()).width(if(drivers)680.dp else 500.dp)) {
                Row(Modifier.padding(vertical=8.dp)) {
                    listOf("Pos.",if(drivers)"Piloto / equipe" else "Equipe","Pts","Dif.","Vit.")
                        .plus(if(drivers)listOf("Pódios") else emptyList()).forEachIndexed { index,label ->
                            Text(label,Modifier.width(if(index==1)250.dp else 65.dp),fontWeight=FontWeight.Bold,color=colors.muted)
                        }
                }
                rows.forEach { row ->
                    HorizontalDivider(color=colors.muted.copy(alpha=.14f))
                    Row(Modifier.padding(vertical=10.dp)) {
                        val accent=if(row.position<=3)colors.mint else colors.text
                        Text(if(row.position==1)"★ 1" else row.position.toString(),Modifier.width(65.dp),color=accent,fontWeight=FontWeight.Bold)
                        Column(Modifier.width(250.dp).padding(end=8.dp)) {
                            Text(row.name,color=accent,fontWeight=if(row.position==1)FontWeight.Bold else FontWeight.Medium)
                            if(drivers)Text(row.team.ifBlank { "Equipe não informada" },color=colors.muted,fontSize=12.sp)
                        }
                        Text(championshipNumber(row.points,locale),Modifier.width(65.dp))
                        Text(championshipGap(row,rows.firstOrNull { it.position==1 })?.let { championshipNumber(it,locale) } ?: "—",Modifier.width(65.dp))
                        Text(row.wins?.toString() ?: "—",Modifier.width(65.dp))
                        if(drivers)Text(row.podiums?.toString() ?: "—",Modifier.width(65.dp))
                    }
                }
            }
            if(drivers && rows.any { it.podiums==null }) Unavailable("Pódios completos da temporada")
            Text("Vitórias e pódios de Grandes Prêmios. Os pontos seguem a classificação da fonte, incluindo Sprint.",
                fontSize=11.sp,color=colors.muted)
        }
    }
}

@Composable private fun NextChampionshipRace(
    race: ChampionshipRace?, locale: Locale, now: Long, events: List<SportsEvent>,
    broadcasts: @Composable (List<SportsEvent>)->Unit
) {
    ChampionshipPanel("Próxima corrida") {
        if(race==null) Unavailable("Próxima corrida no calendário") else {
            RaceSummary(race,locale,now)
            RaceSessions(race,locale,now)
            RaceBroadcasts(race,events,broadcasts)
        }
    }
}

@Composable private fun RaceSummary(race: ChampionshipRace, locale: Locale, now: Long) {
    Text("${race.round}. ${race.name}",fontWeight=FontWeight.Bold,color=LocalAuroraColors.current.mint)
    Text(listOf(race.country,race.circuit).filter(String::isNotBlank).joinToString(" · "),fontSize=12.sp)
    Text(race.start?.let { championshipDateTime(it,locale) } ?: "${race.date} · horário não informado")
    Text(championshipRaceStatus(race,now),fontSize=12.sp,color=LocalAuroraColors.current.muted)
}

@Composable private fun RaceSessions(race: ChampionshipRace, locale: Locale, now: Long) {
    Text("Programação · horário do aparelho",fontWeight=FontWeight.SemiBold,fontSize=13.sp)
    race.sessions.forEach { session ->
        val status=when {
            session.id=="race" && race.completed -> "Finalizada"
            session.start?.let { it>now }==true -> "Programada"
            else -> "Status não informado"
        }
        Text("${session.name} · ${sessionTime(session,locale)} · $status",fontSize=12.sp)
    }
}

@Composable private fun RaceBroadcasts(race: ChampionshipRace, events: List<SportsEvent>, broadcasts: @Composable (List<SportsEvent>)->Unit) {
    val linked=remember(race,events) { championshipBroadcasts(race,events) }
    if(linked.isEmpty()) Unavailable("Transmissões correspondentes na grade") else broadcasts(linked)
}

@Composable private fun ChampionshipRaceItem(
    race: ChampionshipRace, next: Boolean, locale: Locale, now: Long,
    results: suspend (ChampionshipRace)->List<ChampionshipStanding>?, events: List<SportsEvent>,
    broadcasts: @Composable (List<SportsEvent>)->Unit
) {
    var expanded by rememberSaveable(race.round) { mutableStateOf(false) }
    var rows by remember(race.round) { mutableStateOf<List<ChampionshipStanding>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded,race.completed) {
        if(expanded && race.completed && !loaded) {
            loading=true
            try { rows=results(race);loaded=true }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { rows=null;loaded=true }
            finally { loading=false }
        }
    }
    ChampionshipPanel(if(next)"Próxima etapa" else "Etapa ${race.round}") {
        RaceSummary(race,locale,now)
        TextButton(onClick={expanded=!expanded},modifier=Modifier.testTag("championship_round_${race.round}")) {
            Text(if(expanded)"Recolher" else if(race.completed)"Ver resultados" else "Ver programação")
        }
        if(expanded) {
            if(race.completed) {
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if(rows.isNullOrEmpty()) Unavailable("Resultados")
                else rows.orEmpty().forEach { Text("${it.position}. ${it.name} · ${it.team} · ${championshipNumber(it.points,locale)} pts",fontSize=12.sp) }
            } else RaceSessions(race,locale,now)
            RaceBroadcasts(race,events,broadcasts)
        }
    }
}

@Composable private fun ChampionshipStatistics(season: ChampionshipSeason, locale: Locale) {
    ChampionshipPanel("Estatísticas e disputa pelo título") {
        fun leaders(rows: List<ChampionshipStanding>, value: (ChampionshipStanding)->Int?): String? {
            if(rows.isEmpty() || rows.any { value(it)==null })return null
            val max=rows.maxOf { value(it)!! }
            return rows.filter { value(it)==max }.joinToString(" / ") { it.name }+" · $max"
        }
        Text("Mais vitórias: ${leaders(season.drivers) { it.wins } ?: "indisponível"}")
        Text("Mais pódios: ${leaders(season.drivers) { it.podiums } ?: "indisponível"}")
        Text("Equipe com mais vitórias: ${leaders(season.teams) { it.wins } ?: "indisponível"}")
        val gap=season.drivers.firstOrNull { it.position==2 }?.let {
            championshipGap(it,season.drivers.firstOrNull { row -> row.position==1 })
        }
        Text("Diferença entre os líderes: ${gap?.let { championshipNumber(it,locale)+" pts" } ?: "indisponível"}")
        Unavailable("Histórico da evolução de pontos")
        Unavailable("Pontuação máxima restante e disputa matemática")
        Text("A fonte consultada não confirma todas as condições do regulamento e de cada Sprint. Não afirmamos título conquistado ou eliminação matemática.",
            fontSize=12.sp,color=LocalAuroraColors.current.muted)
    }
}
