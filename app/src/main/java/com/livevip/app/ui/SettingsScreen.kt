package com.livevip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livevip.app.data.AccentColor
import com.livevip.app.data.AppSettingsState
import com.livevip.app.data.ThemeMode

@Composable
fun SettingsScreen(
    settings: AppSettingsState,
    onChange: ((AppSettingsState) -> AppSettingsState) -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("< BACK") }
        Text("SETTINGS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)

        SectionTitle("APPEARANCE — THEME")
        ChipRow(ThemeMode.entries.toList(), settings.themeMode, { it.name }) { mode ->
            onChange { it.copy(themeMode = mode) }
        }

        SectionTitle("ACCENT COLOR")
        Column {
            AccentColor.entries.toList().chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { accent ->
                        val selected = settings.customAccentRgb == 0L && settings.accent == accent
                        Box(
                            Modifier
                                .size(44.dp)
                                .then(
                                    if (selected) {
                                        Modifier.neonGlow(Color(accent.rgb), cornerRadius = 22.dp, radius = 12.dp)
                                    } else Modifier
                                )
                                .background(Color(accent.rgb), CircleShape)
                                .padding(2.dp)
                        ) {
                            TextButton(
                                onClick = { onChange { it.copy(accent = accent, customAccentRgb = 0L) } },
                                modifier = Modifier.fillMaxSize()
                            ) { Text(if (selected) "✓" else "", color = Color.White) }
                        }
                    }
                }
            }
        }
        Text(
            "Selected: " + if (settings.customAccentRgb != 0L) "CUSTOM" else settings.accent.label,
            style = MaterialTheme.typography.bodySmall
        )
        SectionTitle("CUSTOM ACCENT")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Teal" to 0xFF1DE9B6, "Indigo" to 0xFF536DFE,
                "Magenta" to 0xFFE040FB, "Lime" to 0xFFC6FF00
            ).forEach { (label, rgb) ->
                TextButton(onClick = { onChange { it.copy(customAccentRgb = rgb) } }) {
                    Text(label, color = Color(rgb))
                }
            }
        }

        SectionTitle("GLOW EFFECTS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.glowEnabled,
                onCheckedChange = { value -> onChange { it.copy(glowEnabled = value) } }
            )
            Text("  Neon glow on primary controls")
        }

        SectionTitle("NOTIFICATIONS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.notificationsEnabled,
                onCheckedChange = { value -> onChange { it.copy(notificationsEnabled = value) } }
            )
            Text("  Live status notification")
        }

        SectionTitle("STREAMING DEFAULTS (new profiles)")
        Text("Default bitrate", style = MaterialTheme.typography.bodySmall)
        ChipRow(listOf(1500, 2500, 4000, 6000), settings.defaultBitrateKbps, { "$it kbps" }) { value ->
            onChange { it.copy(defaultBitrateKbps = value) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Default FPS", style = MaterialTheme.typography.bodySmall)
        ChipRow(listOf(24, 25, 30), settings.defaultFps, { "$it" }) { value ->
            onChange { it.copy(defaultFps = value) }
        }

        SectionTitle("DIAGNOSTICS")
        Button(onClick = onDiagnostics, modifier = Modifier.neonGlow(MaterialTheme.colorScheme.primary)) {
            Text("OPEN DEVELOPER DIAGNOSTICS")
        }

        SectionTitle("ABOUT")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                StatRow("App", "LIVE VIP")
                StatRow("Version", com.livevip.app.BuildConfig.VERSION_NAME)
                StatRow("Build", "#" + com.livevip.app.BuildConfig.VERSION_CODE)
                StatRow("Transport", "Direct RTMP / RTMPS (no relay)")
                StatRow("Video", "Hardware H.264 (MediaCodec, CBR)")
                StatRow("Audio", "AAC LC 44.1 kHz")
                StatRow("Engine owner", "Foreground service")
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
