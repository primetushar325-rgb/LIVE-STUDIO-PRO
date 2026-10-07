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
fun DiagnosticsScreen(
    stats: StreamStats,
    rtmp: com.livevip.app.rtmp.RtmpDiagnostics,
    logs: List<String>,
    onBack: () -> Unit
) {
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
                "Config path" to stats.encoderConfigPath,
                "Output size" to "${stats.outputWidth}x${stats.outputHeight}",
                "Encoded frames" to "${stats.encodedFrames}",
                "Encoded bytes" to "${stats.encodedBytes}",
                "Actual FPS" to String.format("%.2f", stats.actualFps),
                "Dropped" to "${stats.droppedFrames}"
            )
        )
        StatBlock(
            "HEALTH",
            listOf(
                "Video" to stats.videoHealth().name,
                "Audio" to stats.audioHealth().name,
                "Sync" to stats.syncHealth().name,
                "Network" to stats.networkHealth().name,
                "Background" to if (stats.serviceRunning) "GREEN" else "UNKNOWN"
            )
        )
        StatBlock(
            "VIDEO — PER STAGE FPS",
            listOf(
                "Target FPS" to "${stats.targetFps}",
                "Decoder FPS" to String.format("%.2f", stats.decoderFps),
                "Compositor FPS" to String.format("%.2f", stats.compositorFps),
                "Encoder in FPS" to String.format("%.2f", stats.encoderInputFps),
                "Encoder out FPS" to String.format("%.2f", stats.actualFps),
                "Sent FPS" to String.format("%.2f", stats.sentFps),
                "Repeated frames" to "${stats.repeatedFrames}"
            )
        )
        StatBlock(
            "AUDIO",
            listOf(
                "Audio" to if (stats.audioReady) "READY" else "NOT READY",
                "Output" to if (stats.audioSampleRate > 0)
                    "AAC-LC ${stats.audioSampleRate} Hz / ${stats.audioChannels} ch / 128 kbps" else "-",
                "Source" to if (stats.audioSourceSampleRate > 0)
                    "${stats.audioSourceSampleRate} Hz / ${stats.audioSourceChannels} ch" else "-",
                "Config path" to stats.audioConfigPath,
                "AAC frames" to "${stats.audioFrames}",
                "Audio packets sent" to "${stats.audioPacketsSent}",
                "Buffer depth" to "${stats.audioBufferPercent}%",
                "Underruns" to "${stats.audioUnderruns}",
                "Overruns" to "${stats.audioOverruns}",
                "Silence frames" to "${stats.audioSilenceFrames}",
                "Encode errors" to "${stats.audioEncodeErrors}",
                "Buffer state" to stats.audioBufferLabel()
            )
        )
        StatBlock(
            "SYNC",
            listOf(
                "A/V offset" to String.format("%+d ms", stats.avOffsetMs),
                "Sync state" to stats.syncLabel()
            )
        )
        StatBlock(
            "NETWORK",
            listOf(
                "Network type" to stats.networkTransport,
                "Link" to if (stats.networkConnected) "CONNECTED" else "DISCONNECTED",
                "UPLOAD CAPACITY (measured peak)" to
                    String.format("%.2f Mbps", stats.uploadCapacityBps / 1_000_000.0),
                "Current stream bitrate" to String.format("%.2f Mbps", stats.bitrateBps / 1_000_000.0),
                "Headroom" to String.format("%.2f Mbps", stats.headroomBps() / 1_000_000.0),
                "Video packets sent" to "${stats.videoPacketsSent}",
                "Send queue" to "${stats.sendQueueBytes / 1024} KB",
                "Send errors" to "${stats.sendErrors}",
                "Reconnects" to "${stats.reconnectCount}"
            )
        )
        StatBlock(
            "BACKGROUND",
            listOf(
                "Foreground service" to if (stats.serviceRunning) "RUNNING" else "STOPPED",
                "Background mode" to if (!stats.activityVisible) "ACTIVE" else "INACTIVE",
                "Screen" to if (stats.screenOn) "ON" else "OFF",
                "Activity" to if (stats.activityVisible) "VISIBLE" else "BACKGROUND",
                "Engine" to if (stats.state.isActive) "RUNNING" else "STOPPED"
            )
        )
        StatBlock(
            "TRANSPORT — CONNECTION",
            listOf(
                "Mode" to rtmp.mode,
                "Protocol" to rtmp.protocol,
                "Host" to rtmp.host,
                "Port" to "${rtmp.port}",
                "Application" to rtmp.application,
                "URL validation" to rtmp.urlValidation,
                "DNS" to rtmp.dnsResult,
                "Socket" to rtmp.socketResult,
                "TLS" to rtmp.tlsResult,
                "Handshake" to rtmp.handshakeResult,
                "Stream key" to rtmp.maskedStreamKey
            )
        )
        StatBlock(
            "RTMP — PROTOCOL",
            listOf(
                "connect sent" to if (rtmp.connectSent) "YES" else "NO",
                "connect result" to rtmp.connectResultCode,
                "connect description" to rtmp.connectDescription,
                "createStream txId" to "${rtmp.createStreamTxId}",
                "createStream result" to rtmp.createStreamResult,
                "createStream error" to rtmp.createStreamErrorCode,
                "createStream detail" to rtmp.createStreamErrorDescription,
                "NetStream id" to "${rtmp.streamId}",
                "publish result" to rtmp.publishResult,
                "publish status" to rtmp.publishStatusCode,
                "publish level" to rtmp.publishStatusLevel,
                "publish detail" to rtmp.publishStatusDescription,
                "failure stage" to rtmp.failureStage,
                "last exception" to rtmp.lastException
            )
        )
        SectionTitle("RTMP SERVER MESSAGES")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                if (rtmp.serverMessages.isEmpty()) {
                    Text("No server messages yet.", style = MaterialTheme.typography.bodySmall)
                }
                rtmp.serverMessages.takeLast(25).reversed().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        StatBlock(
            "TRANSPORT — METRICS",
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
