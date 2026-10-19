package br.com.fabriciomatos.iptvplayer

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp

/**
 * Make directional remote focus visible in landscape while keeping phone portrait
 * rendering and touch targets exactly as they were. Modifier must precede clickable
 * (or a Material clickable component) so the focus observer sees its focus target.
 */
internal fun Modifier.landscapeFocusRing(): Modifier = composed {
    if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE) {
        this
    } else {
        var focused by remember { mutableStateOf(false) }
        val highlight = MaterialTheme.colorScheme.primary
        this.onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) highlight else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    }
}

/** Clickable remains a standard Compose focus target for DPAD/OK and touch. */
internal fun Modifier.landscapeRemoteClickable(onClick: () -> Unit): Modifier =
    this.landscapeFocusRing().clickable(onClick = onClick)
