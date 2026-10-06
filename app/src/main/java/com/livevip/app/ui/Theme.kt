package com.livevip.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LiveVipColors = darkColorScheme(
    primary = Color(0xFFFF2D55),
    onPrimary = Color.White,
    secondary = Color(0xFF4FC3F7),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF15151C),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF1E1E28),
    error = Color(0xFFFF5252)
)

@Composable
fun LiveVipTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = LiveVipColors, content = content)
}
