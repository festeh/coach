package com.example.coach_android.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coach_android.data.model.LogCategory
import com.example.coach_android.data.model.LogEntry
import com.example.coach_android.data.model.LogLevel
import com.example.coach_android.data.model.LogSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearch,
            placeholder = { Text("Search logs...") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
        )

        // Filter chips
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Level filter
            FilterChipGroup(
                label = "Level",
                selected = state.levelFilter?.displayName,
                options = LogLevel.entries.map { it.displayName },
                onSelect = { name ->
                    val level = LogLevel.entries.find { it.displayName == name }
                    viewModel.setLevelFilter(if (level == state.levelFilter) null else level)
                },
            )

            // Source filter
            FilterChipGroup(
                label = "Source",
                selected = state.sourceFilter?.displayName,
                options = LogSource.entries.map { it.displayName },
                onSelect = { name ->
                    val source = LogSource.entries.find { it.displayName == name }
                    viewModel.setSourceFilter(if (source == state.sourceFilter) null else source)
                },
            )

            // Category filter
            FilterChipGroup(
                label = "Category",
                selected = state.categoryFilter?.displayName,
                options = LogCategory.entries.map { it.displayName },
                onSelect = { name ->
                    val cat = LogCategory.entries.find { it.displayName == name }
                    viewModel.setCategoryFilter(if (cat == state.categoryFilter) null else cat)
                },
            )
        }

        // Actions row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.filteredEntries.size} entries",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = {
                    val text = viewModel.getExportText()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
                }) { Text("Copy") }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        }

        // Log list
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.filteredEntries) { entry ->
                    LogItem(entry)
                }
            }
        }
    }
}

@Composable
private fun FilterChipGroup(
    label: String,
    selected: String?,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    FilterChip(
        selected = selected != null,
        onClick = { expanded = true },
        label = { Text(selected ?: label, maxLines = 1) },
    )

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = {
                    onSelect(option)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun LogItem(entry: LogEntry) {
    val levelColor =
        when (entry.level) {
            LogLevel.VERBOSE -> Color(0xFF888888)
            LogLevel.DEBUG -> Color(0xFF4FC3F7)
            LogLevel.INFO -> Color(0xFF81C784)
            LogLevel.WARNING -> Color(0xFFFFB74D)
            LogLevel.ERROR -> Color(0xFFE57373)
        }

    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level.name[0].toString(),
                    color = levelColor,
                    fontSize = 11.sp,
                    modifier = Modifier.width(14.dp),
                )
                Text(
                    text = entry.timestamp.takeLast(12),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Text(
                    text = "${entry.tag} • ${entry.source.displayName} • ${entry.category.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
