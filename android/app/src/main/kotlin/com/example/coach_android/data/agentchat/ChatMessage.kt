package com.example.coach_android.data.agentchat

import java.time.Instant

data class ChatMessage(
    val role: Role,
    val content: String,
    val streaming: Boolean = false,
    // The coach's OK on this reply: how long it lets the user in, and when it
    // was offered. Only the latest reply's OK is ever shown, and only briefly.
    val ok: OkOffer? = null,
) {
    // NOTICE is local: said by the app, not the coach, and never sent.
    enum class Role { USER, ASSISTANT, NOTICE }
}

data class OkOffer(
    val minutes: Int,
    val at: Instant,
)

sealed interface ChatEvent {
    data class History(val messages: List<ChatMessage>) : ChatEvent
    data class Chunk(val text: String) : ChatEvent
    data class Ok(val offer: OkOffer) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
    data object Connecting : ChatEvent
    data object Connected : ChatEvent
}
