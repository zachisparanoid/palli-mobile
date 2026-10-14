package com.palli.mobile.sync

import android.util.Log
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.Frames
import com.palli.mobile.data.remote.ServerFrame
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnState { IDLE, CONNECTING, CONNECTED, WAITING }

/**
 * Who hears about events that were new to this phone (the notifier). Called inline on the connection loop, whose silence
 * watchdog wraps only `receive()`: an implementation must do fast, local work only and never wait on the network.
 */
interface EventSink {
    suspend fun onNewEvents(events: List<EventDto>)
}

/**
 * The one connection to PaLLi. Replays from the newest event this phone holds, then streams. Every event is
 * committed (insertNew) BEFORE it is acked, so a crash between the two is a harmless replay. Reconnects with
 * backoff; a network change (kick) skips the wait; a minute of silence is a dead socket; 4401 means unpaired.
 *
 * Nothing that goes wrong inside one attempt (a database error, a throwing notifier, a bad session lookup) ends the
 * loop: the attempt is abandoned, its socket closed, and the loop backs off and tries again. Only stop(), the scope
 * ending, no session, or 4401 end it.
 *
 * The loop owns `state` and `herTyping` while it is the current one (`job === self`). stop() cancels it, closes the
 * socket, and settles both flows itself (IDLE, not typing) in the same locked block that clears `job`; the loop also
 * settles on IDLE in its own `finally`. A loop that has been stopped (or replaced by a newer start()) writes neither,
 * so CONNECTED can never appear after a stop, not even for the moment before the cancelled loop unwinds.
 */
class StreamSession(
    private val scope: CoroutineScope,
    private val currentSession: suspend () -> Session?,
    private val sockets: SocketFactory,
    private val insertNew: suspend (List<EventDto>) -> List<EventDto>,
    private val maxId: suspend () -> Long,
    private val sink: EventSink,
    private val unpairer: Unpairer,
    private val backoff: Backoff = Backoff(),
) {
    private val _state = MutableStateFlow(ConnState.IDLE)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _herTyping = MutableStateFlow(false)
    val herTyping: StateFlow<Boolean> = _herTyping.asStateFlow()

    private val lock = Any()
    private var job: Job? = null // guarded by lock; the loop that currently owns the state flows
    @Volatile private var handle: SocketHandle? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Incoming {
        data object Open : Incoming
        data class Message(val text: String) : Incoming
        data class Closed(val code: Int) : Incoming
        data class Failed(val error: Throwable) : Incoming
    }

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            kicks.tryReceive() // a kick from before the last stop() is stale
            // Lazy, so `job` names the loop before its first line runs and the loop can tell whether it still owns the state.
            val loop = scope.launch(start = CoroutineStart.LAZY) { loop(coroutineContext.job) }
            job = loop
            loop.start()
        }
    }

    fun stop() {
        val running = synchronized(lock) {
            // Settle the flows here rather than waiting for the cancelled loop's `finally`: a stopped session must not
            // read CONNECTED for the moment in between. Safe, because every other writer is guarded by `job === self`
            // and `job` is cleared in this same block, so the loop being stopped can no longer write over this.
            _state.value = ConnState.IDLE
            _herTyping.value = false
            job.also { job = null }
        }
        running?.cancel()
        val socket = handle
        handle = null
        socket?.close(1000, "bye")
    }

    /** The network changed: stop waiting and try now. */
    fun kick() { kicks.trySend(Unit) }

    fun sendTyping() { handle?.send(Frames.TYPING) }

    private fun setState(self: Job, value: ConnState) = synchronized(lock) {
        if (job === self) _state.value = value
    }

    private fun setTyping(self: Job, on: Boolean) = synchronized(lock) {
        if (job === self) _herTyping.value = on
    }

    private suspend fun loop(self: Job) {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var revoked = false
                try {
                    val session = currentSession() ?: return
                    setState(self, ConnState.CONNECTING)
                    revoked = connectOnce(session, self) == CLOSE_REVOKED
                } catch (e: CancellationException) {
                    // Cancelled for real (stop(), the scope ending): leave. Anything else is a failed attempt.
                    if (!currentCoroutineContext().isActive) throw e
                    Log.w(TAG, "attempt ended by a stray cancellation: ${e.javaClass.simpleName}")
                } catch (e: Exception) {
                    // The class name only: an exception's message can carry the URL or more.
                    Log.w(TAG, "attempt failed: ${e.javaClass.simpleName}")
                }
                if (revoked) {
                    setState(self, ConnState.IDLE)
                    logged("unpairing") { unpairer.unpaired("This phone was unpaired.") }
                    return
                }
                setState(self, ConnState.WAITING)
                withTimeoutOrNull(backoff.nextDelayMs()) { kicks.receive() }
            }
        } finally {
            synchronized(lock) {
                // A loop that stop() or a newer start() has already replaced must not write over its successor.
                if (job === self || job == null) {
                    _herTyping.value = false
                    _state.value = ConnState.IDLE
                }
            }
        }
    }

    /** One socket's life. Returns its close code, or null for a failure or a silent socket. */
    private suspend fun connectOnce(session: Session, self: Job): Int? {
        val incoming = Channel<Incoming>(Channel.UNLIMITED)
        val url = "${session.baseUrl.trimEnd('/')}/api/mobile/stream?after=${maxId()}"
        val socket = sockets.open(url, session.token, object : SocketListener {
            override fun onOpen() { incoming.trySend(Incoming.Open) }
            override fun onMessage(text: String) { incoming.trySend(Incoming.Message(text)) }
            override fun onClosed(code: Int, reason: String) { incoming.trySend(Incoming.Closed(code)) }
            override fun onFailure(error: Throwable) { incoming.trySend(Incoming.Failed(error)) }
        })
        handle = socket
        var closedByUs = false
        try {
            while (true) {
                val next = withTimeoutOrNull(SILENCE_MS) { incoming.receive() }
                if (next == null) {
                    closedByUs = true
                    socket.close(1001, "silent")
                    return null
                }
                when (next) {
                    Incoming.Open -> setState(self, ConnState.CONNECTED)
                    // A server that accepts and then fails must still back off: only a frame we could use proves it is well.
                    is Incoming.Message -> if (onFrame(next.text, socket, self)) backoff.reset()
                    is Incoming.Closed -> return next.code
                    is Incoming.Failed -> return null
                }
            }
        } finally {
            // However the attempt ends (an exception, a cancellation, a close), it leaves no live socket behind.
            if (!closedByUs) runCatching { socket.close(1000, "done") }
            if (handle === socket) handle = null
            setTyping(self, false)
        }
    }

    /** True for a frame this app understood and handled (an event, a ping, typing). */
    private suspend fun onFrame(text: String, socket: SocketHandle, self: Job): Boolean =
        when (val frame = Frames.decode(text)) {
            is ServerFrame.Event -> {
                val fresh = insertNew(listOf(frame.event))      // 1. commit
                socket.send(Frames.ack(frame.event.id))         // 2. then ack
                // 3. tell only what was new. The event is stored and acked by now, so a notifier that fails must not take the stream down.
                if (fresh.isNotEmpty()) logged("notifying") { sink.onNewEvents(fresh) }
                true
            }
            is ServerFrame.Typing -> { setTyping(self, frame.on); true }
            ServerFrame.Ping -> { socket.send(Frames.PONG); true }
            else -> false
        }

    /** Runs `block`; logs (class name only) and swallows anything but a real cancellation. */
    private suspend inline fun logged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            Log.w(TAG, "$what ended by a stray cancellation: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val SILENCE_MS = 60_000L
        const val CLOSE_REVOKED = 4401
        private const val TAG = "StreamSession"
    }
}
