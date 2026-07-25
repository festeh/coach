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
import kotlinx.coroutines.Job
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
) {
    val today: UnlockDay? get() = unlockTrend.lastOrNull()
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
                        )
                    if (wasLocked && !data.isAgentLocked) {
                        refreshStatsAfterGrant()
                    }
                }
            }
        observeUnlockStats()
        chat.connect()
    }

    fun onStop() {
        eventsJob?.cancel()
        eventsJob = null
        focusJob?.cancel()
        focusJob = null
        statsJob?.cancel()
        statsJob = null
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
        val monitorLogic =
            FocusMonitorService.getInstance()?.getMonitorLogic()
                ?: return Result.failure(IllegalStateException("Coach service is not available"))
        return runCatching { monitorLogic.takeOverride(trimmedReason) }
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
