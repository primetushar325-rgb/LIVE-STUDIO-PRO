package com.livevip.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.livevip.app.data.AppSettingsState
import com.livevip.app.data.ThemeMode

/** Glow availability is theme-wide state, never hard-coded per screen. */
val LocalGlowEnabled = staticCompositionLocalOf { true }
val LocalAccentColor = staticCompositionLocalOf { Color(0xFFFF2D55) }

/**
 * ONE centralized theme. Every screen reads colors from MaterialTheme, so
 * changing theme mode or accent instantly restyles the whole app.
 */
@Composable
fun LiveVipTheme(settings: AppSettingsState, content: @Composable () -> Unit) {
    val accent = Color(settings.accentRgb)
    val scheme = when (settings.themeMode) {
        ThemeMode.AMOLED -> darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent.copy(alpha = 0.8f),
            background = Color(0xFF000000),
            onBackground = Color(0xFFF2F2F7),
            surface = Color(0xFF070707),
            onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF101010),
            outline = accent.copy(alpha = 0.45f),
            error = Color(0xFFFF5252)
        )
        ThemeMode.LIGHT -> lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = Color(0xFFF6F6F9),
            onBackground = Color(0xFF14141A),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF14141A),
            surfaceVariant = Color(0xFFECECF2),
            outline = accent.copy(alpha = 0.5f),
            error = Color(0xFFD32F2F)
        )
        ThemeMode.DARK -> darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent.copy(alpha = 0.85f),
            background = Color(0xFF0B0B0F),
            onBackground = Color(0xFFECECF1),
            surface = Color(0xFF15151C),
            onSurface = Color(0xFFECECF1),
            surfaceVariant = Color(0xFF1E1E28),
            outline = accent.copy(alpha = 0.45f),
            error = Color(0xFFFF5252)
        )
    }
    CompositionLocalProvider(
        LocalGlowEnabled provides settings.glowEnabled,
        LocalAccentColor provides accent
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
