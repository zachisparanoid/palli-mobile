package com.palli.mobile.sync

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The real websocket against MockWebServer: real sockets, real time, so every wait here is bounded and short. */
class OkHttpSocketFactoryTest {
    private val server = MockWebServer()
    private val heard = LinkedBlockingQueue<String>()      // what the client's listener was told
    private val serverHeard = LinkedBlockingQueue<String>() // what the server's side of the socket was told

    private val listener = object : SocketListener {
        override fun onOpen() { heard += "open" }
        override fun onMessage(text: String) { heard += "message:$text" }
        override fun onClosed(code: Int, reason: String) { heard += "closed:$code:$reason" }
        override fun onFailure(error: Throwable) { heard += "failure" }
    }

    private val serverSide = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverHeard += "closing:$code:$reason"
            webSocket.close(1000, null)
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun next(queue: LinkedBlockingQueue<String> = heard): String? = queue.poll(5, TimeUnit.SECONDS)

    private fun streamUrl(after: Long = 0) = server.url("/api/mobile/stream?after=$after").toString()

    @Test fun `the upgrade carries the bearer token and the cursor`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        server.start()
        val handle = OkHttpSocketFactory(OkHttpClient()).open(streamUrl(after = 7), "pmd_t", listener)
        assertEquals("open", next())
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(upgrade)
        assertEquals("Bearer pmd_t", upgrade!!.getHeader("Authorization"))
        assertEquals("/api/mobile/stream?after=7", upgrade.path)
        handle.close(1000, "done")
    }

    @Test fun `a server close with 4401 reaches the listener as 4401, and is answered with a normal close`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(4401, "revoked") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { serverHeard += "closing:$code:$reason" }
        }))
        server.start()
        OkHttpSocketFactory(OkHttpClient()).open(streamUrl(), "pmd_t", listener)
        assertEquals("open", next())
        assertEquals("closed:4401:revoked", next())
        assertEquals("closing:1000:", next(serverHeard))            // not an echo of 4401
        assertNull(heard.poll(200, TimeUnit.MILLISECONDS))          // reported once, not also as a failure
    }

    @Test fun `closing an open socket sends the close frame with the code and reason`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        server.start()
        val handle = OkHttpSocketFactory(OkHttpClient()).open(streamUrl(), "pmd_t", listener)
        assertEquals("open", next())
        handle.close(1001, "silent")
        assertEquals("closing:1001:silent", next(serverHeard))
    }

    @Test fun `closing before the upgrade cancels at once instead of waiting for a close handshake`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val handle = OkHttpSocketFactory(OkHttpClient()).open(streamUrl(), "pmd_t", listener) // 10 s default read timeout
        handle.close(1000, "done")
        assertEquals("failure", next())                             // within 5 s, so not the 10 s timeout
    }

    @Test fun `a handshake nobody answers fails by the inherited read timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        OkHttpSocketFactory(client).open(streamUrl(), "pmd_t", listener)
        assertEquals("failure", next())
    }

    @Test fun `an open socket may stay quiet longer than the read timeout`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                thread { Thread.sleep(900); webSocket.send("late") }
            }
        }))
        server.start()
        val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        OkHttpSocketFactory(client).open(streamUrl(), "pmd_t", listener)
        assertEquals("open", next())
        assertEquals("message:late", next())                        // three read timeouts of silence, no failure
    }
}
