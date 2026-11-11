package br.com.fabriciomatos.iptvplayer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Composable internal fun FootballScreen(model:AuroraViewModel) {
    val colors=LocalAuroraColors.current
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(model.sportsActive) {
        if(model.sportsActive)while(true) {now=System.currentTimeMillis();delay(30000)}
    }
    val zone=remember(model.sportsTimeZone) {
        if(model.sportsTimeZone=="device")TimeZone.getDefault() else TimeZone.getTimeZone(model.sportsTimeZone)
    }
    val locale=Locale.forLanguageTag(model.language)
    val view by key(model.playlistUrl,model.sportsDisciplineId,model.sportsCompetitionId) {
        produceState(SportsHubCatalog(),model.sportsHub,model.footballSearch,model.sportsHubFilter,now,model.sportsScores) {
        value=withContext(Dispatchers.Default) {
            sportsHubProjection(model.sportsHub,model.footballSearch,model.sportsHubFilter,now,model.sportsScores,
                model.sportsDisciplineId,model.sportsCompetitionId)
        }
    }}
    val selected=model.sportsHub.disciplines.firstOrNull {it.id==model.sportsDisciplineId}
    val discipline=view.disciplines.firstOrNull {it.id==model.sportsDisciplineId}
    val competition=selected?.competitions?.firstOrNull {it.id==model.sportsCompetitionId}
    val visibleCompetition=discipline?.competitions?.firstOrNull {it.id==model.sportsCompetitionId}
    var championshipOpen by rememberSaveable(model.playlistUrl) {mutableStateOf(false)}
    if(championshipOpen) {
        MotorsportChampionshipScreen(model) {championshipOpen=false}
        return
    }
    BackHandler(enabled=model.sportsActive) {model.backFromSports()}
    DisposableEffect(Unit) {onDispose {model.visibleSportsEvents(emptySet())}}
    val key=(model.sportsDisciplineId.orEmpty()+":"+model.sportsCompetitionId.orEmpty()) to
        (model.footballSearch+":"+model.sportsHubFilter.name)
    val scroll=remember(key) {
        model.footballPosition?.takeIf {it.first==key}?.second?.let {LazyListState(it.first,it.second)} ?: LazyListState()
    }
    LaunchedEffect(scroll,key,visibleCompetition?.events) {
        snapshotFlow {scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset}.collectLatest { position ->
            val previous=model.footballPosition?.takeIf {it.first==key}?.second
            if(previous==null || previous.first!=position.first || kotlin.math.abs(previous.second-position.second)>=48)
                model.footballPosition=key to position
        }
    }
    LaunchedEffect(scroll,model.sportsCompetitionId,visibleCompetition?.events) {
        snapshotFlow {scroll.layoutInfo.visibleItemsInfo.mapNotNull {it.key as? String}.filter {it.startsWith("football:")}.toSet()}
            .collectLatest { ids ->
                // Briefly coalesce fast flings; only visible cards request scores.
                delay(250);model.visibleSportsEvents(ids)
            }
    }
    val rowState=key(model.playlistUrl,model.sportsCompetitionId) {
        remember {mutableStateOf(emptyList<SportsHubRow>())}
    }
    val rows by rowState
    LaunchedEffect(rowState,visibleCompetition,zone.id) {
        rowState.value=withContext(Dispatchers.Default) {sportsHubRows(visibleCompetition?.events.orEmpty(),zone)}
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact=maxWidth>=600.dp && maxWidth>maxHeight
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.glow.copy(alpha=.3f),colors.background)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=model::backFromSports,modifier=Modifier.testTag("sports_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.sports_back))
            }
            Column(if(compact)Modifier.width(180.dp) else Modifier.weight(1f)) {
                Text(tr(R.string.sports_central),fontSize=if(compact)19.sp else 23.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(competition?.title ?: selected?.title ?: tr(R.string.sports_choose_discipline),
                    fontSize=11.sp,color=colors.muted,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(compact)SportsHubSearch(model,Modifier.weight(1f).padding(horizontal=8.dp))
            IconButton(onClick=model::refreshFootball) {Icon(Icons.Default.Refresh,tr(R.string.refresh),tint=colors.mint)}
            IconButton(onClick={model.openSettings()}) {Icon(Icons.Default.Settings,tr(R.string.settings),tint=colors.muted)}
        }
        if(!compact)SportsHubSearch(model,Modifier.fillMaxWidth().padding(horizontal=16.dp))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),
            modifier=Modifier.weight(1f).testTag("sports_filters")) {
            items(SportsHubFilter.entries,key={it.name}) { filter ->
                FilterChip(model.sportsHubFilter==filter,{model.sportsHubFilter=filter},
                    label={Text(tr(when(filter) {
                        SportsHubFilter.ALL->R.string.sports_all_broadcasts
                        SportsHubFilter.LIVE->R.string.live_score
                        SportsHubFilter.UPCOMING->R.string.sports_upcoming
                    }))},modifier=Modifier.testTag("sports_filter_${filter.name.lowercase()}"))
            }
        }
        if(compact)SportsTimeZoneSwitch(model)
        }
        if(!compact)Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(model.playlistName,Modifier.weight(1f),fontSize=10.sp,color=colors.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            SportsTimeZoneSwitch(model)
        }
        if(model.guideDiagnostics.alternate) Text(tr(R.string.sports_source_epgshare),
            Modifier.padding(horizontal=18.dp,vertical=3.dp).testTag("sports_guide_source"),fontSize=10.sp,color=colors.muted)
        if(model.guideLoading || model.footballScoresLoading)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if(model.footballFeed.failed && competition!=null && model.selectedSport==SportKind.FOOTBALL)
            Text(tr(R.string.scores_stale),Modifier.padding(horizontal=18.dp),fontSize=10.sp,color=colors.muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("football_list"),state=scroll,
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(selected==null && model.sportsDisciplineId==null) {
                items(view.disciplines,key={"discipline:"+it.id},contentType={"discipline"}) { sport ->
                    SportsNavigationCard(sport.title,tr(R.string.sports_competition_count,sport.competitions.size),sport.count,
                        sport.liveCount>0,
                        sportsIcon(sport.sport),Modifier.testTag("sports_kind_"+sport.id.lowercase()),
                        onClick={model.openSportsDiscipline(sport.id)})
                }
                // Complementary championship data is explicitly separate from available broadcasts.
                item(key="championship-reference",contentType="reference") {
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(tr(R.string.sports_reference_only),fontSize=10.sp,color=colors.muted)
                        OutlinedButton(onClick={championshipOpen=true},modifier=Modifier.fillMaxWidth().testTag("sports_f1_reference")) {
                            Icon(Icons.Default.EmojiEvents,null,Modifier.size(17.dp));Spacer(Modifier.width(8.dp))
                            Text(tr(R.string.sports_f1_reference),fontSize=11.sp)
                        }
                    }
                }
            } else if(model.sportsCompetitionId==null) {
                items(discipline?.competitions.orEmpty(),key={"competition:"+it.id},contentType={"competition"}) { league ->
                    val logo=remember(league.id,selected) {
                        val first=league.events.first()
                        first.football?.let {footballLeagueTabLogo(FootballTab(footballTabKey(it.details),league.title),listOf(it))}
                            ?: sportsLeagueLogo(league.title,first.channels)
                    }
                    SportsNavigationCard(league.title,tr(R.string.sports_epg_confirmed),league.events.size,
                        league.liveCount>0,
                        Icons.Default.EmojiEvents,Modifier.testTag("sports_competition_"+league.id),logo) {
                        model.openSportsCompetition(league.id)
                    }
                }
            } else {
                if(selected?.sport==SportKind.MOTORSPORT && competition?.id=="f1")item(key="f1-reference") {
                    OutlinedButton(onClick={championshipOpen=true},modifier=Modifier.fillMaxWidth().testTag("open_f1_championship")) {
                        Icon(Icons.Default.EmojiEvents,null,Modifier.size(17.dp));Spacer(Modifier.width(8.dp))
                        Text(tr(R.string.sports_f1_reference),fontSize=11.sp)
                    }
                }
                items(rows,key={it.key},contentType={when(it) {
                    is SportsHubRow.Day->"day"
                    is SportsHubRow.Stage->"stage"
                    is SportsHubRow.Event->it.event.sport.name
                }}) { row ->
                    when(row) {
                        is SportsHubRow.Day->SportsDayHeader(row.start,now,locale,zone)
                        is SportsHubRow.Stage->Text(row.title,fontSize=13.sp,fontWeight=FontWeight.Bold,color=colors.cyan,
                            modifier=Modifier.testTag("sports_stage_header"))
                        is SportsHubRow.Event->{
                            val event=row.event
                            if(event.football!=null)FootballCard(model,event.football,now)
                            else if(event.sport==SportKind.MOTORSPORT)MotorsportEventCard(model,event.other!!,now)
                            else SportsEventCard(model,event.other!!,now)
                        }
                    }
                }
            }
            val empty=if(model.sportsDisciplineId==null)view.disciplines.isEmpty()
                else if(model.sportsCompetitionId==null)discipline?.competitions.isNullOrEmpty()
                else visibleCompetition?.events.isNullOrEmpty()
            if(empty)item(key="empty",contentType="empty") {
                Column(Modifier.fillMaxWidth().padding(vertical=28.dp).testTag("sports_empty"),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.EventAvailable,null,Modifier.size(40.dp),tint=colors.mint)
                    Text(tr(if(model.guideLoading)R.string.loading else if(model.footballSearch.isNotBlank() ||
                        model.sportsHubFilter!=SportsHubFilter.ALL)R.string.no_matching_games else R.string.sports_empty_epg),
                        color=colors.muted,fontSize=13.sp)
                    if(!model.guideLoading)OutlinedButton(onClick=model::refreshFootball) {Text(tr(R.string.retry))}
                }
            }
        }
    }
    }
}

@Composable private fun SportsHubSearch(model:AuroraViewModel,modifier:Modifier) {
    val colors=LocalAuroraColors.current
    OutlinedTextField(model.footballSearch,{model.footballSearch=it},modifier.testTag("football_search"),
        placeholder={Text(tr(R.string.sports_search),fontSize=12.sp)},
        leadingIcon={Icon(Icons.Default.Search,null,tint=colors.mint)},singleLine=true,shape=RoundedCornerShape(16.dp),
        trailingIcon={if(model.footballSearch.isNotBlank())IconButton(onClick={model.footballSearch=""}) {
            Icon(Icons.Default.Close,tr(R.string.clear_search))
        }})
}

@Composable private fun SportsTimeZoneSwitch(model:AuroraViewModel) {
    TextButton(onClick={model.chooseSportsTimeZone(if(model.sportsTimeZone=="device")"America/Sao_Paulo" else "device")},
        contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.testTag("sports_timezone")) {
        Icon(Icons.Default.Schedule,null,Modifier.size(13.dp));Spacer(Modifier.width(4.dp))
        Text(tr(if(model.sportsTimeZone=="device")R.string.sports_device_time else R.string.sports_brasilia_time),fontSize=10.sp)
    }
}

@Composable private fun SportsNavigationCard(title:String,subtitle:String,count:Int,live:Boolean,icon:ImageVector,
    modifier:Modifier=Modifier,logo:String="",onClick:()->Unit) {
    val colors=LocalAuroraColors.current
    Card(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=colors.panel),
        border=BorderStroke(1.dp,if(live)colors.mint.copy(alpha=.55f) else colors.cyan.copy(alpha=.18f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            Surface(shape=RoundedCornerShape(14.dp),color=colors.mint.copy(alpha=.1f)) {
                Box(Modifier.size(50.dp),contentAlignment=Alignment.Center) {
                    if(logo.isNotBlank())AsyncImage(catalogImage(logo),null,Modifier.size(34.dp),contentScale=ContentScale.Fit)
                    else Icon(icon,null,Modifier.size(28.dp),tint=colors.mint)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(subtitle,fontSize=10.sp,color=colors.muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(live)Text(tr(R.string.live_score),fontSize=10.sp,color=colors.mint,fontWeight=FontWeight.Bold)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(count.toString(),fontSize=22.sp,fontWeight=FontWeight.Black,color=colors.mint)
                Text(tr(R.string.sports_broadcasts),fontSize=9.sp,color=colors.muted)
                Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp),tint=colors.muted)
            }
        }
    }
}

@Composable private fun SportsDayHeader(value:Long,now:Long,locale:Locale,zone:TimeZone) {
    val colors=LocalAuroraColors.current
    val tomorrow=Calendar.getInstance(zone).apply {timeInMillis=now;add(Calendar.DAY_OF_YEAR,1)}.timeInMillis
    val relative=when(sportsDateKey(value,zone)) {
        sportsDateKey(now,zone)->tr(R.string.sports_today)
        sportsDateKey(tomorrow,zone)->tr(R.string.sports_tomorrow)
        else->null
    }
    Column(Modifier.padding(top=10.dp,bottom=2.dp).testTag("sports_day_header"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(relative!=null)Text(relative,fontSize=18.sp,fontWeight=FontWeight.Bold,color=colors.mint)
        Text(sportsDayHeading(value,locale,zone),fontSize=10.sp,fontWeight=FontWeight.SemiBold,color=colors.muted)
    }
}

internal fun sportsIcon(sport:SportKind):ImageVector=when(sport) {
    SportKind.FOOTBALL->Icons.Default.SportsSoccer
    SportKind.BASKETBALL->Icons.Default.SportsBasketball
    SportKind.AMERICAN_FOOTBALL->Icons.Default.SportsFootball
    SportKind.BASEBALL->Icons.Default.SportsBaseball
    SportKind.MOTORSPORT->Icons.Default.SportsMotorsports
    SportKind.HOCKEY->Icons.Default.SportsHockey
    SportKind.TENNIS->Icons.Default.SportsTennis
    SportKind.VOLLEYBALL->Icons.Default.SportsVolleyball
    SportKind.HANDBALL->Icons.Default.SportsHandball
    SportKind.OTHER->Icons.Default.Sports
}
