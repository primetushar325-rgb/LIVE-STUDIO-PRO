package com.livevip.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.livevip.app.data.LoopMode
import com.livevip.app.data.Orientation
import com.livevip.app.data.Resolution
import com.livevip.app.data.StreamProfile
import com.livevip.app.data.VideoItem
import com.livevip.app.video.VideoSourceController

@Composable
fun EditProfileScreen(
    initial: StreamProfile,
    initialKey: String,
    probe: (String) -> Triple<Int, Int, Long>?,
    displayNameOf: (String) -> String,
    onSave: (StreamProfile, String) -> Unit,
    onCancel: () -> Unit
) {
    var profile by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf(initialKey) }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val meta = probe(uri.toString())
            VideoItem(
                uri = uri.toString(),
                displayName = displayNameOf(uri.toString()),
                durationMs = (meta?.third ?: 0L) / 1000,
                width = meta?.first ?: 0,
                height = meta?.second ?: 0
            )
        }
        profile = profile.copy(playlist = profile.playlist + added)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("STREAM PROFILE", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = profile.name,
            onValueChange = { profile = profile.copy(name = it) },
            label = { Text("Stream name") },
            modifier = Modifier.fillMaxWidth()
        )

        SectionTitle("VIDEO PLAYLIST (${profile.playlist.size})")
        profile.playlist.forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("${index + 1}. ${item.displayName}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${item.width}x${item.height} • ${item.aspectLabel()} • " +
                            "${item.durationMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                val list = profile.playlist.toMutableList()
                                val tmp = list[index - 1]; list[index - 1] = list[index]; list[index] = tmp
                                profile = profile.copy(playlist = list)
                            }
                        ) { Text("UP") }
                        TextButton(
                            enabled = index < profile.playlist.size - 1,
                            onClick = {
                                val list = profile.playlist.toMutableList()
                                val tmp = list[index + 1]; list[index + 1] = list[index]; list[index] = tmp
                                profile = profile.copy(playlist = list)
                            }
                        ) { Text("DOWN") }
                        TextButton(onClick = {
                            profile = profile.copy(
                                playlist = profile.playlist.filterIndexed { i, _ -> i != index }
                            )
                        }) { Text("REMOVE") }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ ADD VIDEO") }

        SectionTitle("OUTPUT FORMAT")
        ChipRow(Orientation.entries.toList(), profile.orientation, { it.label }) { orientation ->
            profile = profile.copy(
                orientation = orientation,
                resolution = Resolution.optionsFor(orientation)[1]
            )
        }

        SectionTitle("RESOLUTION")
        ChipRow(
            Resolution.optionsFor(profile.orientation),
            profile.resolution,
            { it.toString() }
        ) { profile = profile.copy(resolution = it) }

        SectionTitle("FPS")
        ChipRow(listOf(24, 25, 30), profile.fps, { "$it" }) { profile = profile.copy(fps = it) }

        SectionTitle("BITRATE — 720p30: 3000 LOW / 4500 BALANCED / 6000 HIGH")
        ChipRow(
            listOf(2500, 3000, 4500, 6000, 9000), profile.bitrateKbps, { "$it" }
        ) { profile = profile.copy(bitrateKbps = it) }

        SectionTitle("AUDIO")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = profile.videoAudioEnabled,
                onCheckedChange = { profile = profile.copy(videoAudioEnabled = it) }
            )
            Text("  Video audio", Modifier.padding(end = 16.dp))
            Switch(
                checked = profile.microphoneEnabled,
                onCheckedChange = { profile = profile.copy(microphoneEnabled = it) }
            )
            Text("  Microphone")
        }

        SectionTitle("LOOP")
        ChipRow(LoopMode.entries.toList(), profile.loopMode, { it.name }) {
            profile = profile.copy(loopMode = it)
        }
        if (profile.loopMode == LoopMode.COUNT) {
            ChipRow(listOf(1, 2, 3, 5, 10, 20), profile.loopCount, { "x$it" }) {
                profile = profile.copy(loopCount = it)
            }
        }

        SectionTitle("MAXIMUM LIVE DURATION")
        ChipRow(
            listOf(30, 60, 120, 180, 360, 600, 720),
            profile.maxDurationMinutes,
            { if (it % 60 == 0) "${it / 60}H" else "${it}M" }
        ) { profile = profile.copy(maxDurationMinutes = it) }

        SectionTitle("DESTINATION (direct RTMP / RTMPS)")
        OutlinedTextField(
            value = profile.serverUrl,
            onValueChange = { profile = profile.copy(serverUrl = it) },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Stream key (stored encrypted)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(profile, key) }) { Text("SAVE PROFILE") }
            OutlinedButton(onClick = onCancel) { Text("CANCEL") }
        }
        Spacer(Modifier.height(30.dp))
    }
}
