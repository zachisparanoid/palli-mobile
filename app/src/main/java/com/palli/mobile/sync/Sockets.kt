package com.palli.mobile.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

interface SocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

interface SocketHandle {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

interface SocketFactory {
    fun open(url: String, token: String, listener: SocketListener): SocketHandle
}

/**
 * OkHttp's websocket. The client's own timeouts are left alone: OkHttp clears the socket's read timeout the moment the
 * upgrade succeeds (RealConnection.newWebSocketStreams), so the inherited read timeout bounds only the wait for the
 * upgrade response and a handshake nobody answers fails by itself. After that a quiet socket may stay quiet as long
 * as it likes: liveness is StreamSession's "a minute of silence is a dead socket".
 */
class OkHttpSocketFactory @Inject constructor(private val client: OkHttpClient) : SocketFactory {

    override fun open(url: String, token: String, listener: SocketListener): SocketHandle {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val opened = AtomicBoolean(false)
        val reported = AtomicBoolean(false)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                listener.onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Answer with a normal close. Echoing the peer's code would throw for a reserved one (1005 is what a
                // close frame with no status arrives as) and the peer's code is not ours to claim.
                webSocket.close(1000, null)
                if (reported.compareAndSet(false, true)) listener.onClosed(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (reported.compareAndSet(false, true)) listener.onClosed(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (reported.compareAndSet(false, true)) listener.onFailure(t)
            }
        })
        return object : SocketHandle {
            override fun send(text: String) = socket.send(text)

            /** A close frame can only be sent once the upgrade is done; before that, the only way out is to cancel. */
            override fun close(code: Int, reason: String) {
                if (opened.get()) socket.close(code, reason) else socket.cancel()
            }
        }
    }
}
