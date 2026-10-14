package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CancellationException
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

    // What the fakes throw next (null: nothing). The insert and the lookup fail once; the sink and the unpairer keep failing.
    private var insertFailure: Throwable? = null
    private var lookupFailure: Throwable? = null
    private var sinkFailure: Throwable? = null
    private var unpairFailure: Throwable? = null

    private fun TestScope.newSession(sockets: FakeSockets) = StreamSession(
        scope = backgroundScope,
        currentSession = { lookupFailure?.also { lookupFailure = null }?.let { throw it }; session },
        sockets = sockets,
        insertNew = { events ->
            insertFailure?.also { insertFailure = null }?.let { throw it }
            events.forEach { log += "insert:${it.id}" }
            events.filter { stored.add(it.id) }
        },
        maxId = { stored.maxOrNull() ?: 0L },
        sink = object : EventSink {
            override suspend fun onNewEvents(events: List<EventDto>) {
                notified += events.map { it.id }
                sinkFailure?.let { throw it }
            }
        },
        unpairer = object : Unpairer {
            override suspend fun unpaired(reason: String?) {
                unpairedWith += reason
                unpairFailure?.let { throw it }
            }
        },
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

    // --- the failure boundary: nothing inside an attempt ends the loop ---

    @Test fun `an insert that throws sends no ack, closes its socket, and the replay is acked`() = runTest {
        insertFailure = IllegalStateException("disk full")
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onOpen()
        sockets.opened[0].listener.onMessage(event(5)); runCurrent()
        assertEquals(emptyList<String>(), log)                      // no ack for what was not committed
        assertEquals(1000, sockets.opened[0].closedWith)            // the aborted attempt left no live socket
        assertEquals(ConnState.WAITING, stream.state.value)

        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, sockets.opened.size)
        assertTrue(sockets.opened[1].url.endsWith("?after=0"))      // nothing was stored, so it asks again from the same cursor
        sockets.opened[1].listener.onOpen()
        sockets.opened[1].listener.onMessage(event(5)); runCurrent()
        assertEquals(listOf("insert:5", """send:{"type":"ack","through":5}"""), log)
        assertEquals(listOf(5L), notified)
        assertEquals(ConnState.CONNECTED, stream.state.value)
    }

    @Test fun `a stray cancellation inside an attempt is a failed attempt, not the end of the loop`() = runTest {
        insertFailure = CancellationException("stray")
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onMessage(event(5)); runCurrent()
        assertEquals(ConnState.WAITING, stream.state.value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test fun `a session lookup that throws is a failed attempt too`() = runTest {
        lookupFailure = IllegalStateException("keystore")
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        assertEquals(0, sockets.opened.size)
        assertEquals(ConnState.WAITING, stream.state.value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, sockets.opened.size)
    }

    @Test fun `a sink that throws still lets the ack go out, and the next event is still handled`() = runTest {
        sinkFailure = IllegalStateException("notification channel gone")
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.listener.onOpen()
        socket.listener.onMessage(event(1))
        socket.listener.onMessage(event(2)); runCurrent()
        assertEquals(listOf("insert:1", """send:{"type":"ack","through":1}""",
                            "insert:2", """send:{"type":"ack","through":2}"""), log)
        assertEquals(listOf(1L, 2L), notified)
        assertEquals(1, sockets.opened.size)                        // the stream carried on
        assertEquals(ConnState.CONNECTED, stream.state.value)
    }

    @Test fun `an unpairer that throws still leaves the loop idle and stopped`() = runTest {
        unpairFailure = IllegalStateException("database locked")
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened.single().listener.onClosed(4401, "revoked"); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf<String?>("This phone was unpaired."), unpairedWith)
        assertEquals(1, sockets.opened.size)
        assertEquals(ConnState.IDLE, stream.state.value)
    }

    // --- reconnecting, stopping, backoff ---

    @Test fun `a close that is not 4401 reconnects after the backoff`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onOpen()
        sockets.opened[0].listener.onClosed(1001, "going away"); runCurrent()
        assertEquals(ConnState.WAITING, stream.state.value)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, sockets.opened.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, sockets.opened.size)
        assertEquals(emptyList<String?>(), unpairedWith)
    }

    @Test fun `stop closes the socket with 1000 and never reconnects`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.listener.onOpen(); runCurrent()
        assertEquals(ConnState.CONNECTED, stream.state.value)

        stream.stop(); runCurrent()
        assertEquals(1000, socket.closedWith)
        assertEquals(ConnState.IDLE, stream.state.value)
        socket.listener.onOpen(); runCurrent()                      // a late callback from the closing socket
        assertEquals(ConnState.IDLE, stream.state.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, sockets.opened.size)
        assertEquals(ConnState.IDLE, stream.state.value)
    }

    @Test fun `stop during a backoff wait never reconnects, and a start after it waits the full backoff`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onFailure(RuntimeException("down")); runCurrent()
        assertEquals(ConnState.WAITING, stream.state.value)
        stream.stop(); runCurrent()
        assertEquals(ConnState.IDLE, stream.state.value)
        stream.kick()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, sockets.opened.size)

        stream.start(); runCurrent()                                // the kick from before the stop is stale
        assertEquals(2, sockets.opened.size)
        sockets.opened[1].listener.onOpen(); runCurrent()
        assertEquals(ConnState.CONNECTED, stream.state.value)
        sockets.opened[1].listener.onFailure(RuntimeException("down")); runCurrent()
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, sockets.opened.size)                        // the second wait is 2 s, not skipped
        advanceTimeBy(2); runCurrent()
        assertEquals(3, sockets.opened.size)
    }

    @Test fun `stop then start at once leaves one live loop that owns the state`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onOpen(); runCurrent()
        stream.stop()
        stream.start(); runCurrent()
        assertEquals(1000, sockets.opened[0].closedWith)
        assertEquals(2, sockets.opened.size)
        assertEquals(ConnState.CONNECTING, stream.state.value)     // the old loop winding down did not write IDLE over it
        sockets.opened[1].listener.onOpen(); runCurrent()
        assertEquals(ConnState.CONNECTED, stream.state.value)
    }

    @Test fun `a socket that opens and drops before any frame keeps the backoff growing, and one that heard a frame resets it`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        sockets.opened[0].listener.onFailure(RuntimeException("refused")); runCurrent()
        advanceTimeBy(1_001); runCurrent()                          // wait 1: 1 s
        assertEquals(2, sockets.opened.size)

        sockets.opened[1].listener.onOpen()                         // accepted, then dropped with nothing said
        sockets.opened[1].listener.onClosed(1011, "internal error"); runCurrent()
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, sockets.opened.size)                        // wait 2: grown to 2 s, not reset by the open
        advanceTimeBy(2); runCurrent()
        assertEquals(3, sockets.opened.size)

        sockets.opened[2].listener.onOpen()
        sockets.opened[2].listener.onMessage("""{"type":"ping"}""")  // a frame we understood: she is well
        sockets.opened[2].listener.onClosed(1006, "gone"); runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(3, sockets.opened.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(4, sockets.opened.size)                        // wait 3: back to 1 s
    }

    @Test fun `a frame that fails to commit does not reset the backoff`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.start(); runCurrent()
        insertFailure = IllegalStateException("disk full")
        sockets.opened[0].listener.onMessage(event(1)); runCurrent()   // wait 1: 1 s
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, sockets.opened.size)
        insertFailure = IllegalStateException("disk full")
        sockets.opened[1].listener.onMessage(event(1)); runCurrent()   // wait 2: 2 s
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, sockets.opened.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(3, sockets.opened.size)
    }

    @Test fun `sendTyping sends exactly the typing frame, and nothing without a socket`() = runTest {
        val sockets = FakeSockets()
        val stream = newSession(sockets)
        stream.sendTyping()
        assertEquals(emptyList<String>(), log)
        stream.start(); runCurrent()
        sockets.opened.single().listener.onOpen(); runCurrent()
        stream.sendTyping()
        assertEquals(listOf("""send:{"type":"typing"}"""), log)
        stream.stop(); runCurrent()
        stream.sendTyping()
        assertEquals(1, log.size)
    }
}
