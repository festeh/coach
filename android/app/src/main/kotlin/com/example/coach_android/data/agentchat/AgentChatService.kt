package com.example.coach_android.data.agentchat

import android.util.Log
import com.example.coach_android.data.websocket.ReconnectScheduler
import com.example.coach_android.data.websocket.WebSocketTransport
import com.example.coach_android.util.SafeUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.OffsetDateTime

// Frame kinds on the chat socket — mirror my-agents' WsIn/WsOut enums.
private object ClientFrame {
    const val MESSAGE = "message"
    const val CLEAR = "clear"
}

private object ServerFrame {
    const val HISTORY = "history"
    const val CHUNK = "chunk"
    const val OK = "ok"
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

    private val client = WebSocketTransport.client

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private val reconnect = ReconnectScheduler(scope, BASE_RECONNECT_DELAY_MS)
    private var shouldReconnect = false

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    // Whether a reason sent now would reach the coach. When it wouldn't, the
    // chat lets the user in on its own rather than leaving them walled.
    val connected: Boolean get() = isConnected

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
        reconnect.reset()
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        isConnecting = false
    }

    // `blocked` names the app the user was walled from, so the coach can ask
    // about it; null when the chat was opened by hand.
    fun send(
        content: String,
        blocked: String? = null,
    ) {
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
                    if (blocked != null) {
                        put("context", buildJsonObject { put("blocked", JsonPrimitive(blocked)) })
                    }
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
                reconnect.reset()
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
                            ChatMessage(role = mapped, content = content, ok = okEvent(m["events"]))
                        }
                    scope.launch { _events.emit(ChatEvent.History(messages)) }
                }
                ServerFrame.CHUNK -> {
                    val piece = obj["content"]?.jsonPrimitive?.content ?: return
                    scope.launch { _events.emit(ChatEvent.Chunk(piece)) }
                }
                ServerFrame.OK -> {
                    val offer = parseOk(obj) ?: return
                    scope.launch { _events.emit(ChatEvent.Ok(offer)) }
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

    // A history entry's events, as the server replays them: the reply's OK, if
    // it carried one.
    private fun okEvent(events: JsonElement?): OkOffer? =
        (events as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == ServerFrame.OK }
            ?.let(::parseOk)

    private fun parseOk(obj: JsonObject): OkOffer? =
        try {
            OkOffer(
                minutes = obj["minutes"]!!.jsonPrimitive.int,
                // OffsetDateTime takes both "Z" and "+00:00"; Instant.parse
                // on older runtimes only takes the former.
                at = OffsetDateTime.parse(obj["at"]!!.jsonPrimitive.content).toInstant(),
            )
        } catch (e: Exception) {
            Log.w(tag, "Ignoring malformed ok event: $obj")
            null
        }

    // Retried as long as a screen wants the socket — shouldReconnect is cleared on
    // disconnect(), so this stops when the chat closes rather than after a fixed
    // number of tries. A capped attempt count used to leave the chat dead after a
    // server restart, with only the error banner's Retry to bring it back.
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delayMs = reconnect.schedule { if (shouldReconnect) openSocket() } ?: return
        Log.i(tag, "Agent chat reconnect attempt ${reconnect.attempts} in ${delayMs / 1000}s")
    }

    fun dispose() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private const val BASE_RECONNECT_DELAY_MS = 1500L
    }
}
