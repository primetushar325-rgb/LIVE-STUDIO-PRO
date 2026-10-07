package com.livevip.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livevip.app.data.StreamProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    profiles: List<StreamProfile>,
    onCreate: () -> Unit,
    onOpen: (StreamProfile) -> Unit,
    onEdit: (StreamProfile) -> Unit,
    onDuplicate: (StreamProfile) -> Unit,
    onDelete: (StreamProfile) -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LIVE VIP",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Row {
                TextButton(onClick = onSettings) { Text("SETTINGS") }
                TextButton(onClick = onDiagnostics) { Text("DIAG") }
            }
        }
        Text(
            "Direct RTMP / RTMPS mobile broadcasting  •  build " +
                com.livevip.app.BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().neonGlow(MaterialTheme.colorScheme.primary)
        ) { Text("+ CREATE STREAM") }
        Spacer(Modifier.height(16.dp))
        Text("SAVED STREAMS", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        if (profiles.isEmpty()) {
            Text(
                "No stream profiles yet. Create one to save server, key, playlist, " +
                    "resolution, loop and duration settings permanently.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(profile, onOpen, onEdit, onDuplicate, onDelete)
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: StreamProfile,
    onOpen: (StreamProfile) -> Unit,
    onEdit: (StreamProfile) -> Unit,
    onDuplicate: (StreamProfile) -> Unit,
    onDelete: (StreamProfile) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(profile.summaryLine(), style = MaterialTheme.typography.bodySmall)
            Text(
                "Last used: " + if (profile.lastUsedAt == 0L) "never" else
                    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(profile.lastUsedAt)),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onOpen(profile) },
                modifier = Modifier.fillMaxWidth().neonGlow(MaterialTheme.colorScheme.primary)
            ) { Text("OPEN") }
            ResponsiveActions(
                listOf(
                    "EDIT" to { onEdit(profile) },
                    "COPY" to { onDuplicate(profile) },
                    "DELETE" to { onDelete(profile) }
                )
            )
        }
    }
}
