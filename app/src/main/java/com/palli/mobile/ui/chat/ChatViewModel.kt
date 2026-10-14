package com.palli.mobile.ui.chat

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.OutgoingFiles
import com.palli.mobile.domain.SendGate
import com.palli.mobile.domain.SendRepository
import com.palli.mobile.domain.TimelineRepository
import com.palli.mobile.domain.Unpairer
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.ConnState
import com.palli.mobile.sync.StreamSession
import com.palli.mobile.ui.media.ChatMedia
import com.palli.mobile.ui.media.Recorder
import com.palli.mobile.ui.media.VoiceRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * The chat's logic. The primary constructor takes the few calls the screen makes on the repositories, the stream and
 * the API, so tests can stand in for them; the `@Inject` one is what Hilt uses (the same shape as PairViewModel).
 *
 * The chat never starts or stops the stream: the service and the controller own that. It only reads what the stream
 * says (`herTyping`, `connection`) and sends the one frame it may, `typing`.
 *
 * Photos, files and voice notes (the parameters after `clock`) have defaults, so a test of the text chat can leave them
 * out: such a chat simply has no media. Hilt always passes the real ones.
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
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
    /** Queues a send that carries files, under [clientId]. Like [enqueue], it throws only when nothing was written. */
    private val enqueueWith: suspend (text: String, attachments: List<LocalAttachment>, clientId: String) -> Unit =
        { _, _, _ -> throw UnsupportedOperationException("this chat has no media") },
    private val context: Context? = null,
    private val outgoing: OutgoingFiles? = null,
    private val recorder: Recorder = Recorder.None,
    /** What the bubbles need to show, play and save her files; null in a chat that has no media. */
    val media: ChatMedia? = null,
    /** Shared with the notification's Reply: the chat writes it from each `/me` answer, every way of sending reads it. */
    private val gate: SendGate = SendGate(),
    /** Where picked files are copied in. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject constructor(
        timeline: TimelineRepository,
        sends: SendRepository,
        stream: StreamSession,
        sessions: SessionStore,
        apis: ApiFactory,
        unpairer: Unpairer,
        prefs: ServerPrefs,
        @ApplicationContext context: Context,
        outgoing: OutgoingFiles,
        recorder: VoiceRecorder,
        media: ChatMedia,
        gate: SendGate,
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
        newClientId = sends::newClientId,
        enqueueWith = { text, attachments, clientId -> sends.enqueue(text, attachments, clientId) },
        context = context,
        outgoing = outgoing,
        recorder = recorder,
        media = media,
        gate = gate,
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
     * is off, nothing is typed or sent (no words, photos, files or voice notes), and a pending send cannot be retried. The
     * banner says why. The flag lives in [SendGate], so the notification's Reply honours it too.
     */
    val sendingBlocked: StateFlow<Boolean> = gate.blocked
    private val blocked: Boolean get() = gate.blocked.value

    /** True while a voice note is being recorded. */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    /** The send the note being recorded belongs to: its file is in that send's folder. */
    private var recordingId: String? = null

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
        // What came of saving one of her files is said like any other notice.
        media?.let { m -> viewModelScope.launch { m.said.collect { tell(it) } } }
    }

    fun onDraft(text: String) {
        if (blocked) return
        _draft.value = text
        if (text.isNotBlank() && throttle.shouldSend(clock())) sendTyping()
    }

    fun send() {
        if (blocked) return
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch { queue(text, clientId = null, Sent.WORDS) { enqueue(text) } }
    }

    /**
     * Photos from the picker, or a document: each is copied into this send's own folder first (a picker's grant can
     * expire before the send goes, and an upload part must be a file of known length), then they are queued together with
     * any words in the box. What could not be opened is left out, and said; if nothing could be, nothing is sent.
     */
    fun sendPicked(uris: List<Uri>, kind: String) {
        if (blocked || uris.isEmpty()) return
        val context = context ?: return
        val outgoing = outgoing ?: return
        val text = _draft.value.trim()
        _draft.value = ""
        viewModelScope.launch(io) {
            // Once picked, the send is either queued or undone, even if the chat closes meanwhile.
            withContext(NonCancellable) {
                val clientId = newClientId()
                val attachments = uris.mapNotNull { uri ->
                    try {
                        copyIn(context.contentResolver, outgoing, clientId, uri, kind)
                    } catch (e: Exception) {
                        Log.w(TAG, "a picked file could not be copied: ${e.javaClass.simpleName}")
                        null
                    }
                }
                if (attachments.isEmpty()) {
                    outgoing.forget(clientId)
                    giveBack(text)
                    tell(PICK_UNREADABLE)
                    return@withContext
                }
                val queued = queue(text, clientId, Sent.FILES) { enqueueWith(text, attachments, clientId) }
                if (queued && attachments.size < uris.size) tell(SOME_UNREADABLE)
            }
        }
    }

    /**
     * Where the camera is to write the next photo (the screen shares it through FileProvider); null while sending is
     * blocked. One photo is taken at a time, so whatever an earlier one left behind is cleared first.
     */
    fun newCameraFile(): File? {
        if (blocked) return null
        val dir = File(context?.cacheDir ?: return null, "camera")
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return File(dir, "photo-${System.currentTimeMillis()}.jpg")
    }

    /** The camera's photo: moved into its send's own folder and queued on its own. The words in the box stay there. */
    fun sendCameraPhoto(file: File) {
        val outgoing = outgoing
        if (blocked || outgoing == null || !file.isFile || file.length() == 0L) {
            file.delete()
            return
        }
        viewModelScope.launch(io) {
            withContext(NonCancellable) {
                val clientId = newClientId()
                val photo = try {
                    File(outgoing.dirFor(clientId), "photo.jpg").also { file.copyTo(it, overwrite = true) }
                } catch (e: Exception) {
                    Log.w(TAG, "a photo could not be moved: ${e.javaClass.simpleName}")
                    outgoing.forget(clientId)
                    tell(PHOTO_FAILED)
                    return@withContext
                } finally {
                    file.delete()
                }
                queue("", clientId, Sent.PHOTO) {
                    enqueueWith("", listOf(LocalAttachment(photo.path, "image", "image/jpeg", "photo.jpg")), clientId)
                }
            }
        }
    }

    /** Hold-to-talk begins: the note is recorded straight into its send's own folder. */
    fun startRecording() {
        if (blocked || recordingId != null) return
        val outgoing = outgoing ?: return
        val clientId = newClientId()
        try {
            recorder.start(File(outgoing.dirFor(clientId), "voice.m4a"))
            recordingId = clientId
            _recording.value = true
        } catch (e: Exception) {
            Log.w(TAG, "the microphone could not start: ${e.javaClass.simpleName}")
            outgoing.forget(clientId)
            tell(MIC_FAILED)
        }
    }

    /** Let go: the note is sent, unless it was a slip of the thumb (or sending was blocked meanwhile), then dropped. */
    fun stopRecording() {
        val clientId = recordingId ?: return
        recordingId = null
        _recording.value = false
        if (blocked) {
            recorder.cancel()
            outgoing?.forget(clientId)
            return
        }
        val note = try {
            recorder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "the microphone could not stop: ${e.javaClass.simpleName}")
            null
        }
        if (note == null) {
            outgoing?.forget(clientId)
            tell(HOLD_TO_RECORD)
            return
        }
        val (file, durationMs) = note
        viewModelScope.launch {
            withContext(NonCancellable) {
                queue("", clientId, Sent.VOICE) {
                    enqueueWith("", listOf(LocalAttachment(file.path, "voice", "audio/mp4", "voice.m4a", durationMs)), clientId)
                }
            }
        }
    }

    /** The finger slid off the microphone, or the screen went: the note is dropped. */
    fun cancelRecording() {
        val clientId = recordingId
        recordingId = null
        _recording.value = false
        recorder.cancel()
        clientId?.let { outgoing?.forget(it) }
    }

    /** No app on this phone could take what the chat asked for (a camera, a picker). */
    fun noAppForThat() = tell(NO_APP)

    /** The owner said no to [permission] (the microphone or the camera). */
    fun refused(permission: String) = tell(if (permission == Manifest.permission.RECORD_AUDIO) MIC_REFUSED else CAMERA_REFUSED)

    /** `key` is a pending item's key: "p<clientId>". Any other key is not a send, and is ignored; so is any key while sending is blocked. */
    fun retry(key: String) {
        if (blocked || !key.startsWith("p")) return
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
                        gate.set(it.apiVersion != Wire.API_VERSION)
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

    private enum class Sent { WORDS, FILES, PHOTO, VOICE }

    /**
     * Queues one send, through [block]. That throws only when nothing was written (see SendRepository), so then the
     * send is undone here and cannot have a second copy: the files copied for it are deleted, the words taken from the
     * box go back unless something new has been typed since, and the owner is told. A send that was written but could
     * not be started is a failed bubble instead. Returns whether the send was queued.
     */
    private suspend fun queue(text: String, clientId: String?, what: Sent, block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a send could not be queued: ${e.javaClass.simpleName}")
            clientId?.let { id -> outgoing?.forget(id) }
            val restored = giveBack(text)
            tell(when (what) {
                Sent.WORDS -> if (restored) SEND_FAILED_KEPT else SEND_FAILED_LOST
                Sent.FILES -> when {
                    text.isEmpty() -> FILES_FAILED
                    restored -> FILES_FAILED_KEPT
                    else -> FILES_FAILED_LOST
                }
                Sent.PHOTO -> PHOTO_FAILED
                Sent.VOICE -> VOICE_FAILED
            })
            return false
        }
    }

    /** Puts [text] back in the box, only if the box is still empty. Says whether it did. */
    private fun giveBack(text: String): Boolean {
        if (text.isEmpty()) return false
        var restored = false
        _draft.update { current -> if (current.isBlank()) text.also { restored = true } else current.also { restored = false } }
        return restored
    }

    /**
     * One picked item, copied into the send's folder. Its `name` is what the picker calls it (the copy's own file name
     * has a random prefix); its type is what the picker says, else what the name's extension says.
     */
    private fun copyIn(resolver: ContentResolver, outgoing: OutgoingFiles, clientId: String, uri: Uri, kind: String): LocalAttachment {
        val shown = displayName(resolver, uri)
        val mime = resolver.getType(uri)
            ?: shown?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
        val name = shown ?: fallbackName(mime)
        val copy = outgoing.copyIn(clientId, uri, name)
        val image = kind == "image" || mime.startsWith("image/")
        return LocalAttachment(copy.path, if (image) "image" else "file", mime, name)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0)?.takeIf(String::isNotBlank) else null
        }

    private fun fallbackName(mime: String): String {
        val stem = if (mime.startsWith("image/")) "photo" else "file"
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { "$stem.$it" } ?: stem
    }

    override fun onCleared() {
        media?.player?.stop()
        cancelRecording()
    }

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
        const val FILES_FAILED = "Couldn't send that. Pick it again to try once more."
        const val FILES_FAILED_KEPT = "Couldn't send that. Your words are back in the box; pick the files again to try once more."
        const val FILES_FAILED_LOST = "Couldn't send that, and you'd typed something new, so type your words again and pick the files again."
        const val PHOTO_FAILED = "Couldn't send that photo. Take it again to try once more."
        const val VOICE_FAILED = "Couldn't send that voice note. Record it again to try once more."
        const val PICK_UNREADABLE = "Couldn't open what you picked, so nothing was sent."
        const val SOME_UNREADABLE = "Some of what you picked couldn't be opened, so it was left out."
        const val MIC_FAILED = "Couldn't start the microphone."
        const val HOLD_TO_RECORD = "Hold the microphone down while you talk, then let go to send."
        const val NO_APP = "There's no app on this phone for that."
        const val MIC_REFUSED = "PaLLi can't use the microphone. You can allow it in the phone's settings."
        const val CAMERA_REFUSED = "PaLLi can't use the camera. You can allow it in the phone's settings."
        const val OLDER_UNREACHABLE = "Can't reach PaLLi to load older messages. Is Tailscale on?"
        const val OLDER_FAILED = "Couldn't load older messages."
        private const val TAG = "ChatViewModel"
    }
}
