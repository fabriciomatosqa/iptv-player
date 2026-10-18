package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** No invented live scores for leagues without a dedicated score provider. */
@Composable internal fun SportsEventCard(model:AuroraViewModel,event:SportEvent,now:Long) {
    val colors=LocalAuroraColors.current
    val live=event.programme.broadcast!=BroadcastHint.REPLAY &&
        event.programme.start<=now && event.programme.end>now
    val replay=event.programme.broadcast==BroadcastHint.REPLAY
    val locale=Locale.forLanguageTag(model.language)
    val accent=if(replay)colors.violet else colors.mint
    Card(modifier=Modifier.fillMaxWidth().testTag("sports_event_card"),
        shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,accent.copy(alpha=.28f)),
        colors=CardDefaults.cardColors(containerColor=colors.panel)) {
        Column(Modifier.fillMaxWidth().padding(17.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.EmojiEvents,null,tint=colors.cyan,modifier=Modifier.size(23.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.league.title,color=colors.text,fontWeight=FontWeight.SemiBold,
                        fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(event.sport.label,color=colors.muted,fontSize=11.sp)
                }
                if(live) {
                    Text(tr(R.string.now),fontSize=11.sp,fontWeight=FontWeight.Bold,
                        color=colors.mint,modifier=Modifier.clip(RoundedCornerShape(8.dp))
                            .background(colors.mint.copy(alpha=.13f))
                            .padding(horizontal=8.dp,vertical=5.dp))
                } else if(replay) {
                    Icon(Icons.Default.Replay,tr(R.string.replay),tint=colors.violet,modifier=Modifier.size(18.dp))
                }
            }
            if(event.home.isNotBlank() && event.away.isNotBlank()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(event.home,Modifier.weight(1f),textAlign=TextAlign.Center,
                        fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=3,
                        overflow=TextOverflow.Ellipsis)
                    Text("VS",color=colors.mint,fontSize=19.sp,fontWeight=FontWeight.Black,
                        modifier=Modifier.testTag("sports_event_vs"))
                    Text(event.away,Modifier.weight(1f),textAlign=TextAlign.Center,
                        fontWeight=FontWeight.Bold,fontSize=16.sp,maxLines=3,
                        overflow=TextOverflow.Ellipsis)
                }
            } else {
                Text(event.title,modifier=Modifier.fillMaxWidth(),fontSize=17.sp,
                    fontWeight=FontWeight.Bold,color=colors.text,maxLines=3,
                    overflow=TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(colors.elevated.copy(alpha=.6f))
                .border(1.dp,colors.muted.copy(alpha=.1f),RoundedCornerShape(14.dp))
                .padding(12.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(if(live)Icons.Default.Sensors else Icons.Default.CalendarMonth,null,
                    tint=colors.mint,modifier=Modifier.size(18.dp))
                Column {
                    Text(footballDay(event.programme.start,locale),fontSize=12.sp,
                        color=colors.text,fontWeight=FontWeight.SemiBold)
                    Text(tr(R.string.broadcast_time,footballTime(event.programme.start,locale)),
                        fontSize=13.sp,color=colors.mint,fontWeight=FontWeight.Bold)
                    if(live) Text(tr(R.string.now_on_channel),fontSize=11.sp,color=colors.muted)
                    if(replay) Text(tr(R.string.replay),fontSize=11.sp,color=colors.violet)
                }
            }
            HorizontalDivider(color=colors.muted.copy(alpha=.13f))
            FootballChannelActions(model,event.channels)
        }
    }
}
