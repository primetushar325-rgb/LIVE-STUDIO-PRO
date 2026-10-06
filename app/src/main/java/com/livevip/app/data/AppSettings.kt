package com.livevip.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, AMOLED, LIGHT }

enum class AccentColor(val label: String, val rgb: Long) {
    RED("Red", 0xFFFF3B30),
    PINK("Pink", 0xFFFF2D55),
    PURPLE("Purple", 0xFF9C27B0),
    BLUE("Blue", 0xFF2979FF),
    CYAN("Cyan", 0xFF00E5FF),
    GREEN("Green", 0xFF00E676),
    ORANGE("Orange", 0xFFFF9100),
    GOLD("Gold", 0xFFFFC400)
}

data class AppSettingsState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.PINK,
    val customAccentRgb: Long = 0L,
    val glowEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val defaultBitrateKbps: Int = 2500,
    val defaultFps: Int = 30
) {
    val accentRgb: Long get() = if (customAccentRgb != 0L) customAccentRgb else accent.rgb
}

/** Centralized app settings: theme, accent, glow and streaming defaults. */
class AppSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("live_vip_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettingsState> = _state

    private fun load(): AppSettingsState = AppSettingsState(
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString("theme", ThemeMode.DARK.name)!!)
        }.getOrDefault(ThemeMode.DARK),
        accent = runCatching {
            AccentColor.valueOf(prefs.getString("accent", AccentColor.PINK.name)!!)
        }.getOrDefault(AccentColor.PINK),
        customAccentRgb = prefs.getLong("customAccent", 0L),
        glowEnabled = prefs.getBoolean("glow", true),
        notificationsEnabled = prefs.getBoolean("notifications", true),
        defaultBitrateKbps = prefs.getInt("defaultBitrate", 2500),
        defaultFps = prefs.getInt("defaultFps", 30)
    )

    fun update(block: (AppSettingsState) -> AppSettingsState) {
        val next = block(_state.value)
        prefs.edit()
            .putString("theme", next.themeMode.name)
            .putString("accent", next.accent.name)
            .putLong("customAccent", next.customAccentRgb)
            .putBoolean("glow", next.glowEnabled)
            .putBoolean("notifications", next.notificationsEnabled)
            .putInt("defaultBitrate", next.defaultBitrateKbps)
            .putInt("defaultFps", next.defaultFps)
            .apply()
        _state.value = next
    }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
