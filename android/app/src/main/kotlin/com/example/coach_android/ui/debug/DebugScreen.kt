package com.example.coach_android.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DebugScreen(
    onNavigateToLogs: () -> Unit,
    viewModel: DebugViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Permission screens live outside this Activity. Refresh every time the
    // user returns instead of keeping the value captured on first composition.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = onNavigateToLogs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View Logs")
            }
        }

        // Permissions
        item {
            Text("Permissions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        item {
            PermissionRow("Usage Stats", state.hasUsageStats, viewModel::requestUsageStats)
        }
        item {
            PermissionRow("Notifications", state.hasNotifications, viewModel::requestNotifications)
        }
        item {
            PermissionRow("Overlay", state.hasOverlay, viewModel::requestOverlay)
        }
        item {
            PermissionRow("Battery Optimization", state.hasBatteryExclusion, viewModel::requestBatteryExclusion)
        }

        // Actions
        item {
            Text("Service", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        item {
            StatusRow("Service running", state.isServiceRunning)
        }
        item {
            StatusRow("WebSocket connected", state.wsStatus["isConnected"] as? Boolean ?: false)
        }
        item {
            DetailRow(
                label = "Connecting",
                value = if (state.wsStatus["isConnecting"] as? Boolean == true) "Yes" else "No",
            )
        }
        item {
            // The socket retries for as long as the service lives, so a climbing
            // attempt count is the only thing that separates a wedged backoff
            // loop from a connection that is simply idle.
            val attempts = state.wsStatus["reconnectAttempts"] as? Int ?: 0
            val connected = state.wsStatus["isConnected"] as? Boolean ?: false
            DetailRow(
                label = "Reconnect attempts",
                value = "$attempts",
                warn = attempts > 0 && !connected,
            )
        }
        item {
            val wsUrl = state.wsStatus["websocketUrl"] as? String ?: ""
            if (wsUrl.isNotEmpty()) {
                Text(
                    text = "WS: $wsUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::startService,
                    modifier = Modifier.weight(1f),
                ) { Text("Start Service") }
                OutlinedButton(
                    onClick = viewModel::stopService,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop Service") }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::refreshFocusState,
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh Focus") }
                OutlinedButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh Status") }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (granted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                )
            }
            if (!granted) {
                TextButton(onClick = onRequest) { Text("Grant") }
            }
        }
    }
}

// For diagnostics where neither value is pass or fail — a count, or a state the
// socket is legitimately allowed to be in.
@Composable
private fun DetailRow(
    label: String,
    value: String,
    warn: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (warn) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    active: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (active) "Yes" else "No",
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
        )
    }
}
