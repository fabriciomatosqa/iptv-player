package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Motorsport is a timed multi-participant event, not a match between two teams.
 * Keep the card truthful when the EPG does not provide telemetry or results.
 */
@Composable internal fun MotorsportEventCard(model: AuroraViewModel, event: SportsEvent, now: Long) {
    val colors=LocalAuroraColors.current
    val details=motorsportCardDetails(event.programme,event.competition)
    val programme=event.programme
    val started=now>=programme.start
    val ended=now>=programme.end
    val replay=programme.broadcast==BroadcastHint.REPLAY
    val live=!replay && started && !ended && programme.broadcast==BroadcastHint.LIVE
    val showing=!replay && started && !ended && !live
    val label=when {
        ended -> "ENCERRADO"
        replay -> "REPRISE"
        live -> "AO VIVO"
        showing -> "NA GRADE"
        else -> "EM BREVE"
    }
    val accent=when {
        replay && !ended -> colors.violet
        live -> colors.mint
        showing -> colors.cyan
        else -> colors.muted
    }

    Card(
        modifier=Modifier.fillMaxWidth().testTag("motorsport_event"),
        shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=colors.panel),
        border=BorderStroke(1.dp,accent.copy(alpha=if(live).65f else .27f))
    ) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(0.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(accent.copy(alpha=.7f)))
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(43.dp).clip(RoundedCornerShape(13.dp))
                            .background(colors.mint.copy(alpha=.13f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.SportsMotorsports,null,tint=colors.mint,
                            modifier=Modifier.size(26.dp))
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(event.competition,fontSize=13.sp,color=colors.text,
                            fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("AUTOMOBILISMO · PROGRAMAÇÃO",fontSize=10.sp,color=colors.muted,
                            maxLines=1)
                    }
                    Surface(color=accent.copy(alpha=.12f),
                        shape=RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(live) Icon(Icons.Default.Sensors,null,Modifier.size(13.dp),tint=accent)
                            Text(label,fontSize=10.sp,color=accent,fontWeight=FontWeight.Bold)
                        }
                    }
                }
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(details.stage,fontSize=20.sp,fontWeight=FontWeight.Bold,
                        color=colors.text,maxLines=2,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.testTag("motorsport_stage"))
                    Row(verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Flag,null,tint=colors.mint,modifier=Modifier.size(17.dp))
                        Text(details.session,fontSize=14.sp,color=colors.mint,
                            fontWeight=FontWeight.SemiBold,
                            modifier=Modifier.testTag("motorsport_session"))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(colors.elevated.copy(alpha=.6f)).padding(12.dp),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CalendarToday,null,tint=colors.muted,
                            modifier=Modifier.size(15.dp))
                        Text(footballDay(programme.start,Locale.forLanguageTag(model.language)),
                            fontSize=12.sp,color=colors.text,maxLines=2)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Schedule,null,tint=colors.mint,
                            modifier=Modifier.size(15.dp))
                        Text(footballTime(programme.start,Locale.forLanguageTag(model.language)),
                            fontSize=15.sp,fontWeight=FontWeight.Bold,color=colors.mint)
                        Text("horário do aparelho",fontSize=10.sp,color=colors.muted)
                    }
                    if(showing) Text("No horário da grade; transmissão ao vivo não confirmada.",
                        color=colors.muted,fontSize=11.sp)
                    if(replay && !ended) Text("Reexibição identificada na programação do canal.",
                        color=colors.muted,fontSize=11.sp)
                }
                if(!ended) {
                    HorizontalDivider(color=colors.muted.copy(alpha=.14f))
                    FootballChannelActions(model,event.channels)
                } else {
                    Text("A faixa da grade terminou. Resultados e posições não foram informados.",
                        color=colors.muted,fontSize=11.sp)
                }
            }
        }
    }
}
