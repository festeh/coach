package com.example.coach_android.data.websocket

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WebSocketServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: WebSocketService
    private val focusFrames = LinkedBlockingQueue<JsonObject>()

    @Before
    fun connect() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.decodeFromString<JsonObject>(text)
                when (frame["type"]?.jsonPrimitive?.content) {
                    "get_focusing" -> webSocket.send("""{"type":"focusing","focusing":false}""")
                    "focus" -> focusFrames.add(frame)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        service = WebSocketService(server.url("/connect").toString())
        runBlocking { service.requestFocusStatus() }
    }

    @After
    fun disconnect() {
        service.dispose()
        server.shutdown()
    }

    @Test
    fun twentyMinutesSendsTwelveHundredSeconds() {
        runBlocking { service.sendFocusCommand(20) }

        val frame = focusFrames.poll(5, TimeUnit.SECONDS) ?: error("No focus frame received")
        assertEquals("focus", frame["type"]?.jsonPrimitive?.content)
        assertEquals(1200L, frame["duration"]?.jsonPrimitive?.long)
    }

    @Test
    fun defaultDurationLeavesTheServerDefaultIntact() {
        runBlocking { service.sendFocusCommand() }

        val frame = focusFrames.poll(5, TimeUnit.SECONDS) ?: error("No focus frame received")
        assertEquals("focus", frame["type"]?.jsonPrimitive?.content)
        assertFalse(frame.containsKey("duration"))
    }
}
