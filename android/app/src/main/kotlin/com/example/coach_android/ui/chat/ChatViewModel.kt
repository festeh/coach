package com.example.coach_android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.data.agentchat.AgentChatService
import com.example.coach_android.data.agentchat.ChatEvent
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.data.model.OverridePhase
import com.example.coach_android.util.TimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connecting: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    val agentLocked: Boolean = true,
    val releaseLeftSeconds: Int = 0,
    // The override as the server runs it. The remaining seconds count down
    // locally between server frames; the rest changes only on a decision.
    val overridesToday: Int = 0,
    val overridePhase: OverridePhase = OverridePhase.IDLE,
    // Priced by the server, not derived here: the escalation rule lives there.
    val cooldownSeconds: Int = 0,
    val cooldownRemaining: Int = 0,
    val redeemRemaining: Int = 0,
    val nextCooldownSeconds: Int = 0,
) {
    val coolingDown: Boolean get() = overridePhase == OverridePhase.COOLING
    val overrideReady: Boolean get() = overridePhase == OverridePhase.READY
}

class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var eventsJob: Job? = null
    private var focusJob: Job? = null
    private var tickJob: Job? = null

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
                // A grant used to auto-dismiss the chat, racing out the coach's
                // reply. Now it just updates the header's lock chip; leaving is
                // the user's move.
                FocusMonitorService.getInstance()?.getMonitorLogic()?.focusData?.collect { data ->
                    _state.value =
                        _state.value.copy(
                            agentLocked = data.isAgentLocked,
                            releaseLeftSeconds = data.agentReleaseTimeLeft ?: 0,
                            overridesToday = data.overridesToday,
                            overridePhase = data.overridePhase,
                            cooldownSeconds = data.cooldownSeconds,
                            cooldownRemaining = data.cooldownRemaining,
                            redeemRemaining = data.redeemRemaining,
                            nextCooldownSeconds = data.nextCooldownSeconds,
                        )
                    // The server's numbers are the truth; resync the local clock.
                    startOverrideTicker()
                }
            }
        chat.connect()
    }

    // The server sends the override state once per frame, so the phone runs the
    // clock in between. Every frame resyncs it, which keeps drift from stacking.
    // When a countdown or redeem window runs out locally, the phase has changed
    // on the server without a broadcast — ask for a frame instead of guessing
    // what it became.
    private fun startOverrideTicker() {
        tickJob?.cancel()
        val ticking =
            when (_state.value.overridePhase) {
                OverridePhase.COOLING -> _state.value.cooldownRemaining > 0
                OverridePhase.READY -> _state.value.redeemRemaining > 0
                OverridePhase.IDLE -> false
            }
        if (!ticking) return
        tickJob =
            viewModelScope.launch {
                while (true) {
                    delay(1_000)
                    val current = _state.value
                    val left =
                        when (current.overridePhase) {
                            OverridePhase.COOLING -> (current.cooldownRemaining - 1).coerceAtLeast(0)
                            OverridePhase.READY -> (current.redeemRemaining - 1).coerceAtLeast(0)
                            OverridePhase.IDLE -> return@launch
                        }
                    _state.value =
                        when (current.overridePhase) {
                            OverridePhase.COOLING -> current.copy(cooldownRemaining = left)
                            else -> current.copy(redeemRemaining = left)
                        }
                    if (left == 0) {
                        FocusMonitorService.getInstance()?.getMonitorLogic()?.refreshFocusState()
                        return@launch
                    }
                }
            }
    }

    fun onStop() {
        eventsJob?.cancel()
        eventsJob = null
        focusJob?.cancel()
        focusJob = null
        tickJob?.cancel()
        tickJob = null
        chatService()?.disconnect()
    }

    fun send(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val newMessages =
            _state.value.messages +
                ChatMessage(role = ChatMessage.Role.USER, content = trimmed) +
                ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", streaming = true)
        _state.value =
            _state.value.copy(
                messages = newMessages,
                streaming = true,
                error = null,
            )
        chatService()?.send(trimmed)
    }

    fun reconnect() {
        _state.value = _state.value.copy(error = null)
        chatService()?.let {
            it.disconnect()
            it.connect()
        }
    }

    // One tap, whatever the phase; the server decides what it means. Idle needs
    // the written reason (it grants the day's first or arms the countdown);
    // ready redeems bare; cooling is answered locally to spare the round trip —
    // the server is still the authority and would refuse it the same way.
    fun takeOverride(reason: String): Result<Unit> {
        val monitorLogic =
            FocusMonitorService.getInstance()?.getMonitorLogic()
                ?: return Result.failure(IllegalStateException("Coach service is not available"))
        return when (_state.value.overridePhase) {
            OverridePhase.READY -> runCatching { monitorLogic.takeOverride("") }
            OverridePhase.COOLING -> {
                val left = TimeFormatter.formatCountdown(_state.value.cooldownRemaining)
                Result.failure(IllegalStateException("Still waiting — $left to go"))
            }
            OverridePhase.IDLE -> {
                val trimmedReason = reason.trim()
                if (trimmedReason.isEmpty()) {
                    Result.failure(IllegalArgumentException("A reason is required"))
                } else {
                    runCatching { monitorLogic.takeOverride(trimmedReason) }
                }
            }
        }
    }

    // The peek: 30 seconds, no reason asked, never refused. The nag of tapping
    // again every half-minute is the only price.
    fun takeShortUnblock(): Result<Unit> {
        val monitorLogic =
            FocusMonitorService.getInstance()?.getMonitorLogic()
                ?: return Result.failure(IllegalStateException("Coach service is not available"))
        return runCatching { monitorLogic.takeShortUnblock() }
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
            is ChatEvent.Done -> {
                val msgs = _state.value.messages.toMutableList()
                val lastIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                if (lastIdx >= 0 && msgs[lastIdx].streaming) {
                    msgs[lastIdx] = msgs[lastIdx].copy(streaming = false)
                }
                _state.value = _state.value.copy(messages = msgs, streaming = false)
            }
            is ChatEvent.Error -> {
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
            }
        }
    }

    private fun chatService(): AgentChatService? =
        FocusMonitorService.getInstance()?.getAgentChatService()
}
