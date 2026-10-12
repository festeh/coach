package com.example.coach_android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coach_android.data.model.FocusData
import com.example.coach_android.util.TimeFormatter

@Composable
fun AgentLockCard(
    focusData: FocusData,
    onOpenChat: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenChat),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (label, accent) =
                if (focusData.isAgentLocked) {
                    "Agent lock engaged" to MaterialTheme.colorScheme.error
                } else {
                    "Released" to MaterialTheme.colorScheme.primary
                }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = accent.copy(alpha = 0.15f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (!focusData.isAgentLocked && (focusData.agentReleaseTimeLeft ?: 0) > 0) {
                Text(
                    text = TimeFormatter.formatFocusTime(focusData.agentReleaseTimeLeft ?: 0),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "until relock",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Tap to chat with coach",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
