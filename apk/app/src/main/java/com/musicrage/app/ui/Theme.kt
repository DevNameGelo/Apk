package com.musicrage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
