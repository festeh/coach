package com.example.coach_android.data.agentchat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AgentChatServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AgentChatService

    @Before
    fun start() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(HISTORY)
                repeat(CHUNKS) { webSocket.send("""{"type":"chunk","content":"$it,"}""") }
                webSocket.send("""{"type":"done"}""")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        service = AgentChatService(server.url("/").toString())
    }

    @After
    fun stop() {
        service.dispose()
        server.shutdown()
    }

    // A streamed reply arrives as many small chunks back to back; the chat
    // must see them in the order sent, or the reply reads as garbled text.
    @Test
    fun chunksArriveInTheOrderTheServerSentThem() {
        val text =
            runBlocking {
                val chunks =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        service.events
                            .takeWhile { it !is ChatEvent.Done }
                            .filterIsInstance<ChatEvent.Chunk>()
                            .toList()
                    }
                service.connect()
                withTimeout(10_000) { chunks.await() }.joinToString("") { it.text }
            }

        assertEquals((0 until CHUNKS).joinToString("") { "$it," }, text)
    }

    // Every device talks in one thread; history says when each line was said.
    @Test
    fun historyFromTheSharedThreadCarriesWhenEachMessageWasSaid() {
        val history =
            runBlocking {
                val first =
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        service.events.filterIsInstance<ChatEvent.History>().first()
                    }
                service.connect()
                withTimeout(10_000) { first.await() }
            }

        assertEquals("/api/coach/ws/main", server.takeRequest().path)
        assertEquals(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "one video", at = Instant.parse("2026-10-15T12:00:00.000Z")),
                ChatMessage(ChatMessage.Role.ASSISTANT, "Go ahead.", at = Instant.parse("2026-10-15T12:00:03.500Z")),
                ChatMessage(ChatMessage.Role.ASSISTANT, "No stamp", at = null),
            ),
            history.messages,
        )
    }

    companion object {
        private const val CHUNKS = 2000
        private const val HISTORY =
            """{"type":"history","messages":[""" +
                """{"role":"human","content":"one video","at":"2026-10-15T12:00:00.000Z"},""" +
                """{"role":"tool","content":"{}","at":"2026-10-15T12:00:03.500Z"},""" +
                """{"role":"ai","content":"Go ahead.","at":"2026-10-15T12:00:03.500Z"},""" +
                """{"role":"ai","content":"No stamp"}]}"""
    }
}
