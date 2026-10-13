package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.core.*

@Composable internal fun FootballScreen(model: AuroraViewModel) {
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true) {delay(1000);now=System.currentTimeMillis()}}
    val matches=remember(model.football,model.footballFeed,model.footballTab,model.footballSearch,now/1000) {
        filterFootball(model.football,model.footballTab,model.footballSearch,now,model.footballFeed)
    }
    val tabs=remember(model.football) {footballTabs(model.football)}
    val key=model.footballTab to model.footballSearch
    val scroll=remember(key) {model.footballPosition.takeIf {it?.first==key}?.second?.let {LazyListState(it.first,it.second)} ?: LazyListState()}
    LaunchedEffect(key,scroll) {snapshotFlow {scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset}.collect {
        model.footballPosition=key to it
    }}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={model.page=Page.HOME}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back_home))}
            Text(tr(R.string.football),Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
            IconButton(onClick=model::refreshFootball) {Icon(Icons.Default.Refresh,tr(R.string.refresh))}
            IconButton(onClick={model.openSettings()}) {Icon(Icons.Default.Settings,tr(R.string.settings))}
        }
        OutlinedTextField(model.footballSearch,{model.footballSearch=it},Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("football_search"),
            placeholder={Text(tr(R.string.search_team))},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp),
            trailingIcon={if(model.footballSearch.isNotBlank())IconButton(onClick={model.footballSearch=""}) {Icon(Icons.Default.Close,tr(R.string.clear_search))}})
        LazyRow(Modifier.fillMaxWidth().padding(top=6.dp),contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(tabs,key={it.key}) {tab ->
                FilterChip(model.footballTab==tab.key,{model.footballTab=tab.key},label={Text(footballTabTitle(tab),maxLines=1)},modifier=Modifier.testTag("football_tab_${tab.key}"))
            }
        }
        Text(tr(R.string.minute_updates),Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(model.footballFeed.failed)Text(tr(R.string.scores_stale),Modifier.padding(horizontal=20.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.error)
        if(model.guideLoading || model.footballScoresLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(matches.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.SportsSoccer,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                Text(tr(when {
                    model.guideLoading->R.string.loading
                    model.football.isNotEmpty()->R.string.no_matching_games
                    model.guideError->R.string.no_guide
                    else->R.string.no_games
                }))
                if(!model.guideLoading && model.football.isEmpty())OutlinedButton(onClick=model::refreshFootball) {Text(tr(R.string.retry))}
            }
        } else LazyColumn(Modifier.weight(1f).testTag("football_list"),state=scroll,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(matches,key={it.id}) {match ->FootballCard(model,match,now)}
        }
    }
}
@Composable private fun footballTabTitle(tab: FootballTab): String = when(tab.key) {
    FOOTBALL_GAMES->tr(R.string.games)
    FOOTBALL_FIFA->"FIFA"
    FOOTBALL_BRAZIL->tr(R.string.brasileirao)
    FOOTBALL_LIBERTADORES->tr(R.string.libertadores)
    FOOTBALL_SUDAMERICANA->tr(R.string.sudamericana)
    else->tab.title
}
@Composable private fun FootballCard(model: AuroraViewModel, match: FootballMatch, now: Long) {
    var expanded by remember(match.id) {mutableStateOf(false)}
    var artwork by remember(match.id) {mutableStateOf(initialFootballArtwork(match.details))}
    LaunchedEffect(match.id) {artwork=model.footballArtwork(match.details)}
    val matched=remember(match,model.footballFeed) {matchFootballScore(match,model.footballFeed)}
    val event=matched?.event
    val country=footballTabKey(match.details)==FOOTBALL_FIFA
    val homeBadge=if(country)artwork.home else matched?.home?.badge?.takeIf {it.isNotBlank() && !it.contains("/countries/")} ?: artwork.home
    val awayBadge=if(country)artwork.away else matched?.away?.badge?.takeIf {it.isNotBlank() && !it.contains("/countries/")} ?: artwork.away
    val competitionBadge=if(country)FIFA_BADGE else event?.badge?.takeIf(String::isNotBlank) ?: artwork.competition
    val championship=match.details.competition.ifBlank {event?.competition.orEmpty().ifBlank {tr(R.string.football)}}
    val mode=broadcastMode(match.programme,event)
    Card(onClick={model.playFootball(match.channels.first())},modifier=Modifier.fillMaxWidth().testTag("football_match"),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FootballBadge(competitionBadge,championship,Modifier.size(32.dp))
                Text(championship,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FootballTeam(match.details.home,homeBadge,Modifier.weight(1f))
                if(matched?.homeGoals!=null)Text(matched.homeGoals.toString(),Modifier.testTag("football_home_goals"),fontSize=27.sp,fontWeight=FontWeight.Bold)
                Column(Modifier.widthIn(min=40.dp,max=75.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(event!=null && event.state in listOf(MatchState.LIVE,MatchState.HALFTIME) && scoreIsFresh(event,now)) {
                        Text(tr(R.string.live_score),Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFC73249)).padding(horizontal=6.dp,vertical=3.dp),
                            color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)
                        Text(if(event.state==MatchState.HALFTIME)tr(R.string.halftime)else event.clock.ifBlank {tr(R.string.now)},fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    } else {
                        Text(if(matched?.homeGoals!=null)"–"else"X",fontSize=18.sp,color=MaterialTheme.colorScheme.primary)
                        if(event!=null)Text(tr(when(event.state) {
                            MatchState.FINISHED->R.string.full_time
                            MatchState.POSTPONED->R.string.postponed
                            MatchState.CANCELLED->R.string.match_cancelled
                            MatchState.SCHEDULED->R.string.match_scheduled
                            MatchState.LIVE,MatchState.HALFTIME->R.string.stale_score
                            else->R.string.score_unavailable
                        }),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(matched?.awayGoals!=null)Text(matched.awayGoals.toString(),Modifier.testTag("football_away_goals"),fontSize=27.sp,fontWeight=FontWeight.Bold)
                FootballTeam(match.details.away,awayBadge,Modifier.weight(1f))
            }
            if(event==null)Text(tr(R.string.score_unavailable),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.testTag("football_broadcast"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                val color=MaterialTheme.colorScheme.primary
                val locale=Locale.forLanguageTag(model.language)
                Text(tr(R.string.broadcast)+":",color=color,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                if(transmissionIsLive(match.programme,event,now)) {
                    val transition=rememberInfiniteTransition(label="live transmission")
                    val opacity by transition.animateFloat(1f,0f,infiniteRepeatable(tween(700),RepeatMode.Reverse),label="live dot")
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(tr(R.string.live_score).uppercase(locale),color=color,fontSize=13.sp,fontWeight=FontWeight.Bold)
                        Box(Modifier.size(8.dp).alpha(opacity).clip(androidx.compose.foundation.shape.CircleShape).background(color).testTag("football_live_dot"))
                    }
                } else {
                    if(mode==BroadcastMode.REPLAY)Text(tr(R.string.replay),color=color,fontWeight=FontWeight.Bold,fontSize=12.sp)
                    Text(footballDay(match.programme.start,locale),color=color,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    Text(tr(R.string.broadcast_time,footballTime(match.programme.start,locale)),color=color,fontSize=12.sp)
                    if(match.programme.start<=now && match.programme.end>now)Text(tr(R.string.now_on_channel),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(event!=null && mode==BroadcastMode.REPLAY)Text(tr(R.string.original_match,footballDay(event.kickoff,Locale.forLanguageTag(model.language))+" · "+footballTime(event.kickoff,Locale.forLanguageTag(model.language))),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(event!=null && event.state in listOf(MatchState.LIVE,MatchState.HALFTIME) && !scoreIsFresh(event,now))
                Text(tr(R.string.last_score_update,SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(event.checked))),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.LiveTv,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(match.channels.first().name,Modifier.weight(1f).padding(start=6.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                if(match.channels.size==1)Icon(Icons.Default.PlayArrow,tr(R.string.watch),Modifier.size(22.dp))
                else Box {
                    IconButton(onClick={expanded=true}) {Icon(Icons.Default.MoreVert,tr(R.string.channels))}
                    DropdownMenu(expanded,{expanded=false}) {match.channels.forEach {channel->
                        DropdownMenuItem(text={Text(channel.name)},onClick={expanded=false;model.playFootball(channel)})
                    }}
                }
            }
        }
    }
}
@Composable private fun FootballTeam(name: String, badge: String, modifier: Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        FootballBadge(badge,name,Modifier.size(50.dp))
        Text(name,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.heightIn(min=32.dp))
    }
}
@Composable private fun FootballBadge(image: String, name: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)),contentAlignment=Alignment.Center) {
        Text(name.split(Regex("\\s+")).take(2).mapNotNull {it.firstOrNull()?.uppercaseChar()}.joinToString(""),fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
        if(image.isNotBlank())AsyncImage(catalogImage(image),null,Modifier.fillMaxSize().padding(3.dp),contentScale=ContentScale.Fit)
    }
}
