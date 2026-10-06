package com.livevip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livevip.app.core.FitMode
import com.livevip.app.core.StreamState
import com.livevip.app.data.StreamProfile
import com.livevip.app.engine.LiveStreamingEngine
import com.livevip.app.engine.StreamStats

@Composable
fun DashboardScreen(
    profile: StreamProfile,
    engine: LiveStreamingEngine,
    state: StreamState,
    stats: StreamStats,
    maskedKey: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
    onDiagnostics: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< STREAMS") }
            Text(
                state.label(),
                color = when (state) {
                    StreamState.STREAMING -> Color(0xFF4CAF50)
                    StreamState.ERROR, StreamState.NETWORK_LOST -> MaterialTheme.colorScheme.error
                    StreamState.IDLE, StreamState.STOPPED -> MaterialTheme.colorScheme.onSurface
                    else -> Color(0xFFFFC107)
                },
                fontWeight = FontWeight.Black
            )
        }
        Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(profile.summaryLine(), style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (profile.orientation == com.livevip.app.data.Orientation.LANDSCAPE_16_9) 16f / 9f
                    else 9f / 16f
                )
                .background(Color.Black)
        ) {
            PreviewSurface(engine, Modifier.fillMaxSize())
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                engine.updateComposition { it.copy(fitMode = FitMode.FIT) }
            }) { Text("FIT") }
            OutlinedButton(onClick = {
                engine.updateComposition { it.copy(fitMode = FitMode.FILL) }
            }) { Text("FILL") }
            OutlinedButton(onClick = { engine.updateComposition { it.reset() } }) { Text("RESET") }
            OutlinedButton(onClick = onEdit) { Text("EDIT") }
        }

        Spacer(Modifier.height(12.dp))
        if (state.isActive || state == StreamState.ERROR) {
            Button(
                onClick = onStopLive,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("STOP LIVE") }
        } else {
            Button(
                onClick = onStartLive,
                modifier = Modifier.fillMaxWidth(),
                enabled = profile.playlist.isNotEmpty()
            ) { Text("START LIVE") }
            if (profile.playlist.isEmpty()) {
                Text(
                    "Add at least one video before going live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("REAL-TIME (measured values only)")
                StatRow("State", state.label())
                StatRow("Elapsed", stats.elapsedLabel())
                StatRow(
                    "Max duration",
                    if (stats.maxDurationMs > 0) StreamStats.formatDuration(stats.maxDurationMs) else "-"
                )
                StatRow("Encoder FPS", String.format("%.1f", stats.actualFps))
                StatRow("Send bitrate", "${stats.bitrateBps / 1000} kbps")
                StatRow("Sent data", "${stats.bytesSent / 1024} KB")
                StatRow("Packets sent", "${stats.packetsSent}")
                StatRow("Loop", "${stats.loopIndex}")
                StatRow("Playing", stats.currentVideoName)
                StatRow("Reconnects", "${stats.reconnectCount}")
                StatRow("Network", stats.networkTransport)
                StatRow("Server", profile.serverUrl)
                StatRow("Stream key", maskedKey)
                stats.lastError?.let { StatRow("Last error", it) }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDiagnostics) { Text("OPEN DIAGNOSTICS") }
        Spacer(Modifier.height(30.dp))
    }
}
