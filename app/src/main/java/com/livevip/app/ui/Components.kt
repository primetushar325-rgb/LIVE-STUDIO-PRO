package com.livevip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            val isSelected = option == selected
            AssistChip(
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                colors = if (isSelected) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else AssistChipDefaults.assistChipColors()
            )
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatBlock(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SectionTitle(title)
        rows.forEach { (l, v) -> StatRow(l, v) }
    }
}

@Composable
fun StatusPill(state: com.livevip.app.core.StreamState) {
    val color = statusColor(state)
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .neonGlow(color, cornerRadius = 20.dp, radius = 10.dp, borderAlpha = 0.5f)
            .background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
        Text(
            if (state == com.livevip.app.core.StreamState.STREAMING ||
                state == com.livevip.app.core.StreamState.SENDING
            ) "  ● LIVE — ${state.label()}" else "  ${statusSymbol(state)} ${state.label()}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = color
        )
    }
}

@Composable
fun statusColor(state: com.livevip.app.core.StreamState): androidx.compose.ui.graphics.Color =
    when (state) {
        // STREAMING is GREEN (that state is only reached when packets really flow).
        com.livevip.app.core.StreamState.STREAMING,
        com.livevip.app.core.StreamState.SENDING -> androidx.compose.ui.graphics.Color(0xFF00E676)
        com.livevip.app.core.StreamState.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF00E5FF)
        com.livevip.app.core.StreamState.STOPPING,
        com.livevip.app.core.StreamState.RECONNECTING -> androidx.compose.ui.graphics.Color(0xFFFF9100)
        com.livevip.app.core.StreamState.ERROR,
        com.livevip.app.core.StreamState.NETWORK_LOST -> MaterialTheme.colorScheme.error
        com.livevip.app.core.StreamState.IDLE,
        com.livevip.app.core.StreamState.STOPPED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        else -> androidx.compose.ui.graphics.Color(0xFFFFC107)
    }

private fun statusSymbol(state: com.livevip.app.core.StreamState): String = when (state) {
    com.livevip.app.core.StreamState.STREAMING, com.livevip.app.core.StreamState.SENDING -> "●"
    com.livevip.app.core.StreamState.ERROR, com.livevip.app.core.StreamState.NETWORK_LOST -> "!"
    com.livevip.app.core.StreamState.IDLE, com.livevip.app.core.StreamState.STOPPED -> "•"
    else -> "…"
}

/** Two-per-row action buttons: labels can never be squeezed into vertical letters. */
@Composable
fun ResponsiveActions(actions: List<Pair<String, () -> Unit>>) {
    Column(Modifier.fillMaxWidth()) {
        actions.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { (label, action) ->
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
