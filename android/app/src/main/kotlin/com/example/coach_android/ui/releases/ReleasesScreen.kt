package com.example.coach_android.ui.releases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coach_android.util.TimeFormatter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ReleasesScreen(viewModel: ReleasesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.onStart() }

    val days =
        remember(state.releases) {
            groupReleasesByDay(state.releases, ZoneId.systemDefault(), LocalDate.now())
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item {
            Text(
                text = "Every time the lock came off: your reason, the coach's reply, and for how long.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (state.loadedOnce && state.releases.isEmpty()) {
            item {
                Text(
                    text = "No releases yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        days.forEach { day ->
            item(key = "day-${day.date}") {
                Text(
                    text = day.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                HorizontalDivider()
            }
            items(day.rows, key = { it.release.id }) { row ->
                ReleaseItem(row)
                HorizontalDivider()
            }
        }

        item(key = "footer") {
            ReleasesFooter(
                state = state,
                onLoadOlder = viewModel::loadOlder,
                onRetry = viewModel::retry,
            )
        }
    }
}

@Composable
private fun ReleaseItem(row: ReleaseRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = row.at.format(timeFormat),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = row.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeColor(row.label),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (row.release.durationSeconds > 0) {
                Text(
                    text = TimeFormatter.formatFocusTime(row.release.durationSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.release.userMessage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = row.release.userMessage,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (row.release.agentMessage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.release.agentMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun badgeColor(label: String): Color =
    when (label) {
        "OK", "granted" -> MaterialTheme.colorScheme.primary
        "fallback" -> MaterialTheme.colorScheme.tertiary
        "denied" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

// The end of the list: older pages load as it scrolls into view, a failure
// offers a retry, and the journal's start says so.
@Composable
private fun ReleasesFooter(
    state: ReleasesUiState,
    onLoadOlder: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.error != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            state.loading || !state.loadedOnce ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            state.nextBefore != null ->
                // Composed only when scrolled near the end, so reaching it is
                // what asks for the next page.
                LaunchedEffect(state.nextBefore) { onLoadOlder() }
            state.releases.isNotEmpty() ->
                Text(
                    text = "That's everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
