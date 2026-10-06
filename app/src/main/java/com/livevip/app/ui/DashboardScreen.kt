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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.livevip.app.data.Orientation
import com.livevip.app.data.StreamProfile
import com.livevip.app.engine.LiveStreamingEngine
import com.livevip.app.engine.StreamStats

/**
 * Stream dashboard: preview + transform controls + REAL live control panel.
 * Every value shown here comes from the engine state flow, which is owned by
 * the foreground service, so it stays correct across activity recreation.
 */
@Composable
fun DashboardScreen(
    profile: StreamProfile,
    engine: LiveStreamingEngine,
    state: StreamState,
    stats: StreamStats,
    maskedKey: String,
    validationError: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
    onDiagnostics: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // Scrollable content…
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("< STREAMS") }
                StatusPill(state)
            }
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(profile.summaryLine(), style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(10.dp))
            // Preview is capped in height so the live controls always stay reachable,
            // even for a 9:16 output on a small phone.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .aspectRatio(
                        if (profile.orientation == Orientation.LANDSCAPE_16_9) 16f / 9f else 9f / 16f
                    )
                    .neonGlow(
                        if (state.isLive) statusColor(state) else MaterialTheme.colorScheme.outline,
                        cornerRadius = 12.dp,
                        radius = if (state.isLive) 18.dp else 8.dp
                    )
                    .background(Color.Black, RoundedCornerShape(12.dp))
            ) {
                PreviewSurface(engine, Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(8.dp))
            ResponsiveActions(
                listOf(
                    "FIT" to { engine.updateComposition { it.copy(fitMode = FitMode.FIT) } },
                    "FILL" to { engine.updateComposition { it.copy(fitMode = FitMode.FILL) } },
                    "RESET" to { engine.updateComposition { it.reset() } },
                    "EDIT" to onEdit
                )
            )

            Spacer(Modifier.height(14.dp))
            LiveControlPanel(
                profile = profile,
                state = state,
                stats = stats,
                maskedKey = maskedKey,
                validationError = validationError,
                onStartLive = onStartLive,
                onStopLive = onStopLive
            )

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDiagnostics) { Text("OPEN DIAGNOSTICS") }
            Spacer(Modifier.height(14.dp))
        }

        // …and a sticky live bar that is ALWAYS visible without scrolling.
        StickyLiveBar(
            state = state,
            stats = stats,
            canStart = profile.playlist.isNotEmpty(),
            onStartLive = onStartLive,
            onStopLive = onStopLive
        )
    }
}

/** Always-visible bottom bar with the real state, timer and START/STOP control. */
@Composable
private fun StickyLiveBar(
    state: StreamState,
    stats: StreamStats,
    canStart: Boolean,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit
) {
    val accent = statusColor(state)
    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(state)
                Text(
                    stats.elapsedLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = accent
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.isActive) {
                Button(
                    onClick = onStopLive,
                    enabled = state != StreamState.STOPPING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .neonGlow(MaterialTheme.colorScheme.error, radius = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("STOP LIVE", fontWeight = FontWeight.Black) }
            } else {
                Button(
                    onClick = onStartLive,
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .neonGlow(MaterialTheme.colorScheme.primary, radius = 18.dp)
                ) { Text("START LIVE", fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun LiveControlPanel(
    profile: StreamProfile,
    state: StreamState,
    stats: StreamStats,
    maskedKey: String,
    validationError: String?,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit
) {
    val accent = statusColor(state)
    Card(
        Modifier.fillMaxWidth().neonGlow(accent, radius = if (state.isLive) 16.dp else 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("LIVE CONTROL")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status", style = MaterialTheme.typography.bodyMedium)
                StatusPill(state)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stats.elapsedLabel(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = accent
            )
            Text(
                "Maximum live: " + StreamStats.formatDuration(
                    if (stats.maxDurationMs > 0) stats.maxDurationMs
                    else profile.maxDurationMinutes * 60_000L
                ),
                style = MaterialTheme.typography.bodySmall
            )

            if (state.isActive || state == StreamState.STREAMING) {
                Spacer(Modifier.height(10.dp))
                StatRow("Current video", "${stats.currentVideoIndex + 1} / ${maxOf(stats.playlistSize, profile.playlist.size)}")
                StatRow("Playing", stats.currentVideoName)
                StatRow("Loop", "${stats.loopIndex} / ${stats.loopTarget}")
                StatRow("FPS decoder", String.format("%.1f", stats.decoderFps))
                StatRow("FPS compositor", String.format("%.1f", stats.compositorFps))
                StatRow("FPS encoder in", String.format("%.1f", stats.encoderInputFps))
                StatRow("FPS encoder out", String.format("%.1f", stats.actualFps))
                StatRow("FPS sent", String.format("%.1f", stats.sentFps))
                StatRow("FPS target", "${stats.targetFps}")
                StatRow("Bitrate actual", "${stats.bitrateBps / 1000} kbps")
                StatRow("Bitrate target", "${stats.targetBitrateKbps} kbps")
                StatRow("Encoded frames", "${stats.encodedFrames}")
                StatRow("Dropped frames", "${stats.droppedFrames}")
                StatRow("Data sent", formatBytes(stats.bytesSent))
                StatRow("Packets sent", "${stats.packetsSent}")
                StatRow("Reconnects", "${stats.reconnectCount}")
                StatRow(
                    "RTMP",
                    if (stats.rtmpPublishing) "PUBLISHING" else if (stats.rtmpConnected) "CONNECTED"
                    else "DISCONNECTED"
                )
                StatRow(
                    "Audio out",
                    if (stats.audioSampleRate > 0)
                        "${stats.audioSampleRate} Hz / ${stats.audioChannels} ch AAC-LC"
                    else "-"
                )
                StatRow(
                    "Audio source",
                    if (stats.audioSourceSampleRate > 0)
                        "${stats.audioSourceSampleRate} Hz / ${stats.audioSourceChannels} ch"
                    else "-"
                )
                StatRow("Audio frames", "${stats.audioFrames}")
                StatRow("Audio buffer", "${stats.audioBufferPercent}%")
                StatRow("Audio underruns", "${stats.audioUnderruns}")
                StatRow("Audio overruns", "${stats.audioOverruns}")
                StatRow("Audio silence frames", "${stats.audioSilenceFrames}")
                StatRow("Audio encode errors", "${stats.audioEncodeErrors}")
                StatRow("A/V offset", String.format("%+d ms", stats.avOffsetMs))
                StatRow("Network type", stats.networkTransport)
                StatRow("Network link", if (stats.networkConnected) "CONNECTED" else "DOWN")
                StatRow("Send queue", formatBytes(stats.sendQueueBytes))
                StatRow("Send errors", "${stats.sendErrors}")
                StatRow("Foreground service", if (stats.serviceRunning) "RUNNING" else "STOPPED")
                StatRow("Screen", if (stats.screenOn) "ON" else "OFF")
                StatRow("Activity", if (stats.activityVisible) "VISIBLE" else "BACKGROUND")
                StatRow("Health video", stats.videoHealth().name)
                StatRow("Health audio", stats.audioHealth().name)
                StatRow("Health sync", stats.syncHealth().name)
                StatRow("Health network", stats.networkHealth().name)
            } else {
                Spacer(Modifier.height(6.dp))
                StatRow("Videos", "${profile.playlist.size}")
                StatRow("Output", "${profile.resolution} @ ${profile.fps} FPS")
                StatRow("Bitrate target", "${profile.bitrateKbps} kbps")
                StatRow("Loop", profile.loopLabel())
                StatRow("Server", profile.serverUrl)
                StatRow("Stream key", maskedKey)
            }

            stats.statusMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = accent, style = MaterialTheme.typography.bodyMedium)
            }
            stats.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            validationError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Use the START LIVE / STOP LIVE control at the bottom of the screen.",
                style = MaterialTheme.typography.bodySmall
            )
            if (profile.playlist.isEmpty()) {
                Text(
                    "Add at least one video before going live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
