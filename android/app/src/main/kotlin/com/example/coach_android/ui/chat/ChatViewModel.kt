package com.example.coach_android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.data.agentchat.AgentChatService
import com.example.coach_android.data.agentchat.ChatEvent
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.data.agentchat.OkOffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

// The app the user was walled from: the coach asks about it by name, and the
// OK hands the user back to it.
data class BlockedApp(
    val packageName: String,
    val label: String,
)

// An OK the user can take right now — on the coach's latest reply, or on the
// local offer made when the coach can't be reached.
data class PendingOk(
    val messageIndex: Int,
    val minutes: Int,
    // What the release journals: the reason the OK answered and the reply
    // that carried it.
    val reason: String,
    val reply: String,
    val source: String,
    // The coach's offer, to remember it was taken; null for a local offer.
    val offer: OkOffer?,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connecting: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    val agentLocked: Boolean = true,
    val releaseLeftSeconds: Int = 0,
    val blockedApp: BlockedApp? = null,
    val pendingOk: PendingOk? = null,
    // Set once the release has landed; the screen hands the user back to the
    // app and calls onReturned().
    val returnTo: BlockedApp? = null,
)

class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var eventsJob: Job? = null
    private var focusJob: Job? = null
    private var okExpiryJob: Job? = null

    // Offers already taken this session, so a replayed history can't hand the
    // same OK out twice.
    private val takenOffers = mutableSetOf<OkOffer>()

    fun onStart() {
        val chat = chatService() ?: run {
            _state.value = _state.value.copy(error = "Chat service not available")
            return
        }
        eventsJob?.cancel()
        eventsJob =
            viewModelScope.launch {
                chat.events.collect { event -> reduce(event) }
            }
        focusJob?.cancel()
        focusJob =
            viewModelScope.launch {
                FocusMonitorService.getInstance()?.getMonitorLogic()?.focusData?.collect { data ->
                    _state.value =
                        _state.value.copy(
                            agentLocked = data.isAgentLocked,
                            releaseLeftSeconds = data.agentReleaseTimeLeft ?: 0,
                        )
                }
            }
        chat.connect()
    }

    fun onStop() {
        eventsJob?.cancel()
        eventsJob = null
        focusJob?.cancel()
        focusJob = null
        chatService()?.disconnect()
    }

    fun setBlockedApp(app: BlockedApp?) {
        _state.value = _state.value.copy(blockedApp = app)
    }

    fun send(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val chat = chatService()
        if (chat == null || !chat.connected) {
            // The coach can't hear the reason, but it still counts: the user is
            // let in rather than left walled by an outage.
            offerFallback(_state.value.messages + ChatMessage(role = ChatMessage.Role.USER, content = trimmed))
            return
        }
        val newMessages =
            _state.value.messages +
                ChatMessage(role = ChatMessage.Role.USER, content = trimmed) +
                ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", streaming = true)
        _state.value =
            _state.value.copy(
                messages = newMessages,
                streaming = true,
                error = null,
                pendingOk = null,
            )
        chat.send(trimmed, _state.value.blockedApp?.label)
    }

    // Release for the OK's minutes, then — when the chat came from a wall —
    // hand the user back to the app once the release has landed. Launching it
    // any sooner would only wall it again on arrival.
    fun takeOk() {
        val ok = _state.value.pendingOk ?: return
        val monitorLogic =
            FocusMonitorService.getInstance()?.getMonitorLogic() ?: run {
                _state.value = _state.value.copy(error = "Coach service is not available")
                return
            }
        try {
            monitorLogic.release(ok.minutes * 60, ok.reason, ok.reply, ok.source)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Couldn't reach Coach — try OK again")
            return
        }
        ok.offer?.let { takenOffers += it }
        okExpiryJob?.cancel()
        _state.value = _state.value.copy(pendingOk = null, error = null)

        val app = _state.value.blockedApp ?: return
        viewModelScope.launch {
            withTimeoutOrNull(RELEASE_WAIT_MS) { monitorLogic.focusData.first { !it.isFocused } }
            _state.value = _state.value.copy(returnTo = app, blockedApp = null)
        }
    }

    fun onReturned() {
        _state.value = _state.value.copy(returnTo = null)
    }

    fun reconnect() {
        _state.value = _state.value.copy(error = null)
        chatService()?.let {
            it.disconnect()
            it.connect()
        }
    }

    // The server wipes the thread and replies with an empty history frame,
    // which reduce() folds into a cleared message list.
    fun clear() {
        chatService()?.clear()
    }

    private fun reduce(event: ChatEvent) {
        when (event) {
            is ChatEvent.History -> {
                _state.value =
                    _state.value.copy(
                        messages = event.messages,
                        connecting = false,
                        error = null,
                    )
                showOk(latestOk(event.messages))
            }
            is ChatEvent.Connecting -> {
                _state.value = _state.value.copy(connecting = true)
            }
            is ChatEvent.Connected -> {
                _state.value = _state.value.copy(connecting = false, error = null)
            }
            is ChatEvent.Chunk -> {
                val msgs = _state.value.messages.toMutableList()
                val lastIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                if (lastIdx >= 0 && msgs[lastIdx].streaming) {
                    msgs[lastIdx] =
                        msgs[lastIdx].copy(content = msgs[lastIdx].content + event.text)
                } else {
                    msgs += ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        content = event.text,
                        streaming = true,
                    )
                }
                _state.value = _state.value.copy(messages = msgs, streaming = true)
            }
            is ChatEvent.Ok -> {
                // The OK belongs to this turn's reply. Frames are emitted on
                // separate coroutines, so it may land after Done as well as
                // before; either way it goes on the reply after the last
                // user message, or on an empty one if the coach said nothing.
                val msgs = _state.value.messages.toMutableList()
                val lastUser = msgs.indexOfLast { it.role == ChatMessage.Role.USER }
                val lastIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                if (lastIdx > lastUser) {
                    msgs[lastIdx] = msgs[lastIdx].copy(ok = event.offer)
                } else {
                    msgs += ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", ok = event.offer)
                }
                _state.value = _state.value.copy(messages = msgs)
                if (!_state.value.streaming) showOk(latestOk(msgs))
            }
            is ChatEvent.Done -> {
                val msgs = _state.value.messages.toMutableList()
                val lastIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                if (lastIdx >= 0 && msgs[lastIdx].streaming) {
                    msgs[lastIdx] = msgs[lastIdx].copy(streaming = false)
                }
                _state.value = _state.value.copy(messages = msgs, streaming = false)
                showOk(latestOk(msgs))
            }
            is ChatEvent.Error -> {
                val turnFailed = _state.value.streaming
                val msgs = _state.value.messages.toMutableList()
                // Drop trailing empty streaming bubble if the request errored before any content.
                val lastIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                if (lastIdx >= 0 && msgs[lastIdx].streaming && msgs[lastIdx].content.isEmpty()) {
                    msgs.removeAt(lastIdx)
                }
                _state.value =
                    _state.value.copy(
                        messages = msgs,
                        streaming = false,
                        connecting = false,
                        error = event.message,
                    )
                // A reason that got no answer still lets the user in.
                if (turnFailed) offerFallback(msgs)
            }
        }
    }

    // A local notice with its own OK, for the reason in the last user message.
    private fun offerFallback(messages: List<ChatMessage>) {
        val reason = messages.lastOrNull { it.role == ChatMessage.Role.USER }?.content ?: return
        val withNotice =
            messages +
                ChatMessage(
                    role = ChatMessage.Role.NOTICE,
                    content = "Coach can't be reached right now. Go ahead — take $FALLBACK_MINUTES minutes.",
                )
        okExpiryJob?.cancel()
        _state.value =
            _state.value.copy(
                messages = withNotice,
                streaming = false,
                pendingOk =
                    PendingOk(
                        messageIndex = withNotice.lastIndex,
                        minutes = FALLBACK_MINUTES,
                        reason = reason,
                        reply = "",
                        source = SOURCE_FALLBACK,
                        offer = null,
                    ),
            )
    }

    // The coach's OK counts only on its latest reply, only once, and only for
    // a few minutes — an OK left in yesterday's thread is not a way in today.
    private fun latestOk(messages: List<ChatMessage>): PendingOk? {
        val idx = messages.lastIndex
        val last = messages.getOrNull(idx) ?: return null
        if (last.role != ChatMessage.Role.ASSISTANT || last.streaming) return null
        val offer = last.ok ?: return null
        if (offer in takenOffers || !Instant.now().isBefore(offer.at.plus(OK_LIFETIME))) return null
        val reason = messages.subList(0, idx).lastOrNull { it.role == ChatMessage.Role.USER }?.content ?: ""
        return PendingOk(
            messageIndex = idx,
            minutes = offer.minutes,
            reason = reason,
            reply = last.content,
            source = SOURCE_CHAT,
            offer = offer,
        )
    }

    private fun showOk(ok: PendingOk?) {
        okExpiryJob?.cancel()
        _state.value = _state.value.copy(pendingOk = ok)
        val offer = ok?.offer ?: return
        okExpiryJob =
            viewModelScope.launch {
                delay(Duration.between(Instant.now(), offer.at.plus(OK_LIFETIME)).toMillis().coerceAtLeast(0))
                if (_state.value.pendingOk?.offer == offer) {
                    _state.value = _state.value.copy(pendingOk = null)
                }
            }
    }

    private fun chatService(): AgentChatService? =
        FocusMonitorService.getInstance()?.getAgentChatService()

    companion object {
        private val OK_LIFETIME: Duration = Duration.ofMinutes(5)
        private const val FALLBACK_MINUTES = 10
        private const val SOURCE_CHAT = "chat"
        private const val SOURCE_FALLBACK = "fallback"

        // How long the OK waits for the server's release before handing the
        // app back anyway.
        private const val RELEASE_WAIT_MS = 5_000L
    }
}
