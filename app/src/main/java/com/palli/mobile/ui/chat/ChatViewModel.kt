package com.palli.mobile.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.ServerPrefs
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
import kotlinx.coroutines.flow.drop
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
    private val askedNotifications: suspend () -> Boolean,
    private val markAskedNotifications: suspend () -> Unit,
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
        prefs: ServerPrefs,
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
        askedNotifications = prefs::askedNotifications,
        markAskedNotifications = prefs::markAskedNotifications,
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

    /**
     * True while PaLLi reports another api version than this app speaks: the app then refuses to chat with her. The composer
     * is off, nothing is typed or sent, and a pending send cannot be retried. The banner says why.
     */
    private val _sendingBlocked = MutableStateFlow(false)
    val sendingBlocked: StateFlow<Boolean> = _sendingBlocked.asStateFlow()

    /** A short plain line for the screen to say once (a send that failed, scrollback that could not load); see [noticeShown]. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val throttle = TypingThrottle(intervalMs = 3_000)
    private var loadingOlder = false
    private var noOlder = false
    private var olderFailureTold = false
    private var doorCheck: Job? = null
    private var doorAnsweredAt: Long? = null

    init {
        checkDoor()
        // The door can open or shut while the app is open, and a phone that was offline at launch learned nothing then:
        // ask again when the stream connects. Only a connect after this start counts (the state it starts in is what the
        // check above already covers), and not within [DOOR_RECHECK_MS] of an answer, so a link that flaps does not ask
        // each time. A check that has had no answer yet, such as one still waiting on a dead network, is replaced.
        viewModelScope.launch {
            connection.map { it == ConnState.CONNECTED }.distinctUntilChanged().drop(1).filter { it }.collect {
                val answered = doorAnsweredAt
                if (answered == null || clock() - answered >= DOOR_RECHECK_MS) checkDoor()
            }
        }
    }

    fun onDraft(text: String) {
        if (_sendingBlocked.value) return
        _draft.value = text
        if (text.isNotBlank() && throttle.shouldSend(clock())) sendTyping()
    }

    fun send() {
        if (_sendingBlocked.value) return
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            try {
                enqueue(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `enqueue` throws only when nothing was written (see SendRepository), so handing the text back cannot
                // make a second copy. A send that was written but could not be started is a failed bubble instead.
                Log.w(TAG, "a message could not be queued: ${e.javaClass.simpleName}")
                val restored = _draft.value.isBlank()
                if (restored) _draft.value = text
                tell(if (restored) SEND_FAILED_KEPT else SEND_FAILED_LOST)
            }
        }
    }

    /** `key` is a pending item's key: "p<clientId>". Any other key is not a send, and is ignored; so is any key while sending is blocked. */
    fun retry(key: String) {
        if (_sendingBlocked.value || !key.startsWith("p")) return
        viewModelScope.launch {
            try {
                retrySend(key.removePrefix("p"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a send could not be retried: ${e.javaClass.simpleName}")
                tell(RETRY_FAILED)
            }
        }
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
                        olderFailureTold = false
                    }
                    .onFailure {
                        if (it is ApiError.Unauthorized) unpaired(UNPAIRED)
                        else tellOlderFailed(if (it is ApiError.Unreachable) OLDER_UNREACHABLE else OLDER_FAILED)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "older messages could not be loaded: ${e.javaClass.simpleName}")
                tellOlderFailed(OLDER_FAILED)
            } finally {
                loadingOlder = false
            }
        }
    }

    private fun checkDoor() {
        val api = api() ?: return
        doorCheck?.cancel()                // a newer check replaces an older one still out: its answer is the stale one
        doorCheck = viewModelScope.launch {
            logged("door check") {
                apiCall { api.me() }
                    .onSuccess {
                        doorAnsweredAt = clock()
                        _banner.value = DoorBanner.text(it)
                        _sendingBlocked.value = it.apiVersion != Wire.API_VERSION
                    }
                    .onFailure { if (it is ApiError.Unauthorized) unpaired(UNPAIRED) }
            }
        }
    }

    /** Whether to put the system's notification question to the owner: only if it has never been put (so a denial stays one). */
    suspend fun shouldAskNotifications(): Boolean = try {
        !askedNotifications()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false                              // cannot tell: better not to ask than to ask again and again
    }

    /** Call just before putting the question, so even a process that dies with it open does not ask it again. */
    suspend fun notificationsAsked() { logged("remembering the notification question") { markAskedNotifications() } }

    /** The screen has shown [text]; forget it, unless a newer notice has already replaced it. */
    fun noticeShown(text: String) = _notice.update { if (it == text) null else it }

    private fun tell(text: String) { _notice.value = text }

    /** Told once per run of failures, not at each scroll near the end of the log while PaLLi cannot be reached. */
    private fun tellOlderFailed(text: String) {
        if (olderFailureTold) return
        olderFailureTold = true
        tell(text)
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
        /** The least time between two door checks made because the stream connected. */
        const val DOOR_RECHECK_MS = 30_000L

        const val SEND_FAILED_KEPT = "Couldn't send that message. It's back in the box, so you can try again."
        const val SEND_FAILED_LOST = "Couldn't send that message, and you'd typed something new, so type it again."
        const val RETRY_FAILED = "Couldn't send that again just now. Tap it to try once more."
        const val OLDER_UNREACHABLE = "Can't reach PaLLi to load older messages. Is Tailscale on?"
        const val OLDER_FAILED = "Couldn't load older messages."
        private const val TAG = "ChatViewModel"
    }
}
