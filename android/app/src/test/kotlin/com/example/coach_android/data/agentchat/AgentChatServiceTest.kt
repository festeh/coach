package com.example.coach_android.data.agentchat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
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

class AgentChatServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AgentChatService

    @Before
    fun start() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                repeat(CHUNKS) { webSocket.send("""{"type":"chunk","content":"$it,"}""") }
                webSocket.send("""{"type":"done"}""")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        service = AgentChatService(server.url("/").toString(), "thread")
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

    companion object {
        private const val CHUNKS = 2000
    }
}
