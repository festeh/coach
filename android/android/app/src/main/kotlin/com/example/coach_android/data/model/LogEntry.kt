package com.example.coach_android.data.model

enum class LogLevel(
    val displayName: String,
) {
    VERBOSE("Verbose"),
    DEBUG("Debug"),
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
}

enum class LogSource(
    val displayName: String,
) {
    SERVICE("Service"),
    WEBSOCKET("WebSocket"),
    UI("UI"),
}

enum class LogCategory(
    val displayName: String,
) {
    GENERAL("General"),
    FOCUS("Focus"),
    OVERLAY("Overlay"),
    RULE("Rule"),
    CONNECTION("Connection"),
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val source: LogSource,
    val category: LogCategory,
    val tag: String,
    val message: String,
)
