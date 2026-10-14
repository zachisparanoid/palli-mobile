package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.Frames
import com.palli.mobile.data.remote.ServerFrame
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnState { IDLE, CONNECTING, CONNECTED, WAITING }

/** Who hears about events that were new to this phone (the notifier). */
interface EventSink {
    suspend fun onNewEvents(events: List<EventDto>)
}

/**
 * The one connection to PaLLi. Replays from the newest event this phone holds, then streams. Every event is
 * committed (insertNew) BEFORE it is acked, so a crash between the two is a harmless replay. Reconnects with
 * backoff; a network change (kick) skips the wait; a minute of silence is a dead socket; 4401 means unpaired.
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

    private var job: Job? = null
    @Volatile private var handle: SocketHandle? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Incoming {
        data object Open : Incoming
        data class Message(val text: String) : Incoming
        data class Closed(val code: Int) : Incoming
        data class Failed(val error: Throwable) : Incoming
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel(); job = null
        handle?.close(1000, "bye"); handle = null
        _herTyping.value = false
        _state.value = ConnState.IDLE
    }

    /** The network changed: stop waiting and try now. */
    fun kick() { kicks.trySend(Unit) }

    fun sendTyping() { handle?.send(Frames.TYPING) }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val session = currentSession() ?: run { _state.value = ConnState.IDLE; return }
            _state.value = ConnState.CONNECTING
            val closedWith = connectOnce(session)
            handle = null
            _herTyping.value = false
            if (closedWith == CLOSE_REVOKED) {
                _state.value = ConnState.IDLE
                unpairer.unpaired("This phone was unpaired.")
                return
            }
            _state.value = ConnState.WAITING
            withTimeoutOrNull(backoff.nextDelayMs()) { kicks.receive() }
        }
    }

    /** One socket's life. Returns its close code, or null for a failure or a silent socket. */
    private suspend fun connectOnce(session: Session): Int? {
        val incoming = Channel<Incoming>(Channel.UNLIMITED)
        val url = "${session.baseUrl.trimEnd('/')}/api/mobile/stream?after=${maxId()}"
        val socket = sockets.open(url, session.token, object : SocketListener {
            override fun onOpen() { incoming.trySend(Incoming.Open) }
            override fun onMessage(text: String) { incoming.trySend(Incoming.Message(text)) }
            override fun onClosed(code: Int, reason: String) { incoming.trySend(Incoming.Closed(code)) }
            override fun onFailure(error: Throwable) { incoming.trySend(Incoming.Failed(error)) }
        })
        handle = socket
        while (true) {
            val next = withTimeoutOrNull(SILENCE_MS) { incoming.receive() }
            if (next == null) { socket.close(1001, "silent"); return null }
            when (next) {
                Incoming.Open -> { _state.value = ConnState.CONNECTED; backoff.reset() }
                is Incoming.Message -> onFrame(next.text, socket)
                is Incoming.Closed -> return next.code
                is Incoming.Failed -> return null
            }
        }
    }

    private suspend fun onFrame(text: String, socket: SocketHandle) {
        when (val frame = Frames.decode(text)) {
            is ServerFrame.Event -> {
                val fresh = insertNew(listOf(frame.event))      // 1. commit
                socket.send(Frames.ack(frame.event.id))         // 2. then ack
                if (fresh.isNotEmpty()) sink.onNewEvents(fresh) // 3. tell only what was new
            }
            is ServerFrame.Typing -> _herTyping.value = frame.on
            ServerFrame.Ping -> socket.send(Frames.PONG)
            else -> Unit
        }
    }

    companion object {
        const val SILENCE_MS = 60_000L
        const val CLOSE_REVOKED = 4401
    }
}
