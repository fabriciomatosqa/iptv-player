package br.com.fabriciomatos.iptvplayer

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun SportsEventCard(model:AuroraViewModel,event:SportsEvent,now:Long) {
    val colors=LocalAuroraColors.current
    var teamArtwork by remember(event.id) { mutableStateOf(FootballArtwork()) }
    // Lazy cards request public badges only when visible; the resolver caches each league.
    LaunchedEffect(event.id,event.home,event.away) {
        teamArtwork=model.sportsArtwork(event)
    }
    val liveChannels=sportsLiveChannels(event,now)
    val live=liveChannels.isNotEmpty()
    val activeProgramme=event.displayProgramme(now)
    val state=sportsBroadcastState(activeProgramme,now)
    val accent=if(state==SportsBroadcastState.REPLAY)colors.violet else if(live)colors.mint else colors.muted
    val zone=sportsDisplayZone(model.sportsTimeZone)
    val eventLeagueLogo=remember(event.competition,event.channels) {
        sportsLeagueLogo(event.competition,event.channels)
    }
    val icon=sportsIcon(event.sport)
    Card(Modifier.fillMaxWidth().testTag("sports_event"),shape=RoundedCornerShape(24.dp),
        colors=CardDefaults.cardColors(containerColor=colors.panel),
        border=BorderStroke(1.dp,accent.copy(alpha=.3f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(colors.mint.copy(alpha=.12f)),contentAlignment=Alignment.Center) {
                    if(eventLeagueLogo.isNotBlank()) AsyncImage(catalogImage(eventLeagueLogo),null,
                        Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
                            .background(Color.White).padding(2.dp),contentScale=ContentScale.Fit)
                    else Icon(icon,null,tint=colors.mint,modifier=Modifier.size(23.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(event.competition,maxLines=2,overflow=TextOverflow.Ellipsis,
                        color=colors.text,fontSize=14.sp,fontWeight=FontWeight.Bold)
                    Text(event.disciplineName,fontSize=11.sp,color=colors.muted)
                }
                if(live) FootballStatus(tr(R.string.now),Icons.Default.Sensors,colors.mint)
            }
            if(event.home.isNotBlank() && event.away.isNotBlank()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        FootballBadge(teamArtwork.home,event.home,
                            Modifier.size(72.dp).testTag("sports_home_badge"),colors.mint)
                        Text(event.home,fontSize=14.sp,fontWeight=FontWeight.Bold,
                            maxLines=3,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                            lineHeight=18.sp,modifier=Modifier.fillMaxWidth())
                    }
                    Text("×",fontSize=24.sp,fontWeight=FontWeight.Black,color=colors.mint)
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        FootballBadge(teamArtwork.away,event.away,
                            Modifier.size(72.dp).testTag("sports_away_badge"),colors.mint)
                        Text(event.away,fontSize=14.sp,fontWeight=FontWeight.Bold,
                            maxLines=3,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                            lineHeight=18.sp,modifier=Modifier.fillMaxWidth())
                    }
                }
            } else {
                // Motorsports and timed events are not fake head-to-head matches.
                Text(event.name,fontSize=17.sp,fontWeight=FontWeight.Bold,
                    maxLines=3,overflow=TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                .background(colors.elevated.copy(alpha=.55f)).padding(14.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(tr(sportsStateResource(state)),
                    fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=accent)
                Text(footballDay(activeProgramme.start,Locale.forLanguageTag(model.language),zone),
                    fontSize=12.sp,color=colors.text)
                Text(footballTime(activeProgramme.start,Locale.forLanguageTag(model.language),zone),
                    fontSize=13.sp,fontWeight=FontWeight.Bold,color=colors.mint)
            }
            HorizontalDivider(color=colors.muted.copy(alpha=.12f))
            FootballChannelActions(model,event.channels,programmes=event.channelProgrammes,
                featuredChannels=liveChannels.ifEmpty {event.channels})
        }
    }
}

@Composable internal fun FootballCard(model: AuroraViewModel, match: FootballMatch, now: Long) {
    val colors = LocalAuroraColors.current
    // An EPG-only card can gain a verified league after scores arrive. Its ID is
    // unchanged, but artwork must refresh when the resolved competition changes.
    var artwork by remember(match.id,match.details) { mutableStateOf(initialFootballArtwork(match.details)) }
    LaunchedEffect(match.id,match.details) { artwork = model.footballArtwork(match.details) }
    val matched = model.sportsScores[match.id]
    val event = matched?.event
    val country = nationalMatch(match.details) != null
    val homeBadge = if (country) artwork.home else matched?.home?.badge?.takeIf { it.isNotBlank() && !it.contains("/countries/") } ?: artwork.home
    val awayBadge = if (country) artwork.away else matched?.away?.badge?.takeIf { it.isNotBlank() && !it.contains("/countries/") } ?: artwork.away
    val competitionBadge = if (country) FIFA_BADGE else event?.badge?.takeIf(String::isNotBlank) ?: artwork.competition
    val championship = match.details.competition.ifBlank { event?.competition.orEmpty().ifBlank { tr(R.string.football) } }
    val mode = broadcastMode(match.programme, event)
    val liveChannels = footballLiveChannels(match,event,now)
    val liveTransmission = liveChannels.isNotEmpty()
    val watchChannels = liveChannels.ifEmpty { match.channels }
    var liveDetailsExpanded by remember(match.id) { mutableStateOf(false) }
    if(compactFootballMatch(match,event,now)) {
        CompactFootballCard(model,match,matched,homeBadge,awayBadge,championship,now)
        return
    }
    val accent = if (mode == BroadcastMode.REPLAY) colors.violet else colors.mint
    Card(onClick = { model.playFootball(watchChannels.first()) }, modifier = Modifier.fillMaxWidth().testTag("football_match"), shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = colors.panel), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = .38f), colors.cyan.copy(alpha = .12f), colors.violet.copy(alpha = .2f))))) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .12f), Color.Transparent), center = Offset(size.width * .85f, 0f), radius = size.width * .8f))
                drawRect(Brush.radialGradient(listOf(colors.violet.copy(alpha = .07f), Color.Transparent), center = Offset(0f, size.height), radius = size.width * .7f))
            }
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(listOf(accent.copy(alpha = .08f), accent.copy(alpha = .85f), colors.cyan.copy(alpha = .55f), colors.violet.copy(alpha = .08f)))))
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FootballBadge(competitionBadge, championship, Modifier.size(36.dp), colors.cyan, competition = true)
                        Text(championship, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (mode == BroadcastMode.REPLAY) FootballStatus(tr(R.string.replay), Icons.Default.Replay, colors.violet)
                        else if (liveTransmission) FootballStatus(tr(R.string.now), Icons.Default.Sensors, colors.mint)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 600.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            FootballConfrontation(match, matched, homeBadge, awayBadge, now, Modifier.weight(1.2f))
                            FootballTransmission(model, match, liveTransmission, now, Modifier.weight(1f),mode==BroadcastMode.REPLAY)
                        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            FootballConfrontation(match, matched, homeBadge, awayBadge, now, Modifier.fillMaxWidth())
                            FootballTransmission(model, match, liveTransmission, now, Modifier.fillMaxWidth(),mode==BroadcastMode.REPLAY)
                        }
                    }
                    if (event != null && mode == BroadcastMode.REPLAY) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.History, null, Modifier.size(14.dp), tint = colors.muted)
                        Text(tr(R.string.original_match, footballDay(event.kickoff, Locale.forLanguageTag(model.language),sportsDisplayZone(model.sportsTimeZone)) + " · " + footballTime(event.kickoff, Locale.forLanguageTag(model.language),sportsDisplayZone(model.sportsTimeZone))),
                            fontSize = 10.sp, color = colors.muted, lineHeight = 15.sp)
                    }
                    if (event != null && event.state in listOf(MatchState.LIVE, MatchState.HALFTIME) && !scoreIsFresh(event, now)) {
                        Text(tr(R.string.last_score_update, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.checked))), fontSize = 10.sp, color = colors.muted)
                    }
                    if (liveTransmission || (match.programme.start <= now && match.programme.end > now &&
                            mode != BroadcastMode.REPLAY)) {
                        FootballDetailsToggle(liveDetailsExpanded) { liveDetailsExpanded = !liveDetailsExpanded }
                        if (liveDetailsExpanded) FootballIncidentsPanel(model,matched,match.details.home,match.details.away)
                    }
                    HorizontalDivider(color = colors.muted.copy(alpha = .12f))
                    FootballChannelActions(model,match.channels,programmes=match.channelProgrammes,featuredChannels=watchChannels)
                }
            }
        }
    }
}


@Composable private fun CompactFootballCard(
    model: AuroraViewModel, match: FootballMatch, matched: MatchedScore?,
    homeBadge: String, awayBadge: String, championship: String, now: Long
) {
    val colors=LocalAuroraColors.current
    val event=matched?.event
    val replay=broadcastMode(match.programme,event)==BroadcastMode.REPLAY
    val accent=if(replay) colors.violet else colors.muted
    var detailsExpanded by remember(match.id) { mutableStateOf(false) }
    Card(onClick={ detailsExpanded=!detailsExpanded },modifier=Modifier.fillMaxWidth().testTag("football_match"),
        shape=RoundedCornerShape(19.dp),colors=CardDefaults.cardColors(containerColor=colors.panel),
        border=BorderStroke(1.dp,accent.copy(alpha=.28f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=11.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FootballBadge(homeBadge,match.details.home,Modifier.size(34.dp),colors.mint)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(match.details.home+" × "+match.details.away,fontSize=12.sp,fontWeight=FontWeight.SemiBold,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(championship,color=colors.muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(matched?.homeGoals!=null && matched.awayGoals!=null)
                    Text("${matched.homeGoals}–${matched.awayGoals}",fontWeight=FontWeight.Black,fontSize=21.sp,
                        modifier=Modifier.testTag("football_compact_score"))
                FootballBadge(awayBadge,match.details.away,Modifier.size(34.dp),colors.mint)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FootballStatus(tr(if(replay)R.string.replay else if(event?.state==MatchState.FINISHED) R.string.full_time else R.string.broadcast_ended),
                    if(replay) Icons.Default.Replay else Icons.Default.CheckCircle,accent)
                Spacer(Modifier.weight(1f))
                Text(tr(if(detailsExpanded)R.string.football_hide_details else R.string.football_match_details),
                    color=colors.muted,fontSize=11.sp)
                Icon(if(detailsExpanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,Modifier.size(19.dp),tint=colors.muted)
            }
            if(detailsExpanded) {
                HorizontalDivider(color=colors.muted.copy(alpha=.15f))
                FootballConfrontation(match,matched,homeBadge,awayBadge,now,Modifier.fillMaxWidth())
                FootballTransmission(model,match,false,now,Modifier.fillMaxWidth(),replay)
                if(replay && event!=null) Text(tr(R.string.original_match,
                    footballDay(event.kickoff,Locale.forLanguageTag(model.language),sportsDisplayZone(model.sportsTimeZone))+" · "+
                        footballTime(event.kickoff,Locale.forLanguageTag(model.language),sportsDisplayZone(model.sportsTimeZone))),
                    fontSize=11.sp,color=colors.muted)
                HorizontalDivider(color=colors.muted.copy(alpha=.15f))
                FootballIncidentsPanel(model,matched,match.details.home,match.details.away)
                FootballChannelActions(model,match.channels,compact=true,programmes=match.channelProgrammes)
            }
        }
    }
}

/** Two compact broadcaster buttons, with every channel and quality variant available in the menu. */
@Composable internal fun FootballChannelActions(model: AuroraViewModel, channels: List<Channel>, compact: Boolean = false,
    programmes:Map<String,Programme> = emptyMap(),featuredChannels:List<Channel> = channels) {
    val colors = LocalAuroraColors.current
    val featured = remember(featuredChannels) { footballFeaturedChannels(featuredChannels) }
    var menuOpen by remember(channels) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        featured.forEachIndexed { index, channel ->
            Surface(onClick = { model.playFootball(channel) },
                modifier = Modifier.weight(1f).heightIn(min = 66.dp)
                    .testTag(if (compact && index == 0) "football_compact_watch" else "football_watch_$index"),
                shape = RoundedCornerShape(14.dp),
                color = colors.mint.copy(alpha = .12f), contentColor = colors.mint) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    var logoFailed by remember(channel.imageUrl) { mutableStateOf(false) }
                    if(channel.imageUrl.isNotBlank() && !logoFailed)
                        AsyncImage(catalogImage(channel.imageUrl),null,
                            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color.White).padding(2.dp),
                            contentScale=ContentScale.Fit,onError={logoFailed=true})
                    else Icon(Icons.Default.PlayCircleFilled, null, Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.watch), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(channel.name, color = colors.muted, fontSize = 10.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                    }
                }
            }
        }
        if (channels.size > 1) Box {
            OutlinedIconButton(onClick = { menuOpen = true }, shape = RoundedCornerShape(13.dp),
                modifier = Modifier.size(44.dp).testTag("football_all_channels"),
                border = BorderStroke(1.dp, colors.muted.copy(alpha = .2f))) {
                Icon(Icons.Default.MoreHoriz, tr(R.string.channels), tint = colors.muted)
            }
            if(menuOpen) FootballChannelPicker(channels, { menuOpen = false },programmes,model) { channel ->
                menuOpen = false
                model.playFootball(channel)
            }
        }
    }
}


@Composable private fun FootballDetailsToggle(expanded: Boolean, onClick: () -> Unit) {
    val colors = LocalAuroraColors.current
    TextButton(onClick = onClick, modifier = Modifier.testTag("football_live_details_toggle")) {
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            null, Modifier.size(19.dp), tint = colors.mint)
        Spacer(Modifier.width(5.dp))
        Text(tr(if (expanded) R.string.football_hide_details else R.string.football_match_details),
            color = colors.mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Shared live / replay / finished fixture events, only requested when details are expanded. */
@Composable private fun FootballIncidentsPanel(
    model: AuroraViewModel, matched: MatchedScore?, homeName: String, awayName: String
) {
    val colors = LocalAuroraColors.current
    val event = matched?.event
    var incidents by remember(event?.id, event?.checked) { mutableStateOf<List<FootballIncident>?>(null) }
    var loading by remember(event?.id, event?.checked) { mutableStateOf(event != null) }
    LaunchedEffect(event?.id, event?.checked) {
        if (event != null) {
            loading = true
            try { incidents = model.footballIncidents(event) }
            finally { loading = false }
        }
    }
    when {
        loading -> CircularProgressIndicator(Modifier.size(22.dp))
        incidents == null -> Text(tr(R.string.football_incidents_unavailable),fontSize=11.sp,color=colors.muted)
        incidents!!.isEmpty() -> Text(tr(R.string.football_incidents_empty),fontSize=11.sp,color=colors.muted)
        else -> Column(Modifier.testTag("football_incidents"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            incidents!!.forEach { item ->
                val sameOrientation = matched?.home?.id == event?.home?.id
                val homeSide = if (sameOrientation) item.isHome else !item.isHome
                val symbol = when (item.kind) {
                    FootballIncidentKind.GOAL -> "⚽"
                    FootballIncidentKind.YELLOW_CARD -> "🟨"
                    FootballIncidentKind.RED_CARD -> "🟥"
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(symbol,fontSize=14.sp)
                    Text(item.minute,fontSize=11.sp,color=colors.muted)
                    Text(item.player,Modifier.weight(1f),fontSize=12.sp,maxLines=2,
                        overflow=TextOverflow.Ellipsis)
                    Text(if(homeSide) homeName else awayName,fontSize=10.sp,color=colors.muted,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Aurora-themed, scrollable channel selector replacing Android's tall default popup. */
@Composable private fun FootballChannelPicker(
    channels: List<Channel>, dismiss: () -> Unit, programmes:Map<String,Programme>,model:AuroraViewModel,
    select: (Channel) -> Unit
) {
    val colors = LocalAuroraColors.current
    val variants = remember(channels) { channels.distinctBy { it.url } }
    Dialog(onDismissRequest = dismiss,properties = DialogProperties(usePlatformDefaultWidth=false)) {
        Card(Modifier.fillMaxWidth(.92f).widthIn(max=470.dp),
            shape=RoundedCornerShape(24.dp),
            border=BorderStroke(1.dp,colors.mint.copy(alpha=.28f)),
            colors=CardDefaults.cardColors(containerColor=colors.panel)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.LiveTv,null,tint=colors.mint,modifier=Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr(R.string.channels),fontSize=19.sp,fontWeight=FontWeight.Bold,
                        color=colors.text,modifier=Modifier.weight(1f))
                    Text(variants.size.toString(),fontSize=12.sp,color=colors.muted)
                    IconButton(onClick=dismiss,modifier=Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close,null,tint=colors.muted)
                    }
                }
                HorizontalDivider(color=colors.muted.copy(alpha=.18f))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=490.dp)
                    .testTag("football_channel_picker"),
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(variants,key={it.url}) { channel ->
                        val quality = remember(channel.name) {
                            when (footballChannelQuality(channel.name)) {
                                4 -> "4K"
                                3 -> "FHD"
                                2 -> "HD"
                                else -> if (Regex("(?i)\\bSD\\b").containsMatchIn(channel.name)) "SD" else ""
                            }
                        }
                        Surface(onClick={select(channel)},modifier=Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(13.dp),
                            color=colors.elevated.copy(alpha=.86f),
                            border=BorderStroke(1.dp,colors.muted.copy(alpha=.12f))) {
                            Row(Modifier.padding(horizontal=12.dp,vertical=12.dp),
                                verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                var logoFailed by remember(channel.imageUrl) { mutableStateOf(false) }
                                if(channel.imageUrl.isNotBlank() && !logoFailed)
                                    AsyncImage(catalogImage(channel.imageUrl),null,
                                        Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                                            .background(Color.White).padding(2.dp),
                                        contentScale=ContentScale.Fit,onError={logoFailed=true})
                                else Icon(Icons.Default.PlayCircleFilled,null,
                                    tint=colors.mint,modifier=Modifier.size(23.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(channel.name,fontSize=12.sp,maxLines=2,lineHeight=16.sp,
                                        overflow=TextOverflow.Ellipsis,color=colors.text)
                                    programmes[channel.url]?.let { p ->
                                        val locale=Locale.forLanguageTag(model.language)
                                        val zone=sportsDisplayZone(model.sportsTimeZone)
                                        Text(footballDay(p.start,locale,zone)+" · "+footballTime(p.start,locale,zone)+" – "+
                                            footballTime(p.end,locale,zone),fontSize=10.sp,color=colors.muted)
                                    }
                                }
                                if (quality.isNotBlank()) {
                                    Text(quality,fontSize=10.sp,color=colors.mint,
                                        modifier=Modifier.clip(RoundedCornerShape(7.dp))
                                            .background(colors.mint.copy(alpha=.13f))
                                            .padding(horizontal=7.dp,vertical=5.dp))
                                }
                                Icon(Icons.Default.ChevronRight,null,
                                    tint=colors.muted,modifier=Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FootballStatus(label: String, icon: ImageVector, accent: Color) {
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = .13f)).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(11.dp), tint = accent)
        Text(label, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun FootballConfrontation(match: FootballMatch, matched: MatchedScore?, homeBadge: String, awayBadge: String, now: Long, modifier: Modifier) {
    val broadcastLive=footballHasLiveTransmission(match,matched?.event,now)
    BoxWithConstraints(modifier.testTag("football_confrontation")) {
        val dense = maxWidth < 300.dp
        val stacked = LocalDensity.current.fontScale > 1.4f || maxWidth < 240.dp
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FootballTeam(match.details.home, homeBadge, Modifier.weight(1f), dense)
                FootballTeam(match.details.away, awayBadge, Modifier.weight(1f), dense)
            }
            FootballScoreboard(matched, now, Modifier.fillMaxWidth(), dense,broadcastLive)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FootballTeam(match.details.home, homeBadge, Modifier.weight(1f), dense)
            FootballScoreboard(matched, now, Modifier.width(if (dense) 94.dp else 112.dp), dense,broadcastLive)
            FootballTeam(match.details.away, awayBadge, Modifier.weight(1f), dense)
        }
    }
}

@Composable private fun FootballScoreboard(matched: MatchedScore?, now: Long, modifier: Modifier, dense: Boolean,
    broadcastLive:Boolean) {
    val colors = LocalAuroraColors.current
    val event = matched?.event
    val live = broadcastLive && event != null && event.state in listOf(MatchState.LIVE, MatchState.HALFTIME) && scoreIsFresh(event, now)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) FootballStatus(tr(R.string.live_score), Icons.Default.Sensors, colors.mint)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (matched?.homeGoals != null && matched.awayGoals != null) {
                Text(matched.homeGoals.toString(), Modifier.testTag("football_home_goals"), fontSize = if (dense) 30.sp else 36.sp, fontWeight = FontWeight.Black)
                Text("–", color = colors.mint.copy(alpha = .8f), fontSize = 19.sp, fontWeight = FontWeight.Light)
                Text(matched.awayGoals.toString(), Modifier.testTag("football_away_goals"), fontSize = if (dense) 30.sp else 36.sp, fontWeight = FontWeight.Black)
            } else Text("VS", Modifier.clip(RoundedCornerShape(12.dp)).background(colors.mint.copy(alpha = .08f)).padding(horizontal = 14.dp, vertical = 10.dp),
                color = colors.mint, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Text(if (live) {
            if (event!!.state == MatchState.HALFTIME) tr(R.string.halftime) else event.clock.ifBlank { tr(R.string.now) }
        } else tr(when (event?.state) {
            MatchState.FINISHED -> R.string.full_time
            MatchState.POSTPONED -> R.string.postponed
            MatchState.CANCELLED -> R.string.match_cancelled
            MatchState.SCHEDULED -> R.string.match_scheduled
            MatchState.LIVE, MatchState.HALFTIME -> if(scoreIsFresh(event,now))R.string.broadcast_unconfirmed else R.string.stale_score
            else -> R.string.score_unavailable
        }), fontSize = if (live) 13.sp else 10.sp, fontWeight = if (live) FontWeight.Bold else FontWeight.Medium,
            color = if (live) colors.mint else colors.muted, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable private fun FootballTransmission(model: AuroraViewModel, match: FootballMatch, live: Boolean, now: Long, modifier: Modifier, replay: Boolean = false) {
    val colors = LocalAuroraColors.current
    val locale = Locale.forLanguageTag(model.language)
    val zone = sportsDisplayZone(model.sportsTimeZone)
    Row(modifier.testTag("football_broadcast").clip(RoundedCornerShape(16.dp)).background(colors.elevated.copy(alpha = .55f))
        .border(1.dp, colors.muted.copy(alpha = .08f), RoundedCornerShape(16.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (live) Icons.Default.Sensors else Icons.Default.CalendarMonth, null, Modifier.size(18.dp).padding(top = 2.dp), tint = colors.mint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.broadcast) + ":",color=colors.muted,fontSize=10.sp,fontWeight=FontWeight.Medium)
                if(replay) Surface(Modifier.testTag("football_replay_badge"),
                    shape=RoundedCornerShape(6.dp),color=Color(0xFF7130A7)) {
                    Text(tr(R.string.replay).uppercase(locale),Modifier.padding(horizontal=8.dp,vertical=3.dp),
                        color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Black)
                }
            }
            if (live) {
                val transition = rememberInfiniteTransition(label = "live transmission")
                val opacity by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "live dot")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(tr(R.string.live_score).uppercase(locale), color = colors.mint, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Box(Modifier.size(8.dp).alpha(opacity).clip(CircleShape).background(colors.mint).testTag("football_live_dot"))
                }
            } else {
                Text(footballDay(match.programme.start, locale,zone), color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(R.string.broadcast_time, footballTime(match.programme.start, locale,zone)+" – "+footballTime(match.programme.end, locale,zone)), color = colors.mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(tr(if(match.broadcastEnd<=now) R.string.broadcast_ended else if(match.programme.start>now)
                    R.string.broadcast_scheduled else R.string.broadcast_unconfirmed),fontSize=10.sp,color=colors.muted)
            }
            if(match.channelProgrammes.values.distinctBy { Triple(it.start,it.end,it.broadcast) }.size>1) {
                val score=model.sportsScores[match.id]?.event
                match.channels.distinctBy { guideChannelName(it.name) to match.programmeFor(it) }.forEach { channel ->
                    val p=match.programmeFor(channel)
                    val status=tr(when {
                        transmissionIsLive(p,score,now)->R.string.live_score
                        footballExplicitReplay(p)->R.string.replay
                        p.end<=now->R.string.broadcast_ended
                        p.start>now->R.string.broadcast_scheduled
                        else->R.string.broadcast_unconfirmed
                    })
                    Text(channel.name+" · "+footballDay(p.start,locale,zone)+" · "+footballTime(p.start,locale,zone)+" – "+
                        footballTime(p.end,locale,zone)+" · "+status,fontSize=10.sp,color=colors.muted)
                }
            }
        }
    }
}

@Composable private fun FootballTeam(name: String, badge: String, modifier: Modifier, dense: Boolean) {
    val colors = LocalAuroraColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FootballBadge(badge, name, Modifier.size(if (dense) 60.dp else 72.dp), colors.mint)
        Text(name, fontWeight = FontWeight.Bold, fontSize = if (dense) 12.sp else 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp))
    }
}

@Composable private fun FootballBadge(image: String, name: String, modifier: Modifier, accent: Color, competition: Boolean = false) {
    val colors = LocalAuroraColors.current
    var loaded by remember(image) { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (competition) 12.dp else 21.dp)
    Box(modifier.clip(shape).background(if (loaded) Color(0xFFF1F5FA) else colors.elevated.copy(alpha = .75f))
        .border(1.dp, accent.copy(alpha = if (loaded) .24f else .3f), shape).padding(if (competition) 5.dp else 9.dp), contentAlignment = Alignment.Center) {
        if (!loaded) {
            if (competition) Icon(Icons.Default.EmojiEvents, null, Modifier.fillMaxSize().padding(2.dp), tint = accent.copy(alpha = .8f))
            else {
                Canvas(Modifier.fillMaxSize()) {
                    val shield = Path().apply {
                        moveTo(size.width * .5f, 0f); lineTo(size.width, size.height * .16f); lineTo(size.width * .9f, size.height * .64f)
                        quadraticBezierTo(size.width * .78f, size.height * .9f, size.width * .5f, size.height)
                        quadraticBezierTo(size.width * .22f, size.height * .9f, size.width * .1f, size.height * .64f)
                        lineTo(0f, size.height * .16f); close()
                    }
                    drawPath(shield, Brush.linearGradient(listOf(accent.copy(alpha = .28f), colors.violet.copy(alpha = .22f))))
                }
                Text(name.split(Regex("\\s+")).filter { it.length > 1 }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""),
                    fontSize = 13.sp, fontWeight = FontWeight.Black, color = accent)
            }
        }
        if (image.isNotBlank()) AsyncImage(catalogImage(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
