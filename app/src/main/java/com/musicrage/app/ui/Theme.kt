package com.musicrage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color

object Rage {
    val Bg = Color(0xFF0B0B0E)
    val Surface = Color(0xFF17171C)
    val SurfaceHigh = Color(0xFF232329)
    val Accent = Color(0xFFB65BFF)
    val AccentDark = Color(0xFF8A3FCC)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFA9A9B4)
    val Divider = Color(0x1FFFFFFF)
    val Danger = Color(0xFFFF5C7A)
}

private val RageColorScheme = darkColorScheme(
    primary = Rage.Accent,
    onPrimary = Color.Black,
    secondary = Rage.Accent,
    background = Rage.Bg,
    onBackground = Rage.Text,
    surface = Rage.Surface,
    onSurface = Rage.Text,
    surfaceVariant = Rage.SurfaceHigh,
    onSurfaceVariant = Rage.TextDim,
    outline = Rage.Divider
)

@Composable
fun MusicRageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RageColorScheme, content = content)
}

/**
 * Makes the whole UI fit any screen. On a phone narrower than 360 dp everything scales down in proportion (so the
 * layout designed for 360 dp fits without clipping), and the system font size is capped so very large text settings
 * cannot push buttons off the screen. Tablets keep their natural size and get the wide layout instead.
 */
@Composable
fun AdaptiveDensity(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val scale = if (widthDp in 1..359) (widthDp / 360f).coerceAtLeast(0.78f) else 1f
    val adapted = Density(base.density * scale, base.fontScale.coerceAtMost(1.2f))
    CompositionLocalProvider(LocalDensity provides adapted, content = content)
}
