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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import com.example.coach_android.util.TimeFormatter
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    // True when hosted inside the app's Scaffold (the Chat tab), which already
    // consumes system-bar insets — padding again would draw dead bands.
    embedded: Boolean = false,
    // The app behind the wall, when the chat was opened by one.
    blockedApp: BlockedApp? = null,
    // Called once the OK's release has landed, to put the user back in the app.
    onReturnToApp: (BlockedApp) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
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

    LaunchedEffect(blockedApp) { viewModel.setBlockedApp(blockedApp) }

    LaunchedEffect(state.returnTo) {
        state.returnTo?.let {
            onReturnToApp(it)
            viewModel.onReturned()
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length, state.pendingOk) {
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
            )

            state.blockedApp?.let { app -> BlockedBanner(label = app.label) }

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
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MessageBubble(
                                    message = message,
                                    isLatest = idx == state.messages.lastIndex,
                                )
                                state.pendingOk
                                    ?.takeIf { it.messageIndex == idx }
                                    ?.let { ok -> OkButton(minutes = ok.minutes, onClick = viewModel::takeOk) }
                            }
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

// Under the latest reply that lets the user in: the coach's OK, or the local
// one when the coach can't be reached.
@Composable
private fun OkButton(
    minutes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Button(onClick = onClick) { Text("OK · $minutes min") }
    }
}

// Says which app brought the user here, and asks about it.
@Composable
private fun BlockedBanner(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "What brings you to $label?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isLatest: Boolean,
) {
    if (message.role == ChatMessage.Role.NOTICE) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        return
    }
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
            text = "What do you need?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tell the coach what you're about to do.\nAny honest reason works.",
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
