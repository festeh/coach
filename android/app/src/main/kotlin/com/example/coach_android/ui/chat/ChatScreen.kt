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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.util.TimeFormatter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
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

    // Whether the list keeps the end of the chat in view as replies stream in.
    // Scrolling away stops it, so a long reply can be read from its top while
    // the rest arrives; reaching the end again, sending, or the jump button
    // picks it back up.
    var follow by remember { mutableStateOf(true) }
    val followScroll =
        remember(listState) {
            object : NestedScrollConnection {
                // Only the user's drags and flings reach here: the list's own
                // scrollToEnd() doesn't dispatch nested scroll.
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    follow = !listState.canScrollForward
                    return Offset.Zero
                }
            }
        }
    val showScrollToBottom by remember { derivedStateOf { !follow && listState.canScrollForward } }

    LaunchedEffect(blockedApp) { viewModel.setBlockedApp(blockedApp) }

    LaunchedEffect(state.returnTo) {
        state.returnTo?.let {
            onReturnToApp(it)
            viewModel.onReturned()
        }
    }

    // While following: each new message, streamed chunk and OK button...
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length, state.pendingOk) {
        if (follow) listState.scrollToEnd(state.messages.lastIndex)
    }
    // ...and the keyboard, which resizes the list from the bottom.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize }
            .collect { if (follow) listState.scrollToEnd(state.messages.lastIndex) }
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
                        modifier = Modifier.fillMaxSize().nestedScroll(followScroll),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.messages.isEmpty() && !state.connecting) {
                            item { EmptyHint() }
                        }
                        // Keyed by position, the default. A key built from the
                        // content changed with every streamed chunk, which threw
                        // the item away and lost the scroll anchor each time.
                        items(count = state.messages.size) { idx ->
                            val message = state.messages[idx]
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MessageBubble(message = message)
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
                        follow = true
                        scope.launch { listState.scrollToEnd(state.messages.lastIndex, animated = true) }
                    },
                    hasNewContent = state.streaming,
                )
            }

            InputRow(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        follow = true
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

    // Drawn under the status bar, which the Chat tab leaves to it.
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
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
                // auto-close, so the coach's reply stays on screen. Locked is the
                // resting state, so it stays quiet; the accent marks a release.
                val chipColor =
                    if (agentLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color =
                        if (agentLocked) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            chipColor.copy(alpha = 0.15f)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (agentLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = chipColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text =
                                if (agentLocked) {
                                    "Locked"
                                } else {
                                    "Released · ${TimeFormatter.formatFocusTime(releaseLeftSeconds)}"
                                },
                            style = MaterialTheme.typography.labelMedium,
                            color = chipColor,
                        )
                    }
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
    Button(onClick = onClick) { Text("OK · $minutes min") }
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
private fun MessageBubble(message: ChatMessage) {
    when (message.role) {
        ChatMessage.Role.NOTICE ->
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        ChatMessage.Role.USER -> UserBubble(message.content)
        ChatMessage.Role.ASSISTANT ->
            if (message.content.isEmpty() && message.streaming) {
                TypingIndicator(modifier = Modifier.padding(vertical = 6.dp))
            } else {
                CoachReply(message.content)
            }
    }
}

// The user's lines keep a bubble; the coach's replies run full width, which
// reads better for its longer, multi-paragraph answers.
@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        SelectionContainer {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun CoachReply(text: String) {
    val body = MaterialTheme.typography.bodyMedium
    val heading = MaterialTheme.typography.titleSmall
    val mono = body.copy(fontFamily = FontFamily.Monospace)
    SelectionContainer {
        Markdown(
            content = text,
            colors =
                markdownColor(
                    text = MaterialTheme.colorScheme.onSurface,
                    codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            typography =
                markdownTypography(
                    h1 = MaterialTheme.typography.titleLarge,
                    h2 = MaterialTheme.typography.titleMedium,
                    h3 = heading,
                    h4 = heading,
                    h5 = heading,
                    h6 = heading,
                    text = body,
                    code = mono,
                    inlineCode = mono,
                    quote = body.copy(fontStyle = FontStyle.Italic),
                    paragraph = body,
                    ordered = body,
                    bullet = body,
                    list = body,
                    link =
                        body.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                ),
            padding = markdownPadding(block = 4.dp),
            // Animating each size change would fight the scroll that follows
            // a streaming reply.
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
    }
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(20.dp),
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
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MessageField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
            )
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

// As tall as the send button on one line, growing to five as the user types.
@Composable
private fun MessageField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val textStyle = MaterialTheme.typography.bodyLarge
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        maxLines = 5,
        decorationBox = { innerTextField ->
            Box(
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color =
                                if (value.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            shape = shape,
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text("Message…", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                innerTextField()
            }
        },
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

// To the very end of the list. scrollToItem stops at the last item's top, and
// a reply taller than the list goes on below it; scrolling past the end is
// clamped, so a full item's height more always lands on the end.
private suspend fun LazyListState.scrollToEnd(
    lastIndex: Int,
    animated: Boolean = false,
) {
    if (lastIndex < 0) return
    if (animated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val rest = layoutInfo.visibleItemsInfo.lastOrNull()?.size?.toFloat() ?: return
    if (animated) animateScrollBy(rest) else scrollBy(rest)
}
