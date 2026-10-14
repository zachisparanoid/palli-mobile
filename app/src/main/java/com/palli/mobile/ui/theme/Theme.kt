package com.palli.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The web door's tokens (website/src/app/globals.css), as colours over black. */
object PalliColors {
    val Background = Color(0xFF000000)
    val Card = Color(0xFF0D0D0D)          // white 5% over black
    val CardBorder = Color(0xFF1A1A1A)    // white 10% over black
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0x99FFFFFF)         // white 60%
    val Faint = Color(0x66FFFFFF)         // white 40%
    val Accent = Color(0xFF38BDF8)        // sky-400
    val AccentDeep = Color(0xFF0EA5E9)    // sky-500
    val MineBubble = Color(0xFF06131A)    // sky-400 10% over black
    val MineBorder = Color(0xFF042A3A)    // sky-500 25% over black
    val Ok = Color(0xFF6EE7B7)            // emerald-300
    val Warn = Color(0xFFFCD34D)          // amber-300
    val Trouble = Color(0xFFFCA5A5)       // red-300
}

private val Scheme = darkColorScheme(
    primary = PalliColors.Accent,
    onPrimary = Color(0xFF00131D),
    secondary = PalliColors.AccentDeep,
    background = PalliColors.Background,
    onBackground = PalliColors.Text,
    surface = PalliColors.Background,
    onSurface = PalliColors.Text,
    surfaceVariant = PalliColors.Card,
    onSurfaceVariant = PalliColors.Muted,
    outline = PalliColors.CardBorder,
    error = PalliColors.Trouble,
)

@Composable
fun PalliTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
