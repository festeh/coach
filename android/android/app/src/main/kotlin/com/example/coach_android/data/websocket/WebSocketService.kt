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
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class WebSocketService(
    private val url: String,
) {
    private val tag = "WebSocketService"

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES) // no read timeout for WebSocket
            .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isConnected = false
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var focusQueryJob: Job? = null

    private var pendingRequest: CompletableDeferred<Map<String, Any?>>? = null

    private val _focusUpdates = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 16)
    val focusUpdates: SharedFlow<Map<String, Any?>> = _focusUpdates.asSharedFlow()

    fun getConnectionStatus(): Map<String, Any?> =
        mapOf(
            "isConnected" to isConnected,
            "isConnecting" to isConnecting,
            "reconnectAttempts" to reconnectAttempts,
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
                        reconnectAttempts = 0
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
        if (reconnectJob?.isActive == true) return

        reconnectAttempts++

        val delayMs =
            (
                BASE_RECONNECT_DELAY_MS *
                    2.0.pow(min(reconnectAttempts - 1, 6).toDouble())
            ).toLong() +
                (0..999).random()

        Log.i(tag, "Scheduling reconnection attempt $reconnectAttempts in ${delayMs / 1000}s")

        reconnectJob =
            scope.launch {
                delay(delayMs)
                connect()
            }
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

    private fun sendMessage(message: Map<String, String>) {
        val ws = webSocket
        if (!isConnected || ws == null) {
            throw Exception("WebSocket not connected")
        }
        val jsonStr =
            json.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    message.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
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

    // One message whatever the phase: the server decides if the tap grants the
    // day's first override, arms a countdown, or redeems a ripe request. The
    // reason is required to grant or arm and ignored on a redeem — callers pass
    // an empty string for a redeem tap.
    fun sendOverride(reason: String) {
        sendMessage(
            mapOf(
                "type" to "override",
                "message" to reason.trim(),
            ),
        )
    }

    // The peek: 30 seconds, no reason asked, never refused. The nag of tapping
    // again every half-minute is the only price.
    fun sendShortUnblock() {
        sendMessage(mapOf("type" to "short_unblock"))
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
                        put("duration", kotlinx.serialization.json.JsonPrimitive(durationMinutes))
                    }
                },
            )
        ws.send(jsonStr)
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
        reconnectJob?.cancel()
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
