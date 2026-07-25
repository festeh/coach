package com.example.coach_android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.util.TimeFormatter
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    // True when hosted inside the app's Scaffold (the Chat tab), which already
    // consumes system-bar insets — padding again would draw dead bands.
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showUnlockDetail by remember { mutableStateOf(false) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var overrideReason by remember { mutableStateOf("") }
    var overrideError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val showScrollToBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible < totalItems - 2
        }
    }


    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear conversation?") },
            text = { Text("The coach forgets this thread. The decision ledger keeps its rows.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (showUnlockDetail) {
        UnlockDetailSheet(
            // The card's week stands in until the month lands, so the sheet never
            // opens empty on a slow network.
            days = state.unlockDetail.ifEmpty { state.unlockTrend },
            loading = state.unlockDetailLoading,
            onDismiss = { showUnlockDetail = false },
        )
    }

    if (showOverrideDialog) {
        AlertDialog(
            onDismissRequest = {
                showOverrideDialog = false
                overrideReason = ""
                overrideError = null
            },
            title = { Text("Take a 15-minute override?") },
            text = {
                Column {
                    Text("This bypasses the coach. Your reason is recorded in the decision ledger.")
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = overrideReason,
                        onValueChange = {
                            overrideReason = it
                            overrideError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Reason") },
                        minLines = 2,
                        maxLines = 4,
                        isError = overrideError != null,
                    )
                    overrideError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = overrideReason.isNotBlank(),
                    onClick = {
                        viewModel.takeOverride(overrideReason).fold(
                            onSuccess = {
                                showOverrideDialog = false
                                overrideReason = ""
                                overrideError = null
                            },
                            onFailure = { error ->
                                overrideError = error.message ?: "Could not take the override"
                            },
                        )
                    },
                ) { Text("Take override") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOverrideDialog = false
                        overrideReason = ""
                        overrideError = null
                    },
                ) { Text("Cancel") }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { if (embedded) it else it.statusBarsPadding().navigationBarsPadding() }
                    .imePadding(),
        ) {
            ChatHeader(
                connecting = state.connecting,
                streaming = state.streaming,
                agentLocked = state.agentLocked,
                releaseLeftSeconds = state.releaseLeftSeconds,
                onClear = { confirmClear = true },
                onTakeOverride = { showOverrideDialog = true },
            )

            if (state.unlockTrend.isNotEmpty()) {
                UnlockStatsCard(
                    trend = state.unlockTrend,
                    onClick = {
                        showUnlockDetail = true
                        viewModel.loadUnlockDetail()
                    },
                )
            }

            state.error?.let { err ->
                ErrorBanner(message = err, onRetry = viewModel::reconnect)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (state.connecting && state.messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )
                            Text(
                                text = "Connecting to Coach…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.messages.isEmpty() && !state.connecting) {
                            item { EmptyHint() }
                        }
                        items(
                            count = state.messages.size,
                            key = { index ->
                                // Use content hash as part of key to avoid jumps during streaming
                                val msg = state.messages[index]
                                "${index}_${msg.role}_${msg.content.hashCode()}"
                            },
                        ) { idx ->
                            val message = state.messages[idx]
                            MessageBubble(
                                message = message,
                                isLatest = idx == state.messages.lastIndex,
                            )
                        }
                    }
                }

                ScrollToBottomContainer(
                    visible = showScrollToBottom,
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex)
                        }
                    },
                    hasNewContent = state.streaming,
                )
            }

            InputRow(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.send(input)
                        input = ""
                    }
                },
                enabled = !state.streaming,
            )
        }
    }
}

@Composable
private fun ChatHeader(
    connecting: Boolean,
    streaming: Boolean,
    agentLocked: Boolean,
    releaseLeftSeconds: Int,
    onClear: () -> Unit,
    onTakeOverride: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val statusText = when {
        connecting -> "Connecting…"
        streaming -> "Coach is typing…"
        else -> "Online"
    }
    val statusColor by animateColorAsState(
        targetValue = when {
            connecting -> MaterialTheme.colorScheme.outline
            streaming -> MaterialTheme.colorScheme.primary
            else -> Color(0xFF4ADE80)
        },
        label = "statusColor",
    )

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Coach",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Live lock state: a grant flips this chip in place of the old
                // auto-close, so the coach's reply stays on screen.
                val chipColor =
                    if (agentLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = chipColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text =
                            if (agentLocked) {
                                "Locked"
                            } else {
                                "Released · ${TimeFormatter.formatFocusTime(releaseLeftSeconds)}"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = chipColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Take 15-minute override") },
                            onClick = {
                                menuExpanded = false
                                onTakeOverride()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear conversation") },
                            onClick = {
                                menuExpanded = false
                                onClear()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }

        }
    }
}

// What today's asking has bought, next to the week it sits in. Its own card, so
// it reads as the ledger rather than as part of the chat's chrome, and tappable
// for the month behind the number.
@Composable
private fun UnlockStatsCard(
    trend: List<UnlockDay>,
    onClick: () -> Unit,
) {
    val today = trend.last()
    val weekSeconds = trend.sumOf { it.unlockedSeconds }
    val dailyAverage = weekSeconds / trend.size

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (today.unlocks == 0) {
                            "No unlocks yet today"
                        } else {
                            val unit = if (today.unlocks == 1) "unlock" else "unlocks"
                            "${today.unlocks} $unit · ${TimeFormatter.formatFocusTime(today.unlockedSeconds)}"
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // A week with nothing in it has no average worth printing.
                    text =
                        if (weekSeconds > 0) {
                            "today · ${trend.size}d avg ${TimeFormatter.formatFocusTime(dailyAverage)}"
                        } else {
                            "nothing in the last ${trend.size} days"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UnlockSparkline(trend = trend)

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Unlock details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun UnlockSparkline(trend: List<UnlockDay>) {
    val peak = trend.maxOf { it.unlockedSeconds }
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val pastColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val todayColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.height(SPARK_MAX_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        trend.forEachIndexed { index, day ->
            // Zero days keep a stub so the week always reads as seven days.
            val height =
                if (day.unlockedSeconds <= 0 || peak <= 0) {
                    SPARK_MIN_HEIGHT
                } else {
                    SPARK_MIN_HEIGHT +
                        (SPARK_MAX_HEIGHT - SPARK_MIN_HEIGHT) * (day.unlockedSeconds.toFloat() / peak)
                }
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                day.unlockedSeconds <= 0 -> emptyColor
                                index == trend.lastIndex -> todayColor
                                else -> pastColor
                            },
                        ),
            )
        }
    }
}

private val SPARK_MIN_HEIGHT = 3.dp
private val SPARK_MAX_HEIGHT = 20.dp

// The month behind the card: exact numbers per day, which the sparkline can only
// imply, plus what the recent week looks like against the fuller record.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockDetailSheet(
    days: List<UnlockDay>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Unlocks",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Every release the coach granted, and every override you took.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            val today = days.lastOrNull()
            val lastWeek = days.takeLast(7)
            val windowAverage = if (days.isEmpty()) 0 else days.sumOf { it.unlockedSeconds } / days.size
            val weekAverage = if (lastWeek.isEmpty()) 0 else lastWeek.sumOf { it.unlockedSeconds } / lastWeek.size

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UnlockStatTile(
                    label = "Today",
                    value = "${today?.unlocks ?: 0}",
                    caption = TimeFormatter.formatFocusTime(today?.unlockedSeconds ?: 0),
                    modifier = Modifier.weight(1f),
                    emphasised = true,
                )
                UnlockStatTile(
                    label = "7-day avg",
                    value = TimeFormatter.formatFocusTime(weekAverage),
                    caption = "per day",
                    modifier = Modifier.weight(1f),
                )
                UnlockStatTile(
                    label = "${days.size}-day avg",
                    value = TimeFormatter.formatFocusTime(windowAverage),
                    caption = "per day",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // The week reads as a verdict only against the longer record.
            if (windowAverage > 0 && lastWeek.size == 7) {
                val delta = weekAverage - windowAverage
                val share = (delta * 100) / windowAverage
                Text(
                    text =
                        when {
                            share >= 5 -> "This week runs $share% above your ${days.size}-day average."
                            share <= -5 -> "This week runs ${-share}% below your ${days.size}-day average."
                            else -> "This week matches your ${days.size}-day average."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (share >= 5) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Spacer(Modifier.height(20.dp))

            if (loading && days.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            } else {
                val peak = days.maxOfOrNull { it.unlockedSeconds } ?: 0
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Most recent first: the days you're answering for are the ones
                    // you scroll to least.
                    items(days.size) { index ->
                        UnlockDayRow(day = days[days.lastIndex - index], peakSeconds = peak)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockStatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (emphasised) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color =
                if (emphasised) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun UnlockDayRow(
    day: UnlockDay,
    peakSeconds: Int,
) {
    val today = LocalDate.now()
    val label =
        when (day.date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else ->
                "${day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} " +
                    "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
        }
    val fraction =
        if (peakSeconds <= 0) 0f else (day.unlockedSeconds.toFloat() / peakSeconds).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (day.date == today) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.width(84.dp),
        )

        // The bar carries the comparison; the numbers carry the fact.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (day.date == today) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                },
                            ),
                )
            }
        }

        Text(
            text = if (day.unlocks == 0) "—" else "${day.unlocks}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = if (day.unlockedSeconds == 0) "" else TimeFormatter.formatFocusTime(day.unlockedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLatest: Boolean,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface

    val shape =
        if (isUser) {
            RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
        } else {
            RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            CoachAvatar(modifier = Modifier.padding(end = 8.dp, top = 4.dp))
        } else {
            Spacer(Modifier.width(40.dp))
        }

        Box(
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .weight(1f, fill = false)
                    .clip(shape)
                    .background(bubbleColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (message.content.isEmpty() && message.streaming) {
                TypingIndicator()
            } else {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (isUser) {
            Spacer(Modifier.width(40.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun CoachAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp),
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val infiniteTransition = rememberInfiniteTransition(label = "typing$index")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(600, delayMillis = delay),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "alpha$index",
            )
            Box(
                modifier =
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Make your case",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tell the coach why you need a break.\nBe honest — it listens.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val fieldShape = RoundedCornerShape(24.dp)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(fieldShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color =
                                if (value.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            shape = fieldShape,
                        ),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextFieldWithPlaceholder(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = "Message…",
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.size(48.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                )
            }
        }
    }
}

@Composable
private fun BasicTextFieldWithPlaceholder(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        maxLines = 5,
        colors =
            androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ScrollToBottomContainer(
    visible: Boolean,
    onClick: () -> Unit,
    hasNewContent: Boolean,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ScrollToBottomButton(onClick = onClick, hasNewContent = hasNewContent)
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    hasNewContent: Boolean,
) {
    FilledIconButton(
        onClick = onClick,
        modifier =
            Modifier
                .padding(bottom = 12.dp)
                .size(40.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                modifier = Modifier.size(20.dp),
            )
            if (hasNewContent) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
