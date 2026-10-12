package com.example.coach_android.data.agentchat

data class ChatMessage(
    val role: Role,
    val content: String,
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }
}

sealed interface ChatEvent {
    data class History(val messages: List<ChatMessage>) : ChatEvent
    data class Chunk(val text: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
    data object Connecting : ChatEvent
    data object Connected : ChatEvent
}
