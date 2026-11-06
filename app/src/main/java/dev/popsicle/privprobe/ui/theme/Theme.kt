package dev.popsicle.privprobe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun schemeOf(p: ProbePalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accent.copy(alpha = 0.16f),
        onPrimaryContainer = p.accent,
        secondary = p.accent,
        onSecondary = p.onAccent,
        background = p.page,
        onBackground = p.ink,
        surface = p.card,
        onSurface = p.ink,
        surfaceVariant = p.soft,
        onSurfaceVariant = p.ink2,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.soft,
        surfaceContainerHighest = p.soft,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.down,
        onError = Color(0xFF0B0B0D),
        errorContainer = p.down.copy(alpha = 0.16f),
        onErrorContainer = p.down,
        scrim = Color(0x66000000),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accent.copy(alpha = 0.08f),
        onPrimaryContainer = p.accent,
        secondary = p.accent,
        onSecondary = p.onAccent,
        background = p.page,
        onBackground = p.ink,
        surface = p.card,
        onSurface = p.ink,
        surfaceVariant = p.soft,
        onSurfaceVariant = p.ink2,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.soft,
        surfaceContainerHighest = p.soft,
        outline = p.lineStrong,
        outlineVariant = p.line,
        error = p.down,
        onError = Color.White,
        errorContainer = p.down.copy(alpha = 0.10f),
        onErrorContainer = p.down,
        scrim = Color(0x66000000),
    )
}

/** 卡片 16dp 圆角，控件 10–12dp。 */
val ProbeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** 浅色 / 深色跟随系统。 */
@Composable
fun PrivacyProbeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalProbePalette provides palette) {
        MaterialTheme(
            colorScheme = schemeOf(palette, dark),
            typography = ProbeTypography,
            shapes = ProbeShapes,
            content = content,
        )
    }
}
