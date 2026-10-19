package br.com.fabriciomatos.iptvplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPane(model: AuroraViewModel, modifier: Modifier, fullscreen: Boolean = false) {
    val player=model.player
    var visible by remember(player) {mutableStateOf(true)}
    var interaction by remember {mutableIntStateOf(0)}
    var menuOpen by remember {mutableStateOf(false)}
    var tracks by remember(player) {mutableStateOf(player?.currentTracks ?: Tracks.EMPTY)}
    var position by remember(player) {mutableLongStateOf(0)}
    var duration by remember(player) {mutableLongStateOf(0)}
    fun reveal() {visible=true;interaction++}
    val remoteLandscape = fullscreen &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // DPAD wakes a faded control layer. Center/Enter first reveals it instead of
    // accidentally activating a control the user cannot see.
    val remoteControls = if (remoteLandscape) {
        Modifier.onPreviewKeyEvent { event ->
            val isNavigation = event.key in setOf(
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                Key.DirectionCenter, Key.Enter
            )
            if (event.type == KeyEventType.KeyDown && isNavigation) {
                val wasHidden = !visible
                reveal()
                wasHidden && (event.key == Key.DirectionCenter || event.key == Key.Enter)
            } else false
        }.focusable()
    } else Modifier
    DisposableEffect(player) {
        val listener=object: Player.Listener { override fun onTracksChanged(value: Tracks) {tracks=value} }
        player?.addListener(listener)
        onDispose {player?.removeListener(listener)}
    }
    LaunchedEffect(player) {
        while(true) {position=player?.currentPosition ?: 0;duration=(player?.duration ?: 0).takeIf {it!=C.TIME_UNSET} ?: 0;delay(500)}
    }
    LaunchedEffect(interaction,model.isPlaying,menuOpen) {
        if(!model.isPlaying || menuOpen) {visible=true;return@LaunchedEffect}
        delay(3000);visible=false
    }
    Box(modifier.then(remoteControls).testTag("video_surface").clip(RoundedCornerShape(if(fullscreen)0.dp else 12.dp)).background(Color.Black)
        .pointerInput(player) {detectTapGestures(onTap={reveal()})}) {
        if(player!=null) AndroidView(factory={context->PlayerView(context).apply {
            this.player=player;keepScreenOn=true;useController=false
            resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }},update={it.player=player},onRelease={it.player=null;it.keepScreenOn=false},modifier=Modifier.fillMaxSize())
        AnimatedVisibility(visible,enter=fadeIn(tween(120)),exit=fadeOut(tween(2000)),modifier=Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("video_controls")) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.65f),Color.Transparent)))) {
                    if(fullscreen)IconButton(onClick={model.fullscreen=false;reveal()},modifier=Modifier.testTag("video_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,tr(R.string.back),tint=Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={model.fullscreen=!model.fullscreen;reveal()},modifier=Modifier.testTag("video_fullscreen")) {
                        Icon(if(fullscreen)Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            tr(if(fullscreen)R.string.exit_fullscreen else R.string.fullscreen),tint=Color.White)
                    }
                }
                if(player!=null) {
                    Row(Modifier.align(Alignment.Center),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(model.current?.kind!=MediaKind.LIVE)IconButton(onClick={player.seekBack();reveal()}) {
                            Icon(Icons.Default.Replay10,tr(R.string.rewind),tint=Color.White)
                        }
                        IconButton(onClick={if(player.isPlaying)player.pause()else player.play();reveal()},modifier=Modifier.size(56.dp)) {
                            Icon(if(model.isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,
                                tr(if(model.isPlaying)R.string.pause else R.string.watch),Modifier.size(36.dp),tint=Color.White)
                        }
                        if(model.current?.kind!=MediaKind.LIVE)IconButton(onClick={player.seekForward();reveal()}) {
                            Icon(Icons.Default.Forward10,tr(R.string.forward),tint=Color.White)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.87f)))).padding(horizontal=12.dp)) {
                        var drag by remember(player) {mutableStateOf<Float?>(null)}
                        val seekable=duration>0 && model.current?.kind!=MediaKind.LIVE
                        Slider(value=drag ?: if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f)else 0f,
                            onValueChange={drag=it;reveal()},onValueChangeFinished={drag?.let {player.seekTo((it*duration).toLong())};drag=null;reveal()},
                            enabled=seekable,modifier=Modifier.fillMaxWidth().height(26.dp),
                            colors=SliderDefaults.colors(thumbColor=MaterialTheme.colorScheme.primary,activeTrackColor=MaterialTheme.colorScheme.primary))
                        Row(Modifier.fillMaxWidth().testTag("video_time"),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(if(model.current?.kind==MediaKind.LIVE)tr(R.string.live)else playbackTime(position),color=Color.White,fontSize=11.sp)
                            if(seekable)Text(playbackTime(duration),color=Color.White,fontSize=11.sp)
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            TrackMenu(player,tracks,C.TRACK_TYPE_AUDIO,{menuOpen=it},::reveal)
                            TrackMenu(player,tracks,C.TRACK_TYPE_TEXT,{menuOpen=it},::reveal)
                        }
                    }
                }
            }
        }
        if(model.buffering && model.playbackError==null)CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),strokeWidth=2.dp)
        model.playbackError?.let {error->Column(Modifier.align(Alignment.Center).background(Bg.copy(alpha=.9f)).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(error,fontSize=12.sp,maxLines=3)
            model.playbackErrorDetails?.let { details ->
                Text(details,fontSize=11.sp,color=MaterialTheme.colorScheme.primary,maxLines=3,modifier=Modifier.testTag("playback_error_details"))
            }
            TextButton(onClick=model::retry){Text(tr(R.string.retry))}
        }}
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TrackMenu(player: Player, tracks: Tracks, type: Int, menuChanged: (Boolean)->Unit, reveal: ()->Unit) {
    val options=tracks.groups.filter {it.type==type}.flatMap {g->(0 until g.length).filter(g::isTrackSupported).map {g to it}}
    if(options.isEmpty())return
    var expanded by remember(player,type) {mutableStateOf(false)}
    val text=type==C.TRACK_TYPE_TEXT
    val selected=options.firstOrNull {it.first.isTrackSelected(it.second)}
    val name: @Composable (Pair<Tracks.Group,Int>)->String = {option->
        val f=option.first.getTrackFormat(option.second)
        f.label?.takeIf(String::isNotBlank) ?: f.language?.takeUnless {it=="und"}?.let {
            Locale.forLanguageTag(it.replace('_','-')).getDisplayLanguage(Locale.getDefault()).replaceFirstChar {c->c.titlecase(Locale.getDefault())}
        } ?: tr(R.string.track,options.indexOf(option)+1)
    }
    val heading=tr(if(text)R.string.subtitles else R.string.audio_language)
    val current=selected?.let {name(it)} ?: tr(if(text)R.string.subtitles_off else R.string.automatic)
    Box {
        TextButton(onClick={expanded=true;menuChanged(true);reveal()},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp),modifier=Modifier.testTag(if(text)"subtitle_menu" else "audio_menu")) {
            Icon(if(text)Icons.Default.Subtitles else Icons.Default.Language,null,Modifier.size(18.dp),tint=Color.White)
            Spacer(Modifier.width(5.dp));Text("$heading: $current",color=Color.White,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false;menuChanged(false);reveal()},modifier=Modifier.heightIn(max=260.dp)) {
            DropdownMenuItem(text={Text(tr(if(text)R.string.subtitles_off else R.string.automatic))},onClick={
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,text).build()
                expanded=false;menuChanged(false);reveal()
            })
            options.forEach {option->DropdownMenuItem(text={Text(name(option))},leadingIcon={if(option.first.isTrackSelected(option.second))Icon(Icons.Default.Check,null)},onClick={
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type,false)
                    .setOverrideForType(TrackSelectionOverride(option.first.mediaTrackGroup,option.second)).build()
                expanded=false;menuChanged(false);reveal()
            })}
        }
    }
}
private fun playbackTime(milliseconds: Long): String {
    val seconds=milliseconds.coerceAtLeast(0)/1000
    return if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
}
