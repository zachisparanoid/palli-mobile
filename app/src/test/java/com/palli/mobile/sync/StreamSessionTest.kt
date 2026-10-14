package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionTest {
    private val log = mutableListOf<String>()

    private inner class FakeSocket(val url: String, val token: String, val listener: SocketListener) : SocketHandle {
        var closedWith: Int? = null
        override fun send(text: String): Boolean { log += "send:$text"; return true }
        override fun close(code: Int, reason: String) { closedWith = code }
    }

    private inner class FakeSockets : SocketFactory {
        val opened = mutableListOf<FakeSocket>()
        override fun open(url: String, token: String, listener: SocketListener): SocketHandle =
            FakeSocket(url, token, listener).also { opened += it }
    }

    private val stored = mutableSetOf<Long>()
    private val notified = mutableListOf<Long>()
    private val unpairedWith = mutableListOf<String?>()
    private var session: Session? = Session("http://palli:8000", "pmd_t", 1, "Phone")

    private fun TestScope.newSession(sockets: FakeSockets) = StreamSession(
        scope = backgroundScope,
        currentSession = { session },
        sockets = sockets,
        insertNew = { events ->
            events.forEach { log += "insert:${it.id}" }
            events.filter { stored.add(it.id) }
        },
        maxId = { stored.maxOrNull() ?: 0L },
        sink = object : EventSink { override suspend fun onNewEvents(events: List<EventDto>) { notified += events.map { it.id } } },
        unpairer = object : Unpairer { override suspend fun unpaired(reason: String?) { unpairedWith += reason } },
        backoff = Backoff(random = { 1.0 }),
    )

    private fun event(id: Long) =
        """{"type":"event","event":{"id":$id,"kind":"${EventKinds.MESSAGE}","at":1.0,"text":"t$id"}}"""

    @Test fun `it connects from its cursor with its token and is connected on open`() = runTest {
        stored += 41L
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        val socket = sockets.opened.single()
        assertEquals("http://palli:8000/api/mobile/stream?after=41", socket.url)
        assertEquals("pmd_t", socket.token)
        socket.listener.onOpen(); runCurrent()
        assertEquals(ConnState.CONNECTED, stream.state.value)
    }

    @Test fun `an event is committed before it is acked, and a replay notifies nothing`() = runTest {
        val sockets = FakeSockets()
        newSession(sockets).start(); runCurrent()
        val socket = sockets.opened.single()
        socket.listener.onOpen()
        socket.listener.onMessage(event(5)); runCurrent()
        socket.listener.onMessage(event(5)); runCurrent()
        assertEquals(listOf("insert:5", """send:{"type":"ack","through":5}""",
                            "insert:5", """send:{"type":"ack","through":5}"""), log)
        assertEquals(listOf(5L), notified)
    }

    @Test fun `typing frames show her typing and a ping is answered`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.listener.onMessage("""{"type":"typing","on":true}"""); runCurrent()
        assertTrue(stream.herTyping.value)
        socket.listener.onMessage("""{"type":"typing","on":false}""")
        socket.listener.onMessage("""{"type":"ping"}"""); runCurrent()
        assertEquals(false, stream.herTyping.value)
        assertEquals(listOf("""send:{"type":"pong"}"""), log)
    }

    @Test fun `closed with 4401 means unpaired, and it stops`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened.single().listener.onClosed(4401, "revoked"); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf<String?>("This phone was unpaired."), unpairedWith)
        assertEquals(1, sockets.opened.size)
        assertEquals(ConnState.IDLE, stream.state.value)
    }

    @Test fun `a failure waits out the backoff, and a network change cuts the wait short`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onFailure(RuntimeException("down")); runCurrent()
        assertEquals(ConnState.WAITING, stream.state.value)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, sockets.opened.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, sockets.opened.size)                       // 1 s later

        sockets.opened[1].listener.onFailure(RuntimeException("down again")); runCurrent()
        stream.kick(); runCurrent()
        assertEquals(3, sockets.opened.size)                       // no 2 s wait
    }

    @Test fun `a minute of silence is a dead socket`() = runTest {
        val sockets = FakeSockets()
        newSession(sockets).start(); runCurrent()
        sockets.opened[0].listener.onOpen()
        advanceTimeBy(StreamSession.SILENCE_MS + 1); runCurrent()
        assertEquals(1001, sockets.opened[0].closedWith)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test fun `with no session there is nothing to connect to`() = runTest {
        session = null
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        assertEquals(0, sockets.opened.size)
        assertEquals(ConnState.IDLE, stream.state.value)
    }
}
