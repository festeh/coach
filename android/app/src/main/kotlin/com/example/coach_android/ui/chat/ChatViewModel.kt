package com.example.coach_android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.data.agentchat.AgentChatService
import com.example.coach_android.data.agentchat.ChatEvent
import com.example.coach_android.data.agentchat.ChatMessage
import com.example.coach_android.data.coach.UnlockDayDto
import com.example.coach_android.data.coach.UnlockStatsRepository
import com.example.coach_android.util.TimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UnlockDay(
    val date: LocalDate,
    val unlocks: Int,
    val unlockedSeconds: Int,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connecting: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    val agentLocked: Boolean = true,
    val releaseLeftSeconds: Int = 0,
    // Oldest first, today last, one entry per day the server reported. Empty
    // until a cached or fetched answer lands, which keeps the header from
    // flashing zeros it would have to take back.
    val unlockTrend: List<UnlockDay> = emptyList(),
    // The detail sheet's longer window, loaded when it opens. Falls back to the
    // card's week so the sheet has something to show while the month arrives.
    val unlockDetail: List<UnlockDay> = emptyList(),
    val unlockDetailLoading: Boolean = false,
    // Override cooldown. remaining counts down locally between server frames;
    // the rest changes only when a decision is made.
    val overridesToday: Int = 0,
    val cooldownSeconds: Int = 0,
    val cooldownRemaining: Int = 0,
    // Priced by the server, not derived here: the escalation rule lives there.
    val nextCooldownSeconds: Int = 0,
    val shortUnblocksLeft: Int = 0,
    val refusal: String? = null,
) {
    val coolingDown: Boolean get() = cooldownRemaining > 0
}

class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var forced: Boolean = false
    private var eventsJob: Job? = null
    private var focusJob: Job? = null
    private var statsJob: Job? = null
    private var refusalJob: Job? = null
    private var tickJob: Job? = null

    fun setForced(value: Boolean) {
        forced = value
    }

    fun isForced(): Boolean = forced

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
                    val wasLocked = _state.value.agentLocked
                    _state.value =
                        _state.value.copy(
                            agentLocked = data.isAgentLocked,
                            releaseLeftSeconds = data.agentReleaseTimeLeft ?: 0,
                            overridesToday = data.overridesToday,
                            cooldownSeconds = data.cooldownSeconds,
                            cooldownRemaining = data.cooldownRemaining,
                            nextCooldownSeconds = data.nextCooldownSeconds,
                            shortUnblocksLeft = data.shortUnblocksLeft,
                        )
                    // The server's number is the truth; resync the local clock to it.
                    startCooldownTicker()
                    if (wasLocked && !data.isAgentLocked) {
                        refreshStatsAfterGrant()
                    }
                }
            }
        refusalJob?.cancel()
        refusalJob =
            viewModelScope.launch {
                FocusMonitorService.getInstance()?.getMonitorLogic()?.unblockRefusals?.collect { data ->
                    val remaining = (data["override_cooldown_remaining"] as? Number)?.toInt() ?: 0
                    val left = (data["short_unblocks_left"] as? Number)?.toInt() ?: 0
                    _state.value =
                        _state.value.copy(
                            cooldownRemaining = remaining,
                            shortUnblocksLeft = left,
                            overridesToday = (data["overrides_today"] as? Number)?.toInt() ?: _state.value.overridesToday,
                            cooldownSeconds = (data["override_cooldown_seconds"] as? Number)?.toInt() ?: _state.value.cooldownSeconds,
                            refusal =
                                if (data["request"] == "short_unblock") {
                                    "No 30-second unblocks left in this cooldown."
                                } else {
                                    "Still cooling down — ${TimeFormatter.formatCountdown(remaining)} to go."
                                },
                        )
                    startCooldownTicker()
                }
            }
        observeUnlockStats()
        chat.connect()
    }

    // The server sends the cooldown once per frame, so the phone runs the clock
    // in between. Every server frame resyncs it, which keeps drift from stacking.
    private fun startCooldownTicker() {
        tickJob?.cancel()
        if (_state.value.cooldownRemaining <= 0) return
        tickJob =
            viewModelScope.launch {
                while (_state.value.cooldownRemaining > 0) {
                    delay(1_000)
                    val left = _state.value.cooldownRemaining - 1
                    _state.value =
                        _state.value.copy(
                            cooldownRemaining = left.coerceAtLeast(0),
                            // The hatch only exists inside a cooldown; when the
                            // wait ends the full override is back on the table.
                            shortUnblocksLeft = if (left <= 0) 0 else _state.value.shortUnblocksLeft,
                        )
                }
            }
    }

    fun dismissRefusal() {
        _state.value = _state.value.copy(refusal = null)
    }

    fun onStop() {
        eventsJob?.cancel()
        eventsJob = null
        focusJob?.cancel()
        focusJob = null
        statsJob?.cancel()
        statsJob = null
        refusalJob?.cancel()
        refusalJob = null
        tickJob?.cancel()
        tickJob = null
        chatService()?.disconnect()
    }

    // The cached ledger renders at once; the server's answer replaces it. Days the
    // server didn't report simply aren't drawn — better a short week than invented
    // zeroes.
    private fun observeUnlockStats() {
        val repository = unlockStats() ?: return
        statsJob?.cancel()
        statsJob =
            viewModelScope.launch {
                repository.stats.collect { days ->
                    _state.value = _state.value.copy(unlockTrend = toUnlockDays(days))
                }
            }
        viewModelScope.launch { repository.refresh() }
    }

    // Loaded on each open so the sheet is never showing a month that has moved on.
    fun loadUnlockDetail() {
        val repository = unlockStats() ?: return
        _state.value = _state.value.copy(unlockDetailLoading = true)
        viewModelScope.launch {
            val days = repository.fetchWindow(DETAIL_DAYS)
            _state.value =
                _state.value.copy(
                    unlockDetail = days?.let(::toUnlockDays) ?: _state.value.unlockDetail,
                    unlockDetailLoading = false,
                )
        }
    }

    // A grant lands as a lock-state flip on the focus socket; the ledger it came
    // from is the server's, so ask again rather than guessing the new totals.
    private fun refreshStatsAfterGrant() {
        val repository = unlockStats() ?: return
        viewModelScope.launch { repository.refresh() }
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

    fun takeOverride(reason: String): Result<Unit> {
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            return Result.failure(IllegalArgumentException("A reason is required"))
        }
        // The server is the authority and refuses on its own; checking here just
        // spares the round trip and answers in the dialog the user is looking at.
        if (_state.value.coolingDown) {
            val left = TimeFormatter.formatCountdown(_state.value.cooldownRemaining)
            return Result.failure(IllegalStateException("Still cooling down — $left to go"))
        }
        val monitorLogic =
            FocusMonitorService.getInstance()?.getMonitorLogic()
                ?: return Result.failure(IllegalStateException("Coach service is not available"))
        return runCatching { monitorLogic.takeOverride(trimmedReason) }
    }

    // The escape hatch inside a cooldown. No reason is asked: 30 seconds is short
    // enough that the friction would cost more than it buys.
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

    private fun unlockStats(): UnlockStatsRepository? =
        FocusMonitorService.getInstance()?.getUnlockStats()

    // A day the server labelled unparseably is dropped rather than drawn wrong.
    private fun toUnlockDays(days: List<UnlockDayDto>): List<UnlockDay> =
        days.mapNotNull { day ->
            runCatching { LocalDate.parse(day.day) }.getOrNull()?.let { date ->
                UnlockDay(
                    date = date,
                    unlocks = day.unlocks,
                    unlockedSeconds = day.unlockedSeconds,
                )
            }
        }

    private companion object {
        const val DETAIL_DAYS = 30
    }
}
