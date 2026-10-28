package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.util.Locale

@Composable internal fun ResumePlaybackDialog(model: AuroraViewModel) {
    val request=model.pendingPlayback ?: return
    AlertDialog(
        onDismissRequest=model::dismissPlaybackResume,
        modifier=Modifier.testTag("resume_playback_dialog"),
        title={Text(tr(R.string.resume_playback_title))},
        text={Text(tr(R.string.resume_playback_message,request.channel.name,
            resumePlaybackTime(request.progress.resumePositionMs)))},
        confirmButton={
            TextButton(onClick={model.confirmPlaybackResume(false)},
                modifier=Modifier.testTag("resume_playback_continue")) {
                Text(tr(R.string.resume_playback_continue))
            }
        },
        dismissButton={Column {
            TextButton(onClick={model.confirmPlaybackResume(true)},
                modifier=Modifier.testTag("resume_playback_start")) {
                Text(tr(R.string.resume_playback_start))
            }
            TextButton(onClick=model::dismissPlaybackResume,
                modifier=Modifier.testTag("resume_playback_cancel")) {
                Text(tr(R.string.cancel))
            }
        }}
    )
}

internal fun resumePlaybackTime(milliseconds: Long): String {
    val seconds=milliseconds.coerceAtLeast(0)/1000
    return if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
}
