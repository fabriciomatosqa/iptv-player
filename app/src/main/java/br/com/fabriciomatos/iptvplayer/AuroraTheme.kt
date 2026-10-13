package br.com.fabriciomatos.iptvplayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppAppearance { SYSTEM, LIGHT, DARK }

internal data class AuroraColors(
    val background: Color, val panel: Color, val elevated: Color,
    val cyan: Color, val mint: Color, val violet: Color,
    val text: Color, val muted: Color, val gold: Color,
    val glow: Color, val sky: Color
)

private val DarkAurora = AuroraColors(
    Color(0xFF080D19), Color(0xFF131D2D), Color(0xFF1B293E),
    Color(0xFF56D8F9), Color(0xFF51E5BE), Color(0xFFA28AFF),
    Color(0xFFF1F6FE), Color(0xFF99ACC6), Color(0xFFFFD378),
    Color(0xFF102A35), Color(0xFF19142E)
)
private val LightAurora = AuroraColors(
    Color(0xFFF4F9FC), Color(0xFFFFFFFF), Color(0xFFE2EDF3),
    Color(0xFF087C98), Color(0xFF007B65), Color(0xFF6850B8),
    Color(0xFF152638), Color(0xFF526777), Color(0xFF916000),
    Color(0xFFDCF5ED), Color(0xFFEDE5FA)
)
internal val LocalAuroraColors = staticCompositionLocalOf { DarkAurora }

@Composable internal fun AuroraTheme(appearance: AppAppearance, content: @Composable (Boolean) -> Unit) {
    val dark = when(appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    val colors = if(dark) DarkAurora else LightAurora
    val scheme = if(dark) darkColorScheme(
        primary=colors.mint,secondary=colors.cyan,tertiary=colors.violet,
        background=colors.background,surface=colors.panel,onPrimary=colors.background,
        onBackground=colors.text,onSurface=colors.text,onSurfaceVariant=colors.muted,
        outline=colors.muted,surfaceVariant=colors.elevated
    ) else lightColorScheme(
        primary=colors.mint,secondary=colors.cyan,tertiary=colors.violet,
        background=colors.background,surface=colors.panel,onPrimary=Color.White,
        onBackground=colors.text,onSurface=colors.text,onSurfaceVariant=colors.muted,
        outline=colors.muted,surfaceVariant=colors.elevated
    )
    CompositionLocalProvider(LocalAuroraColors provides colors) {
        MaterialTheme(colorScheme=scheme) { content(dark) }
    }
}

@Composable internal fun appearanceLabel(appearance: AppAppearance) = tr(when(appearance) {
    AppAppearance.SYSTEM -> R.string.theme_system
    AppAppearance.LIGHT -> R.string.theme_light
    AppAppearance.DARK -> R.string.theme_dark
})
