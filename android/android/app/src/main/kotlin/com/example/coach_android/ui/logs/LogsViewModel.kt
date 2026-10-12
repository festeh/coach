package com.example.coach_android.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.data.model.LogCategory
import com.example.coach_android.data.model.LogEntry
import com.example.coach_android.data.model.LogLevel
import com.example.coach_android.data.model.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

data class LogsUiState(
    val entries: List<LogEntry> = emptyList(),
    val filteredEntries: List<LogEntry> = emptyList(),
    val searchQuery: String = "",
    val levelFilter: LogLevel? = null,
    val sourceFilter: LogSource? = null,
    val categoryFilter: LogCategory? = null,
    val isLoading: Boolean = true,
)

class LogsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private val appTags =
        setOf(
            "FocusMonitorService",
            "MonitorLogic",
            "WebSocketService",
            "AppMonitorHandler",
            "MainActivity",
            "ServiceNotificationManager",
        )

    init {
        loadLogs()
    }

    private fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "*:V"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val entries = mutableListOf<LogEntry>()

                reader.forEachLine { line ->
                    val entry = parseLine(line)
                    if (entry != null) {
                        entries.add(entry)
                    }
                }

                // Keep most recent 500
                val trimmed = entries.takeLast(500)
                _state.value =
                    _state.value.copy(
                        entries = trimmed,
                        isLoading = false,
                    )
                applyFilters()
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    private fun parseLine(line: String): LogEntry? {
        // logcat format: "MM-DD HH:MM:SS.mmm L/Tag(PID): message"
        val regex = Regex("""(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s([VDIWEF])/(\S+)\s*\(\s*\d+\):\s*(.*)""")
        val match = regex.matchEntire(line) ?: return null

        val (timestamp, levelChar, tag, message) = match.destructured

        // Filter to app-relevant tags only
        val cleanTag = tag.trim()
        if (!appTags.any { cleanTag.startsWith(it) }) return null

        val level =
            when (levelChar) {
                "V" -> LogLevel.VERBOSE
                "D" -> LogLevel.DEBUG
                "I" -> LogLevel.INFO
                "W" -> LogLevel.WARNING
                "E" -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }

        val source =
            when {
                cleanTag.startsWith("WebSocket") -> LogSource.WEBSOCKET
                cleanTag.startsWith("FocusMonitor") || cleanTag.startsWith("MonitorLogic") -> LogSource.SERVICE
                cleanTag.startsWith("MainActivity") -> LogSource.UI
                else -> LogSource.SERVICE
            }

        val category =
            when {
                message.contains("overlay", ignoreCase = true) -> LogCategory.OVERLAY
                message.contains("focus", ignoreCase = true) -> LogCategory.FOCUS
                message.contains("rule", ignoreCase = true) || message.contains("challenge", ignoreCase = true) -> LogCategory.RULE
                message.contains("WebSocket", ignoreCase = true) || message.contains("connect", ignoreCase = true) -> LogCategory.CONNECTION
                else -> LogCategory.GENERAL
            }

        return LogEntry(
            timestamp = timestamp,
            level = level,
            source = source,
            category = category,
            tag = cleanTag,
            message = message.trim(),
        )
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        applyFilters()
    }

    fun setLevelFilter(level: LogLevel?) {
        _state.value = _state.value.copy(levelFilter = level)
        applyFilters()
    }

    fun setSourceFilter(source: LogSource?) {
        _state.value = _state.value.copy(sourceFilter = source)
        applyFilters()
    }

    fun setCategoryFilter(category: LogCategory?) {
        _state.value = _state.value.copy(categoryFilter = category)
        applyFilters()
    }

    private fun applyFilters() {
        val s = _state.value
        var filtered = s.entries

        s.levelFilter?.let { level ->
            filtered = filtered.filter { it.level == level }
        }
        s.sourceFilter?.let { source ->
            filtered = filtered.filter { it.source == source }
        }
        s.categoryFilter?.let { category ->
            filtered = filtered.filter { it.category == category }
        }
        if (s.searchQuery.isNotBlank()) {
            val q = s.searchQuery.lowercase()
            filtered =
                filtered.filter {
                    it.message.lowercase().contains(q) || it.tag.lowercase().contains(q)
                }
        }

        _state.value = _state.value.copy(filteredEntries = filtered)
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true)
        loadLogs()
    }

    fun getExportText(): String =
        _state.value.filteredEntries.joinToString("\n") { entry ->
            "${entry.timestamp} ${entry.level.name[0]}/${entry.tag}: ${entry.message}"
        }
}
