package com.example.coach_android.data.websocket

import android.util.Log
import com.example.coach_android.util.SafeUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*

class WebSocketService(
    private val url: String,
) {
    private val tag = "WebSocketService"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = WebSocketTransport.client

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isConnected = false
    private var isConnecting = false
    private val reconnect = ReconnectScheduler(scope, BASE_RECONNECT_DELAY_MS)
    private var focusQueryJob: Job? = null

    private var pendingRequest: CompletableDeferred<Map<String, Any?>>? = null

    private val _focusUpdates = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 16)
    val focusUpdates: SharedFlow<Map<String, Any?>> = _focusUpdates.asSharedFlow()

    fun getConnectionStatus(): Map<String, Any?> =
        mapOf(
            "isConnected" to isConnected,
            "isConnecting" to isConnecting,
            "reconnectAttempts" to reconnect.attempts,
            // Redacted here rather than at the Debug screen that renders it, so a
            // future reader of this map cannot reintroduce the leak.
            "websocketUrl" to SafeUrl.redacted(url),
        )

    fun initialize() {
        if (url.isEmpty()) {
            Log.w(tag, "WebSocket URL not configured")
            return
        }
        Log.i(tag, "Initializing WebSocket service")
        connect()
    }

    private fun connect() {
        if (isConnecting || isConnected) {
            Log.d(tag, "Already connected or connecting")
            return
        }

        isConnecting = true
        Log.i(tag, "Connecting to WebSocket: ${SafeUrl.redacted(url)}")

        val request = Request.Builder().url(url).build()
        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        Log.i(tag, "WebSocket connected successfully")
                        isConnected = true
                        isConnecting = false
                        reconnect.reset()
                        startPeriodicFocusQuery()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        handleMessage(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        Log.e(tag, "WebSocket error: ${t.message}")
                        cleanup()
                        scheduleReconnect()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(tag, "WebSocket closing: $code $reason")
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        Log.i(tag, "WebSocket closed: $code $reason")
                        cleanup()
                        scheduleReconnect()
                    }
                },
            )
    }

    private fun handleMessage(text: String) {
        try {
            Log.d(tag, "Received WebSocket message: $text")
            val data = json.decodeFromString<JsonObject>(text)
            val dataMap = jsonObjectToMap(data)
            val messageType = (data["type"]?.jsonPrimitive?.content)

            // Complete a pending request, then still publish the frame below.
            // Periodic focus queries rely on that publication to refresh
            // countdowns in MonitorLogic and the persistent notification.
            val pending = pendingRequest
            if (pending != null && messageType == "focusing") {
                pendingRequest = null
                pending.complete(dataMap)
            }

            // Emit focus updates. The server stamps every focus frame with this
            // type, both for broadcasts and for get_focusing replies.
            if (messageType == "focusing") {
                scope.launch { _focusUpdates.emit(dataMap) }
                Log.d(tag, "Focus update emitted: focusing=${data["focusing"]}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing WebSocket message: ${e.message}")
        }
    }

    private fun cleanup() {
        isConnected = false
        isConnecting = false
        focusQueryJob?.cancel()
        focusQueryJob = null

        val pending = pendingRequest
        if (pending != null && !pending.isCompleted) {
            pending.completeExceptionally(Exception("WebSocket connection lost"))
            pendingRequest = null
        }
    }

    // Retried for as long as the service lives. Giving up after a fixed number of
    // attempts left the app silently offline — a server restart during a deploy
    // cost the socket until someone hit Refresh — and the phone can't tell a
    // finished outage from an ongoing one without trying.
    private fun scheduleReconnect() {
        val delayMs = reconnect.schedule { connect() } ?: return
        Log.i(tag, "Scheduling reconnection attempt ${reconnect.attempts} in ${delayMs / 1000}s")
    }

    private fun startPeriodicFocusQuery() {
        focusQueryJob?.cancel()
        focusQueryJob =
            scope.launch {
                while (isActive) {
                    delay(FOCUS_QUERY_INTERVAL_MS)
                    if (isConnected) {
                        try {
                            Log.d(tag, "Sending periodic focus query")
                            requestFocusStatus()
                        } catch (e: Exception) {
                            Log.w(tag, "Periodic focus query failed: ${e.message}")
                        }
                    }
                }
            }
    }

    // Values are strings or numbers; numbers stay numbers on the wire, because
    // the server decodes fields like `duration` into integers.
    private fun sendMessage(message: Map<String, Any>) {
        val ws = webSocket
        if (!isConnected || ws == null) {
            throw Exception("WebSocket not connected")
        }
        val jsonStr =
            json.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    message.forEach { (k, v) ->
                        put(
                            k,
                            when (v) {
                                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                            },
                        )
                    }
                },
            )
        if (!ws.send(jsonStr)) {
            throw IllegalStateException("WebSocket rejected the message")
        }
        Log.d(tag, "Sent WebSocket message: type=${message["type"]}")
    }

    // Report a blocked app open as a temptation. Best-effort: if the socket is
    // down the block still happened, we just don't record it.
    fun sendTemptation(packageName: String) {
        try {
            sendMessage(
                mapOf(
                    "type" to "temptation",
                    "source" to "android",
                    "target" to packageName,
                ),
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to send temptation: ${e.message}")
        }
    }

    // The user's OK on the coach's reply (source "chat"), or on the local offer
    // made while the coach was unreachable (source "fallback"). The server
    // releases the lock and journals the reason with the coach's answer.
    // Throws when the socket is down, so the caller can keep the OK on screen.
    fun sendRelease(
        durationSeconds: Int,
        reason: String,
        agentMessage: String,
        source: String,
    ) {
        sendMessage(
            mapOf(
                "type" to "release",
                "duration" to durationSeconds,
                "message" to reason,
                "agent_message" to agentMessage,
                "source" to source,
            ),
        )
    }

    suspend fun sendFocusCommand(durationMinutes: Int = 0) {
        Log.i(tag, "Sending focus command (duration=${durationMinutes}m)")
        if (!isConnected) throw Exception("WebSocket not connected")
        val ws = webSocket ?: throw Exception("WebSocket not available")
        val jsonStr =
            json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("focus"))
                    if (durationMinutes > 0) {
                        // The picker and preferences use minutes; the server's
                        // focus protocol uses seconds. Convert only at the boundary.
                        put("duration", kotlinx.serialization.json.JsonPrimitive(durationMinutes.toLong() * 60))
                    }
                },
            )
        if (!ws.send(jsonStr)) {
            throw IllegalStateException("WebSocket rejected the message")
        }
        Log.i(tag, "Focus command sent: $jsonStr")
    }

    suspend fun requestFocusStatus(): Map<String, Any?> {
        Log.i(tag, "Requesting focus status - connected: $isConnected")

        if (!isConnected) {
            Log.i(tag, "WebSocket not connected, attempting to connect first")
            connect()

            // Wait for connection
            val maxWait = 8000L
            val interval = 200L
            var waited = 0L
            while (!isConnected && waited < maxWait) {
                delay(interval)
                waited += interval
            }
            if (!isConnected) {
                throw Exception(
                    "Could not establish WebSocket connection after ${waited / 1000}s. " +
                        "URL: ${SafeUrl.redacted(url)}",
                )
            }
            Log.i(tag, "WebSocket connection established for focus status request")

            if (focusQueryJob == null || focusQueryJob?.isActive != true) {
                startPeriodicFocusQuery()
            }
        }

        val deferred = CompletableDeferred<Map<String, Any?>>()
        pendingRequest = deferred

        try {
            sendMessage(mapOf("type" to "get_focusing"))
            val response = withTimeout(10_000) { deferred.await() }
            Log.i(tag, "Focus status response received")
            return response
        } catch (e: Exception) {
            pendingRequest = null
            Log.e(tag, "Focus status request failed: ${e.message}")
            throw e
        }
    }

    fun dispose() {
        Log.i(tag, "Disposing WebSocket service")
        reconnect.reset()
        focusQueryJob?.cancel()
        scope.cancel()
        webSocket?.close(1000, "Disposing")
        webSocket = null
        cleanup()
    }

    companion object {
        private const val BASE_RECONNECT_DELAY_MS = 2000L
        private const val FOCUS_QUERY_INTERVAL_MS = 60_000L

        private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> =
            obj.mapValues { (_, value) ->
                when {
                    value is kotlinx.serialization.json.JsonNull -> null
                    value is kotlinx.serialization.json.JsonPrimitive && value.isString -> value.content
                    value is kotlinx.serialization.json.JsonPrimitive -> {
                        value.content.toBooleanStrictOrNull()
                            ?: value.content.toIntOrNull()
                            ?: value.content.toLongOrNull()
                            ?: value.content.toDoubleOrNull()
                            ?: value.content
                    }
                    else -> value.toString()
                }
            }
    }
}
