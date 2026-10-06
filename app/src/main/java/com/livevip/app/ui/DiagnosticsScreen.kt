package com.livevip.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.livevip.app.engine.StreamStats

/**
 * Developer diagnostics. Every value here comes from the real pipeline.
 * Credentials are never displayed.
 */
@Composable
fun DiagnosticsScreen(stats: StreamStats, logs: List<String>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("< BACK") }
        Text("DEVELOPER DIAGNOSTICS", style = MaterialTheme.typography.headlineSmall)

        StatBlock(
            "VIDEO SOURCE",
            listOf(
                "Decoder" to if (stats.decoderReady) "READY" else "NOT READY",
                "First frame" to if (stats.firstFrame) "YES" else "NO",
                "Source size" to "${stats.sourceWidth}x${stats.sourceHeight}",
                "Preview frames" to "${stats.previewFrames}",
                "Playing" to stats.currentVideoName,
                "Loop" to "${stats.loopIndex}"
            )
        )
        StatBlock(
            "ENCODER",
            listOf(
                "Encoder" to if (stats.encoderReady) "READY" else "NOT READY",
                "Codec" to stats.encoderName,
                "Output size" to "${stats.outputWidth}x${stats.outputHeight}",
                "Encoded frames" to "${stats.encodedFrames}",
                "Encoded bytes" to "${stats.encodedBytes}",
                "Actual FPS" to String.format("%.2f", stats.actualFps),
                "Dropped" to "${stats.droppedFrames}"
            )
        )
        StatBlock(
            "AUDIO",
            listOf(
                "Audio" to if (stats.audioReady) "READY" else "NOT READY",
                "AAC frames" to "${stats.audioFrames}",
                "A/V offset" to "${stats.avOffsetMs} ms"
            )
        )
        StatBlock(
            "TRANSPORT",
            listOf(
                "RTMP" to if (stats.rtmpPublishing) "PUBLISHING" else
                    if (stats.rtmpConnected) "CONNECTED" else "DISCONNECTED",
                "Packets sent" to "${stats.packetsSent}",
                "Bytes sent" to "${stats.bytesSent}",
                "Bitrate" to "${stats.bitrateBps / 1000} kbps",
                "Send queue" to "${stats.queueDepth}",
                "Reconnects" to "${stats.reconnectCount}",
                "Network" to stats.networkTransport,
                "Elapsed" to stats.elapsedLabel(),
                "Last error" to (stats.lastError ?: "NONE")
            )
        )
        SectionTitle("BLACK SCREEN CHECKLIST")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                StatRow("1. Decoder initialised", yn(stats.decoderReady))
                StatRow("2. First frame received", yn(stats.firstFrame))
                StatRow("3. Compositor drawing", yn(stats.previewFrames > 0))
                StatRow("4. Encoder producing", yn(stats.encodedFrames > 0))
                StatRow("5. Audio producing", yn(stats.audioFrames > 0))
                StatRow("6. Transport sending", yn(stats.packetsSent > 0))
            }
        }
        SectionTitle("ENGINE LOG")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                if (logs.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                logs.takeLast(60).reversed().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun yn(value: Boolean) = if (value) "YES" else "NO"
