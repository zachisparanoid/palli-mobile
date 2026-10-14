package com.palli.mobile.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
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

/** OkHttp's websocket. No read timeout: liveness is StreamSession's "a minute of silence is a dead socket". */
class OkHttpSocketFactory @Inject constructor(client: OkHttpClient) : SocketFactory {
    private val wsClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    override fun open(url: String, token: String, listener: SocketListener): SocketHandle {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val reported = AtomicBoolean(false)
        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
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
            override fun close(code: Int, reason: String) { socket.close(code, reason) }
        }
    }
}
