package com.example.coach_android.data.agentchat

import android.util.Log
import com.example.coach_android.data.websocket.ReconnectScheduler
import com.example.coach_android.data.websocket.WebSocketTransport
import com.example.coach_android.util.SafeUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.time.OffsetDateTime

// Frame kinds on the chat socket — mirror my-agents' WsIn/WsOut enums.
private object ClientFrame {
    const val MESSAGE = "message"
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
 * `<baseUrl>/api/coach/ws/<THREAD_ID>`.
 *
 * One instance is constructed by AppContainer. The chat Activity connects
 * when foregrounded (`connect()`) and disconnects when backgrounded
 * (`disconnect()`). Thread state lives server-side; history is replayed
 * on each connect.
 */
class AgentChatService(
    baseUrl: String,
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
            "$scheme$withoutScheme/api/coach/ws/$THREAD_ID$auth"
        }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = WebSocketTransport.client

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private val reconnect = ReconnectScheduler(scope, BASE_RECONNECT_DELAY_MS)
    private var shouldReconnect = false

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = Channel.UNLIMITED)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    // Emitted in place, on the caller's thread: OkHttp hands frames over one at
    // a time, so chunks reach the chat in the order the server sent them. A
    // coroutine per frame on the IO pool used to reorder them and garble the
    // streamed reply. The buffer is unbounded, so tryEmit never drops one.
    private fun emit(event: ChatEvent) {
        _events.tryEmit(event)
    }

    // Whether a reason sent now would reach the coach. When it wouldn't, the
    // chat lets the user in on its own rather than leaving them walled.
    val connected: Boolean get() = isConnected

    fun connect() {
        if (wsUrl.isEmpty()) {
            Log.w(tag, "Agent chat URL not configured")
            emit(ChatEvent.Error("Agent server URL not configured"))
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
            emit(ChatEvent.Error("Not connected"))
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
            emit(ChatEvent.Error("Failed to send (queue closed)"))
        }
    }

    private fun openSocket() {
        isConnecting = true
        emit(ChatEvent.Connecting)
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
                emit(ChatEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Agent chat error: ${t.message}")
                isConnected = false
                isConnecting = false
                emit(ChatEvent.Error(t.message ?: "Connection failed"))
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
                            ChatMessage(
                                role = mapped,
                                content = content,
                                at = parseInstant(m["at"]),
                                ok = okEvent(m["events"]),
                            )
                        }
                    emit(ChatEvent.History(messages))
                }
                ServerFrame.CHUNK -> {
                    val piece = obj["content"]?.jsonPrimitive?.content ?: return
                    emit(ChatEvent.Chunk(piece))
                }
                ServerFrame.OK -> {
                    val offer = parseOk(obj) ?: return
                    emit(ChatEvent.Ok(offer))
                }
                ServerFrame.DONE -> {
                    emit(ChatEvent.Done)
                }
                ServerFrame.ERROR -> {
                    val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    Log.e(tag, "Server error frame: $message (raw=$text)")
                    emit(ChatEvent.Error(message))
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

    private fun parseOk(obj: JsonObject): OkOffer? {
        val minutes = (obj["minutes"] as? JsonPrimitive)?.intOrNull
        val at = parseInstant(obj["at"])
        if (minutes == null || at == null) {
            Log.w(tag, "Ignoring malformed ok event: $obj")
            return null
        }
        return OkOffer(minutes = minutes, at = at)
    }

    // OffsetDateTime takes both "Z" and "+00:00"; Instant.parse on older
    // runtimes only takes the former.
    private fun parseInstant(value: JsonElement?): Instant? =
        try {
            OffsetDateTime.parse((value as JsonPrimitive).content).toInstant()
        } catch (e: Exception) {
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

        // Every device talks in this one thread, so the coach hears the phone
        // and the browser alike. The server keeps the last 30 days of it.
        const val THREAD_ID = "main"
    }
}
