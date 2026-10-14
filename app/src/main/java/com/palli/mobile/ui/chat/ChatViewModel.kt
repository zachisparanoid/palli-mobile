package com.palli.mobile.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.SendRepository
import com.palli.mobile.domain.TimelineRepository
import com.palli.mobile.domain.Unpairer
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.ConnState
import com.palli.mobile.sync.StreamSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The chat's logic. The primary constructor takes the few calls the screen makes on the repositories, the stream and
 * the API, so tests can stand in for them; the `@Inject` one is what Hilt uses (the same shape as PairViewModel).
 *
 * The chat never starts or stops the stream: the service and the controller own that. It only reads what the stream
 * says (`herTyping`, `connection`) and sends the one frame it may, `typing`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel(
    private val observe: (window: Int) -> Flow<List<TimelineItem>>,
    private val enqueue: suspend (text: String) -> Unit,
    private val retrySend: suspend (clientId: String) -> Unit,
    private val sendTyping: () -> Unit,
    private val eventCount: suspend () -> Int,
    private val minId: suspend () -> Long?,
    private val insertNew: suspend (List<EventDto>) -> List<EventDto>,
    /** The API of the session this phone holds; null when there is none. */
    private val api: () -> PalliApi?,
    private val unpaired: suspend (reason: String?) -> Unit,
    val herTyping: StateFlow<Boolean>,
    val connection: StateFlow<ConnState>,
    private val clock: () -> Long,
) : ViewModel() {
    @Inject constructor(
        timeline: TimelineRepository,
        sends: SendRepository,
        stream: StreamSession,
        sessions: SessionStore,
        apis: ApiFactory,
        unpairer: Unpairer,
    ) : this(
        observe = timeline::observe,
        enqueue = { text -> sends.enqueue(text) },
        retrySend = sends::retry,
        sendTyping = stream::sendTyping,
        eventCount = timeline::eventCount,
        minId = timeline::minId,
        insertNew = timeline::insertNew,
        api = { sessions.current?.let { apis.forBase(it.baseUrl) } },
        unpaired = unpairer::unpaired,
        herTyping = stream.herTyping,
        connection = stream.state,
        clock = SystemClock::elapsedRealtime,
    )

    private val window = MutableStateFlow(FIRST_WINDOW)
    val items: StateFlow<List<TimelineItem>> = window
        .flatMapLatest { observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner.asStateFlow()

    private val throttle = TypingThrottle(intervalMs = 3_000)
    private var loadingOlder = false
    private var noOlder = false
    private var doorCheck: Job? = null

    init {
        checkDoor()
        // The door can open or shut while the app is open, and a phone that was offline at launch learned nothing then:
        // ask again each time the stream (re)connects.
        viewModelScope.launch {
            connection.map { it == ConnState.CONNECTED }.distinctUntilChanged().filter { it }.collect { checkDoor() }
        }
    }

    fun onDraft(text: String) {
        _draft.value = text
        if (text.isNotBlank() && throttle.shouldSend(clock())) sendTyping()
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            try {
                enqueue(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not queued (a full disk, a database error): hand back what was typed rather than lose it.
                Log.w(TAG, "a message could not be queued: ${e.javaClass.simpleName}")
                _draft.update { if (it.isBlank()) text else it }
            }
        }
    }

    /** `key` is a pending item's key: "p<clientId>". Any other key is not a send, and is ignored. */
    fun retry(key: String) {
        if (!key.startsWith("p")) return
        viewModelScope.launch { logged("retry") { retrySend(key.removePrefix("p")) } }
    }

    /**
     * Called when the oldest bubble comes near. Older events this phone already holds are only hidden by the window, so
     * those are shown first, with no network; only when the window already reaches the oldest one held does it ask PaLLi
     * for the next page before it.
     */
    fun loadOlder() {
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                if (eventCount() > window.value) {
                    window.update { it + PAGE }
                    return@launch
                }
                if (noOlder) return@launch
                val api = api() ?: return@launch
                val before = minId() ?: return@launch
                apiCall { api.eventsBefore(before) }
                    .onSuccess { page ->
                        insertNew(page.events)
                        window.update { it + page.events.size }
                        noOlder = !page.more
                    }
                    .onFailure { if (it is ApiError.Unauthorized) unpaired(UNPAIRED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "older messages could not be loaded: ${e.javaClass.simpleName}")
            } finally {
                loadingOlder = false
            }
        }
    }

    private fun checkDoor() {
        val api = api() ?: return
        doorCheck?.cancel()
        doorCheck = viewModelScope.launch {
            logged("door check") {
                apiCall { api.me() }
                    .onSuccess { _banner.value = DoorBanner.text(it) }
                    .onFailure { if (it is ApiError.Unauthorized) unpaired(UNPAIRED) }
            }
        }
    }

    /** Runs `block`; logs (class name only) and swallows anything but a real cancellation, so no launch here can take the app down. */
    private suspend inline fun logged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val FIRST_WINDOW = 200
        const val PAGE = 50
        const val UNPAIRED = "This phone was unpaired."
        private const val TAG = "ChatViewModel"
    }
}
