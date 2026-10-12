package com.example.coach_android.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coach_android.util.TimeFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.onStart()
        onDispose { viewModel.onStop() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            DashboardHeader(
                state = state,
                onRefresh = viewModel::refresh,
            )
        }

        item {
            RangePicker(
                selected = state.range,
                onSelect = viewModel::selectRange,
            )
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxHeight(0.55f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item { MetricGrid(state) }
            item { ActivityChart(state.days) }
            item { TemptingAppsCard(state.temptingApps) }

            state.error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    state: StatsUiState,
    onRefresh: () -> Unit,
) {
    val firstDay = state.days.firstOrNull()?.date ?: LocalDate.now().minusDays(state.range.days.toLong() - 1)
    val lastDay = state.days.lastOrNull()?.date ?: LocalDate.now()
    val dateFormat = DateTimeFormatter.ofPattern("MMM d")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Patterns",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${firstDay.format(dateFormat)} – ${lastDay.format(dateFormat)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh stats")
            }
        }
    }
}

@Composable
private fun RangePicker(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.label) },
            )
        }
    }
}

@Composable
private fun MetricGrid(state: StatsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Temptations",
                value = state.totalTemptations.toString(),
                caption = "blocked opens",
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Unlocks",
                value = state.totalUnlocks.toString(),
                caption = "times let in",
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        MetricTile(
            label = "Unlocked",
            value = TimeFormatter.formatFocusTime(state.totalUnlockedSeconds),
            caption = "across ${state.range.label}",
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    caption: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActivityChart(days: List<InteractionDay>) {
    val chartDays =
        if (days.size > 30) {
            days.chunked(7).map { week ->
                InteractionDay(
                    date = week.first().date,
                    temptations = week.sumOf { it.temptations },
                    unlocks = week.sumOf { it.unlocks },
                    unlockedSeconds = week.sumOf { it.unlockedSeconds },
                )
            }
        } else {
            days
        }
    val peak = max(1, chartDays.maxOfOrNull { max(it.temptations, it.unlocks) } ?: 1)
    val compact = chartDays.size > 7
    val chartDescription =
        chartDays.joinToString { day ->
            "${day.date}: ${day.temptations} temptations, ${day.unlocks} unlocks"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (days.size > 30) "Weekly activity" else "Daily activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot("Temptations", MaterialTheme.colorScheme.error)
                LegendDot("Unlocks", MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(20.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .semantics { contentDescription = chartDescription },
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                chartDays.forEachIndexed { index, day ->
                    ChartDay(
                        day = day,
                        peak = peak,
                        showLabel = !compact || index == 0 || index == chartDays.lastIndex || index % 7 == 0,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDot(
    label: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartDay(
    day: InteractionDay,
    peak: Int,
    showLabel: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(116.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ChartBar(
                value = day.temptations,
                peak = peak,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            ChartBar(
                value = day.unlocks,
                peak = peak,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                if (!showLabel) {
                    ""
                } else if (compact) {
                    day.date.dayOfMonth.toString()
                } else {
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChartBar(
    value: Int,
    peak: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = if (value == 0) 0f else value.toFloat() / peak
    Box(
        modifier =
            modifier
                .height(if (value == 0) 2.dp else 88.dp * fraction.coerceIn(0.05f, 1f))
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(if (value == 0) MaterialTheme.colorScheme.outlineVariant else color),
    )
}

@Composable
private fun TemptingAppsCard(apps: List<TemptingApp>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Most tempting apps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Actual monitored-app opens stopped by the lock",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (apps.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "No temptations recorded in this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            } else {
                val peak = apps.maxOf { it.count }.coerceAtLeast(1)
                apps.forEachIndexed { index, app ->
                    if (index > 0) Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = app.count.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { app.count.toFloat() / peak },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.error,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        }
                    }
                }
            }
        }
    }
}
