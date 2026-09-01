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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.data.model.OverridePhase
import com.example.coach_android.util.TimeFormatter
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
    var overrideDialogOpen by remember { mutableStateOf(false) }
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

    if (overrideDialogOpen) {
        val close = {
            overrideDialogOpen = false
            overrideReason = ""
            overrideError = null
        }
        OverrideDialog(
            reason = overrideReason,
            error = overrideError,
            overridesToday = state.overridesToday,
            cooldownSeconds = state.cooldownSeconds,
            onReasonChange = {
                overrideReason = it
                overrideError = null
            },
            onConfirm = {
                viewModel.takeOverride(overrideReason).fold(
                    onSuccess = { close() },
                    onFailure = { error ->
                        overrideError = error.message ?: "Could not request the override"
                    },
                )
            },
            onDismiss = close,
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
                overridePhase = state.overridePhase,
                overridesToday = state.overridesToday,
                cooldownSeconds = state.cooldownSeconds,
                cooldownRemaining = state.cooldownRemaining,
                redeemRemaining = state.redeemRemaining,
                onClear = { confirmClear = true },
                onRequestOverride = { overrideDialogOpen = true },
                onRedeemOverride = { viewModel.takeOverride("") },
                onPeek = { viewModel.takeShortUnblock() },
            )

            // While a request is live, its state is stated plainly rather than
            // hidden behind a menu item: the countdown to come back for, or the
            // window to claim in before it evaporates.
            if (state.overridePhase != OverridePhase.IDLE) {
                OverrideBanner(
                    phase = state.overridePhase,
                    cooldownRemaining = state.cooldownRemaining,
                    redeemRemaining = state.redeemRemaining,
                    overridesToday = state.overridesToday,
                    onPeek = { viewModel.takeShortUnblock() },
                    onRedeem = { viewModel.takeOverride("") },
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
    overridePhase: OverridePhase,
    overridesToday: Int,
    cooldownSeconds: Int,
    cooldownRemaining: Int,
    redeemRemaining: Int,
    onClear: () -> Unit,
    onRequestOverride: () -> Unit,
    onRedeemOverride: () -> Unit,
    onPeek: () -> Unit,
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
                            text = {
                                Text(
                                    when (overridePhase) {
                                        OverridePhase.COOLING ->
                                            "Override in ${TimeFormatter.formatCountdown(cooldownRemaining)}"
                                        OverridePhase.READY ->
                                            "Take your 15 minutes · ${TimeFormatter.formatCountdown(redeemRemaining)} left"
                                        OverridePhase.IDLE ->
                                            if (overridesToday == 0) {
                                                "Take 15-minute override"
                                            } else {
                                                "Request override · ${TimeFormatter.formatCountdown(cooldownSeconds)} wait"
                                            }
                                    },
                                )
                            },
                            enabled = overridePhase != OverridePhase.COOLING,
                            onClick = {
                                menuExpanded = false
                                if (overridePhase == OverridePhase.READY) onRedeemOverride() else onRequestOverride()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Peek · 30 seconds") },
                            onClick = {
                                menuExpanded = false
                                onPeek()
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

// The reason buys the request, not the override: after the first of the day,
// the tap only starts the wait, and the 15 minutes are taken by coming back
// once it lapses.
@Composable
private fun OverrideDialog(
    reason: String,
    error: String?,
    overridesToday: Int,
    cooldownSeconds: Int,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstOfTheDay = overridesToday == 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (firstOfTheDay) "Take a 15-minute override?" else "Request an override?") },
        text = {
            Column {
                Text(
                    if (firstOfTheDay) {
                        "This bypasses the coach. The first override of the day is immediate; " +
                            "your reason is recorded in the decision ledger."
                    } else {
                        "This is your ${ordinal(overridesToday + 1)} override today, so the wait " +
                            "starts now: ${TimeFormatter.formatCountdown(cooldownSeconds)}. Come back " +
                            "when it ends to take your 15 minutes. Your reason is recorded in the " +
                            "decision ledger."
                    },
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason") },
                    minLines = 2,
                    maxLines = 4,
                    isError = error != null,
                )
                error?.let { message ->
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
                enabled = reason.isNotBlank(),
                onClick = onConfirm,
            ) { Text(if (firstOfTheDay) "Take override" else "Start the wait") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// While a request is live the wait is stated plainly rather than hidden behind
// a disabled menu item — with the door still open next to it: the peek while
// cooling, the claim while ready.
@Composable
private fun OverrideBanner(
    phase: OverridePhase,
    cooldownRemaining: Int,
    redeemRemaining: Int,
    overridesToday: Int,
    onPeek: () -> Unit,
    onRedeem: () -> Unit,
) {
    val ready = phase == OverridePhase.READY
    val tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Surface(
        color = tint.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (ready) {
                            "Override ready · ${TimeFormatter.formatCountdown(redeemRemaining)} to take it"
                        } else {
                            "Override in ${TimeFormatter.formatCountdown(cooldownRemaining)}"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text =
                        if (ready) {
                            "Unclaimed, it evaporates — no penalty, same wait next time."
                        } else {
                            "$overridesToday ${if (overridesToday == 1) "override" else "overrides"} today. " +
                                "A 30s peek is one tap away."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = if (ready) onRedeem else onPeek,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) { Text(if (ready) "Take 15 min" else "Peek 30s", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun ordinal(n: Int): String {
    val suffix =
        when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    return "$n$suffix"
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
