package com.example.coach_android.data.agentchat

import android.util.Log
import com.example.coach_android.util.SafeUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

// Frame kinds on the chat socket — mirror my-agents' WsIn/WsOut enums.
private object ClientFrame {
    const val MESSAGE = "message"
    const val CLEAR = "clear"
}

private object ServerFrame {
    const val HISTORY = "history"
    const val CHUNK = "chunk"
    const val DONE = "done"
    const val ERROR = "error"
    const val PONG = "pong"
}

private object HistoryRole {
    const val HUMAN = "human"
    const val AI = "ai"
}

/**
 * WebSocket client to the my-agents server's coach agent at
 * `<baseUrl>/api/coach/ws/<threadId>`.
 *
 * One instance is constructed by AppContainer. The chat Activity connects
 * when foregrounded (`connect()`) and disconnects when backgrounded
 * (`disconnect()`). Thread state lives server-side; history is replayed
 * on each connect.
 */
class AgentChatService(
    baseUrl: String,
    private val threadId: String,
    apiToken: String = "",
) {
    private val tag = "AgentChatService"

    private val wsUrl: String =
        if (baseUrl.isBlank()) {
            ""
        } else {
            val trimmed = baseUrl.trimEnd('/')
            val scheme =
                when {
                    trimmed.startsWith("https://") -> "wss://"
                    trimmed.startsWith("http://") -> "ws://"
                    trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> ""
                    else -> "wss://"
                }
            val withoutScheme =
                trimmed
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("wss://")
                    .removePrefix("ws://")
            // Token in the query, matching the browser clients — Caddy checks it.
            val auth = if (apiToken.isEmpty()) "" else "?token=$apiToken"
            "$scheme$withoutScheme/api/coach/ws/$threadId$auth"
        }

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES)
            .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    fun connect() {
        if (wsUrl.isEmpty()) {
            Log.w(tag, "Agent chat URL not configured")
            scope.launch { _events.emit(ChatEvent.Error("Agent server URL not configured")) }
            return
        }
        shouldReconnect = true
        if (isConnecting || isConnected) return
        openSocket()
    }

    fun disconnect() {
        Log.i(tag, "Disconnecting agent chat WebSocket")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        isConnecting = false
        reconnectAttempts = 0
    }

    fun send(content: String) {
        val ws = webSocket
        if (!isConnected || ws == null) {
            Log.w(tag, "send() called while not connected (isConnected=$isConnected, ws=${ws != null})")
            scope.launch { _events.emit(ChatEvent.Error("Not connected")) }
            return
        }
        val frame =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("type", JsonPrimitive(ClientFrame.MESSAGE))
                    put("content", JsonPrimitive(content))
                },
            )
        val queued = ws.send(frame)
        if (!queued) {
            Log.w(tag, "WebSocket.send() returned false; frame not queued")
            scope.launch { _events.emit(ChatEvent.Error("Failed to send (queue closed)")) }
        }
    }

    // Ask the server to wipe this thread. It answers with an empty history
    // frame, which resets the UI the same way a fresh connect would.
    fun clear() {
        val ws = webSocket
        if (!isConnected || ws == null) {
            scope.launch { _events.emit(ChatEvent.Error("Not connected")) }
            return
        }
        val frame =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("type", JsonPrimitive(ClientFrame.CLEAR)) },
            )
        ws.send(frame)
    }

    private fun openSocket() {
        isConnecting = true
        scope.launch { _events.emit(ChatEvent.Connecting) }
        Log.i(tag, "Connecting to agent chat: ${SafeUrl.redacted(wsUrl)}")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "Agent chat connected")
                isConnected = true
                isConnecting = false
                reconnectAttempts = 0
                scope.launch { _events.emit(ChatEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Agent chat error: ${t.message}")
                isConnected = false
                isConnecting = false
                scope.launch { _events.emit(ChatEvent.Error(t.message ?: "Connection failed")) }
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Agent chat closing: $code $reason")
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "Agent chat closed: $code $reason")
                isConnected = false
                isConnecting = false
                if (shouldReconnect && code != 1000) scheduleReconnect()
            }
        }

    private fun handleMessage(text: String) {
        try {
            val obj = json.decodeFromString<JsonObject>(text)
            when (obj["type"]?.jsonPrimitive?.content) {
                ServerFrame.HISTORY -> {
                    val arr = obj["messages"] as? JsonArray ?: JsonArray(emptyList())
                    val messages =
                        arr.mapNotNull { el ->
                            val m = el.jsonObject
                            val role = m["role"]?.jsonPrimitive?.content
                            val content = m["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val mapped =
                                when (role) {
                                    HistoryRole.HUMAN -> ChatMessage.Role.USER
                                    HistoryRole.AI -> ChatMessage.Role.ASSISTANT
                                    else -> return@mapNotNull null
                                }
                            ChatMessage(role = mapped, content = content)
                        }
                    scope.launch { _events.emit(ChatEvent.History(messages)) }
                }
                ServerFrame.CHUNK -> {
                    val piece = obj["content"]?.jsonPrimitive?.content ?: return
                    scope.launch { _events.emit(ChatEvent.Chunk(piece)) }
                }
                ServerFrame.DONE -> {
                    scope.launch { _events.emit(ChatEvent.Done) }
                }
                ServerFrame.ERROR -> {
                    val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    Log.e(tag, "Server error frame: $message (raw=$text)")
                    scope.launch { _events.emit(ChatEvent.Error(message)) }
                }
                ServerFrame.PONG -> {}
                else -> Log.d(tag, "Ignoring frame: $text")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse frame: ${e.message}")
        }
    }

    // Retried as long as a screen wants the socket — shouldReconnect is cleared on
    // disconnect(), so this stops when the chat closes rather than after a fixed
    // number of tries. A capped attempt count used to leave the chat dead after a
    // server restart, with only the error banner's Retry to bring it back.
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        if (reconnectJob?.isActive == true) return
        reconnectAttempts++
        val delayMs =
            (BASE_RECONNECT_DELAY_MS * 2.0.pow(min(reconnectAttempts - 1, 6).toDouble())).toLong() +
                (0..999).random()
        Log.i(tag, "Agent chat reconnect attempt $reconnectAttempts in ${delayMs / 1000}s")
        reconnectJob =
            scope.launch {
                delay(delayMs)
                if (shouldReconnect) openSocket()
            }
    }

    fun dispose() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private const val BASE_RECONNECT_DELAY_MS = 1500L
    }
}
