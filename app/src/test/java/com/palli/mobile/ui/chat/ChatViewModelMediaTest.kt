package com.palli.mobile.ui.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.AcceptedResponse
import com.palli.mobile.data.remote.DeviceRef
import com.palli.mobile.data.remote.DevicesResponse
import com.palli.mobile.data.remote.EventsPage
import com.palli.mobile.data.remote.MeResponse
import com.palli.mobile.data.remote.OkResponse
import com.palli.mobile.data.remote.PairRequest
import com.palli.mobile.data.remote.PairResponse
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.RemindersResponse
import com.palli.mobile.data.remote.SnoozeRequest
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.domain.OutgoingFiles
import com.palli.mobile.domain.SendGate
import com.palli.mobile.domain.SendProcessor
import com.palli.mobile.sync.ConnState
import com.palli.mobile.sync.ReplyReceiver
import com.palli.mobile.ui.media.ChatMedia
import com.palli.mobile.ui.media.FileCache
import com.palli.mobile.ui.media.Recorder
import com.palli.mobile.ui.media.VoicePlayer
import com.palli.mobile.data.remote.FileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileNotFoundException

/**
 * Photos, files and voice notes from the chat: copied into the app's own storage, queued, and undone cleanly when the
 * queue will not take them. Robolectric, for real files, a real content resolver and the picker's display names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outgoing = OutgoingFiles(context)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        outgoing.forgetAll()
    }
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- stand-ins ----

    /** What a picker hands back: content URIs with a display name and a type, as the system's do. */
    class FakePicker : ContentProvider() {
        /** [size] is what the picker says, which may be missing (null) or wrong. */
        class Item(val name: String?, val mime: String?, val file: File?, val size: Long?)
        val items = mutableMapOf<String, Item>()
        var opened = 0

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            val item = items[uri.lastPathSegment] ?: return null
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { if (it == OpenableColumns.SIZE) item.size else item.name }.toTypedArray())
            }
        }
        override fun getType(uri: Uri): String? = items[uri.lastPathSegment]?.mime
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opened++
            val file = items[uri.lastPathSegment]?.file ?: throw FileNotFoundException("the grant has expired")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    private val picker: FakePicker = Robolectric.setupContentProvider(FakePicker::class.java, PICKER)

    private fun picked(id: String, name: String?, mime: String?, bytes: ByteArray?, size: Long? = bytes?.size?.toLong()): Uri {
        picker.items[id] = FakePicker.Item(name, mime, bytes?.let { tmp.newFile().apply { writeBytes(it) } }, size)
        return Uri.parse("content://$PICKER/items/$id")
    }

    /** A file of [length] bytes that takes no room on disk. */
    private fun sparse(length: Long, dir: File = tmp.root): File =
        File.createTempFile("big", ".bin", dir).apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(length) } }

    private val mb = 1024L * 1024

    /** Writes a small note where it is told to, as MediaRecorder would. */
    private inner class FakeRecorder : Recorder {
        var failStart: Exception? = null
        var tooShort = false
        var recordingTo: File? = null
        val cancelled = mutableListOf<File?>()
        override fun start(file: File) {
            failStart?.let { throw it }
            file.writeBytes(VOICE)
            recordingTo = file
        }
        override fun stop(): Pair<File, Long>? {
            val file = recordingTo ?: return null
            recordingTo = null
            if (tooShort) { file.delete(); return null }
            return file to 4_200L
        }
        override fun cancel() { cancelled += recordingTo; recordingTo?.delete(); recordingTo = null }
    }

    private class Api(val version: () -> Int) : PalliApi {
        override suspend fun me() = MeResponse(DeviceRef(1, "Phone"), "open", "", version())
        override suspend fun pair(body: PairRequest): PairResponse = error("not used")
        override suspend fun send(parts: List<MultipartBody.Part>): AcceptedResponse = error("not used")
        override suspend fun eventsAfter(after: Long, limit: Int): EventsPage = error("not used")
        override suspend fun eventsBefore(before: Long, limit: Int): EventsPage = error("not used")
        override suspend fun reminders(): RemindersResponse = error("not used")
        override suspend fun snooze(id: Long, body: SnoozeRequest): OkResponse = error("not used")
        override suspend fun cancelReminder(id: Long): OkResponse = error("not used")
        override suspend fun devices(): DevicesResponse = error("not used")
        override suspend fun revoke(id: Long): OkResponse = error("not used")
    }

    data class Queued(val text: String, val attachments: List<LocalAttachment>, val clientId: String)

    private val recorder = FakeRecorder()
    private val queued = mutableListOf<Queued>()
    private var enqueueFailure: Throwable? = null
    private var version = 1
    private var now = 10_000L
    private var ids = 0
    private val connection = MutableStateFlow(ConnState.IDLE)

    private fun viewModel(gate: SendGate = SendGate(), media: ChatMedia? = null) = ChatViewModel(
        observe = { flowOf(emptyList()) },
        enqueue = { enqueueFailure?.let { throw it } },
        retrySend = { },
        sendTyping = { },
        eventCount = { 0 },
        minId = { null },
        insertNew = { it },
        api = { Api { version } },
        unpaired = { },
        askedNotifications = { true },
        markAskedNotifications = { },
        herTyping = MutableStateFlow(false),
        connection = connection,
        clock = { now },
        newClientId = { "c-${++ids}" },
        enqueueWith = { text, attachments, clientId ->
            enqueueFailure?.let { throw it }
            queued += Queued(text, attachments, clientId)
        },
        context = context,
        outgoing = outgoing,
        recorder = recorder,
        media = media,
        gate = gate,
        io = Dispatchers.Unconfined,
    )

    private fun folder(clientId: String) = File(context.filesDir, "outgoing/$clientId")
    private fun nothingCopied() = File(context.filesDir, "outgoing").listFiles().orEmpty().all { it.listFiles().isNullOrEmpty() }

    /** The part SendProcessor makes for an attachment: a file of known length, never -1 (which makes the send chunked: 413). */
    private fun partLength(a: LocalAttachment) = File(a.path).asRequestBody(a.mime.toMediaType()).contentLength()

    // ---- picked photos and files ----

    @Test fun `picked photos are copied in, keep the picker's names, and go with the words in the box`() {
        val holiday = picked("1", "Holiday photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val cat = picked("2", "cat.png", "image/png", byteArrayOf(4, 5))
        val vm = viewModel()
        vm.onDraft("look at these ")
        vm.sendPicked(listOf(holiday, cat), "image")

        val send = queued.single()
        assertEquals("look at these", send.text)
        assertEquals("c-1", send.clientId)
        assertEquals(listOf("Holiday photo.jpg", "cat.png"), send.attachments.map { it.name })
        assertEquals(listOf("image", "image"), send.attachments.map { it.kind })
        assertEquals(listOf("image/jpeg", "image/png"), send.attachments.map { it.mime })
        val copy = File(send.attachments[0].path)
        assertEquals(folder("c-1").canonicalPath, copy.parentFile!!.canonicalPath)
        assertNotEquals("the copy's own name has a random prefix; the name PaLLi sees does not", "Holiday photo.jpg", copy.name)
        assertTrue(copy.name, copy.name.endsWith("-Holiday_photo.jpg"))
        assertEquals(listOf(1, 2, 3), copy.readBytes().map(Byte::toInt))
        assertEquals(listOf(3L, 2L), send.attachments.map(::partLength))
        assertEquals("", vm.draft.value)
        assertNull(vm.notice.value)
    }

    @Test fun `a picked document is a file, named as the picker names it, and its type taken from the picker`() {
        val bill = picked("3", "Bill March.pdf", "application/pdf", "%PDF".toByteArray())
        viewModel().sendPicked(listOf(bill), "file")

        val a = queued.single().attachments.single()
        assertEquals(LocalAttachment(a.path, "file", "application/pdf", "Bill March.pdf"), a)
        assertEquals("", queued.single().text)
    }

    @Test fun `with no type from the picker, the name's extension says it, and with no name either, it is called by its type`() {
        // What a phone's MimeTypeMap knows; Robolectric's starts empty.
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("jpg", "image/jpeg")
        val noType = picked("4", "scan.png", null, byteArrayOf(9))
        val nothing = picked("5", null, "image/jpeg", byteArrayOf(9))
        viewModel().sendPicked(listOf(noType, nothing), "file")

        val (first, second) = queued.single().attachments
        assertEquals("image/png" to "image", first.mime to first.kind)
        assertEquals("photo.jpg" to "image", second.name to second.kind)
    }

    @Test fun `every attachment queued makes a multipart of known length, so PaLLi never sees a chunked upload`() {
        val vm = viewModel()
        vm.sendPicked(listOf(picked("6", "a.jpg", "image/jpeg", ByteArray(2048))), "image")
        vm.sendCameraPhoto(cameraShot(vm))
        vm.startRecording(); vm.stopRecording()

        assertEquals(3, queued.size)
        for (send in queued) {
            val row = PendingSendEntity(send.clientId, send.text,
                Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), send.attachments),
                SendStates.QUEUED, null, 0L, 0)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .apply { SendProcessor.buildParts(row).forEach(::addPart) }.build()
            assertNotEquals("${send.attachments.single().kind} makes the whole send chunked", -1L, body.contentLength())
            assertTrue(File(send.attachments.single().path).canonicalPath.startsWith(File(context.filesDir, "outgoing").canonicalPath))
        }
    }

    @Test fun `what could not be opened is left out and said, and nothing at all is sent when none could be`() {
        val good = picked("7", "good.jpg", "image/jpeg", byteArrayOf(1))
        val expired = picked("8", "expired.jpg", "image/jpeg", null)
        val vm = viewModel()
        vm.sendPicked(listOf(good, expired), "image")
        assertEquals(listOf("good.jpg"), queued.single().attachments.map { it.name })
        assertEquals(ChatViewModel.SOME_UNREADABLE, vm.notice.value)

        queued.clear()
        vm.onDraft("caption")
        vm.sendPicked(listOf(expired), "image")
        assertTrue(queued.isEmpty())
        assertEquals(ChatViewModel.PICK_UNREADABLE, vm.notice.value)
        assertEquals("the words go back", "caption", vm.draft.value)
        assertFalse(folder("c-2").exists())
    }

    // ---- PaLLi's limits: 20 MB a file, 60 MB a send ----

    @Test fun `a pick the picker says is over 20 MB is refused before anything is copied, and the words go back`() {
        val vm = viewModel()
        vm.onDraft("caption")
        vm.sendPicked(listOf(picked("20", "film.mp4", "video/mp4", byteArrayOf(1), size = 21 * mb)), "file")

        assertEquals(0, picker.opened)
        assertTrue(queued.isEmpty())
        assertTrue(nothingCopied())
        assertEquals(ChatViewModel.PART_TOO_BIG, vm.notice.value)
        assertEquals("caption", vm.draft.value)
    }

    @Test fun `a pick whose size is not said but runs past 20 MB is refused, and leaves no partial copy`() {
        picker.items["21"] = FakePicker.Item("dump.bin", "application/octet-stream", sparse(20 * mb + 1), size = null)
        val vm = viewModel()
        vm.onDraft("caption")
        vm.sendPicked(listOf(picked("22", "small.txt", "text/plain", byteArrayOf(1)), Uri.parse("content://$PICKER/items/21")), "file")

        assertTrue(queued.isEmpty())
        assertTrue("no partial file and no folder", nothingCopied())
        assertFalse(folder("c-1").exists())
        assertEquals(ChatViewModel.PART_TOO_BIG, vm.notice.value)
        assertEquals("caption", vm.draft.value)
    }

    @Test fun `three 19 MB picks, 57 MB in all, go`() {
        val vm = viewModel()
        vm.sendPicked((1..3).map { picked("3$it", "p$it.jpg", "image/jpeg", byteArrayOf(1), size = 19 * mb) }, "image")
        assertEquals(3, queued.single().attachments.size)
        assertNull(vm.notice.value)
    }

    @Test fun `four 19 MB picks, 76 MB in all, are refused before anything is copied`() {
        val vm = viewModel()
        vm.onDraft("caption")
        vm.sendPicked((1..4).map { picked("4$it", "p$it.jpg", "image/jpeg", byteArrayOf(1), size = 19 * mb) }, "image")
        assertEquals(0, picker.opened)
        assertTrue(queued.isEmpty())
        assertEquals(ChatViewModel.SEND_TOO_BIG, vm.notice.value)
        assertEquals("caption", vm.draft.value)
    }

    @Test fun `picks whose sizes are not said but come to over 60 MB are refused, and leave nothing behind`() {
        val parts = (1..4).map { i ->
            picker.items["5$i"] = FakePicker.Item("p$i.bin", "application/octet-stream", sparse(16 * mb), size = null)
            Uri.parse("content://$PICKER/items/5$i")
        }
        val vm = viewModel()
        vm.sendPicked(parts, "file")
        assertTrue(queued.isEmpty())
        assertTrue(nothingCopied())
        assertEquals(ChatViewModel.SEND_TOO_BIG, vm.notice.value)
    }

    @Test fun `a camera photo over 20 MB is not sent, not kept, and said`() {
        val vm = viewModel()
        vm.onDraft("typed")
        val shot = vm.newCameraFile()!!.also { it.delete() }
        val big = sparse(20 * mb + 1, shot.parentFile!!)
        vm.sendCameraPhoto(big)
        assertTrue(queued.isEmpty())
        assertFalse(big.exists())
        assertEquals(ChatViewModel.PHOTO_TOO_BIG, vm.notice.value)
        assertEquals("typed", vm.draft.value)
    }

    // ---- the camera ----

    private fun cameraShot(vm: ChatViewModel): File = vm.newCameraFile()!!.apply { writeBytes(ByteArray(512) { 7 }) }

    @Test fun `the camera's photo is moved into its send's folder and queued on its own, leaving the words in the box`() {
        val vm = viewModel()
        vm.onDraft("not a caption")
        val shot = cameraShot(vm)
        assertEquals(File(context.cacheDir, "camera").canonicalPath, shot.parentFile!!.canonicalPath)

        vm.sendCameraPhoto(shot)
        val send = queued.single()
        assertEquals("", send.text)
        val a = send.attachments.single()
        assertEquals(LocalAttachment(File(folder("c-1"), "photo.jpg").path, "image", "image/jpeg", "photo.jpg"), a)
        assertEquals(512L, partLength(a))
        assertFalse("the camera's scratch file is gone", shot.exists())
        assertEquals("not a caption", vm.draft.value)
    }

    @Test fun `a camera that saved nothing sends nothing`() {
        val vm = viewModel()
        val empty = vm.newCameraFile()!!.apply { writeBytes(ByteArray(0)) }
        vm.sendCameraPhoto(empty)
        assertTrue(queued.isEmpty())
        assertFalse(empty.exists())
    }

    // ---- the microphone ----

    @Test fun `a held note is recorded into its send's folder and queued as voice when let go`() {
        val vm = viewModel()
        vm.startRecording()
        assertEquals(true, vm.recording.value)
        assertEquals(File(folder("c-1"), "voice.m4a").path, recorder.recordingTo!!.path)

        vm.stopRecording()
        assertEquals(false, vm.recording.value)
        val a = queued.single().attachments.single()
        assertEquals(LocalAttachment(File(folder("c-1"), "voice.m4a").path, "voice", "audio/mp4", "voice.m4a", 4_200L), a)
        assertEquals(VOICE.size.toLong(), partLength(a))
    }

    @Test fun `holding the microphone stops her voice note first, so the mic does not hear her`() {
        val media = chatMedia()
        media.player.toggle("f_her", tmp.newFile("her.ogg").apply { writeBytes(byteArrayOf(0x4f, 0x67, 0x67, 0x53)) })
        assertEquals("f_her", media.player.state.value.playingId)

        val vm = viewModel(media = media)
        vm.startRecording()
        assertNull(media.player.state.value.playingId)
        assertEquals(true, vm.recording.value)
        vm.cancelRecording()
    }

    @Test fun `a slip of the thumb sends nothing, leaves nothing behind, and says how to record`() {
        recorder.tooShort = true
        val vm = viewModel()
        vm.startRecording(); vm.stopRecording()
        assertTrue(queued.isEmpty())
        assertFalse(folder("c-1").exists())
        assertEquals(ChatViewModel.HOLD_TO_RECORD, vm.notice.value)
    }

    @Test fun `a microphone that will not start says so and leaves nothing behind`() {
        recorder.failStart = IllegalStateException("in use")
        val vm = viewModel()
        vm.startRecording()
        assertEquals(false, vm.recording.value)
        assertFalse(folder("c-1").exists())
        assertEquals(ChatViewModel.MIC_FAILED, vm.notice.value)
        vm.stopRecording()                                          // nothing was recording: nothing happens
        assertTrue(queued.isEmpty())
    }

    @Test fun `sliding off drops the note and its folder`() {
        val vm = viewModel()
        vm.startRecording()
        vm.cancelRecording()
        assertEquals(false, vm.recording.value)
        assertEquals(1, recorder.cancelled.size)
        assertFalse(folder("c-1").exists())
        assertTrue(queued.isEmpty())
    }

    @Test fun `a chat that goes away mid-note drops it`() {
        val vm = viewModel()
        vm.startRecording()
        ViewModelStore().apply { put("chat", vm) }.clear()
        assertFalse(folder("c-1").exists())
        assertTrue(queued.isEmpty())
    }

    // ---- a queue that will not take it: each path undoes itself and says so ----

    @Test fun `picked files the queue would not take - no crash, the words back, the copies gone, and it says so`() {
        enqueueFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.onDraft("caption")
        vm.sendPicked(listOf(picked("9", "a.jpg", "image/jpeg", byteArrayOf(1))), "image")

        assertEquals("caption", vm.draft.value)
        assertFalse("forgotten", folder("c-1").exists())
        assertEquals(ChatViewModel.FILES_FAILED_KEPT, vm.notice.value)

        vm.onDraft("")
        vm.sendPicked(listOf(picked("10", "b.jpg", "image/jpeg", byteArrayOf(1))), "image")
        assertEquals(ChatViewModel.FILES_FAILED, vm.notice.value)
        assertFalse(folder("c-2").exists())
    }

    @Test fun `picked files the queue would not take do not overwrite words typed since`() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            observe = { flowOf(emptyList()) }, enqueue = { }, retrySend = { }, sendTyping = { }, eventCount = { 0 },
            minId = { null }, insertNew = { it }, api = { null }, unpaired = { }, askedNotifications = { true },
            markAskedNotifications = { }, herTyping = MutableStateFlow(false), connection = connection, clock = { now },
            newClientId = { "c-${++ids}" },
            enqueueWith = { _, _, _ -> held.await(); throw IllegalStateException("disk full") },
            context = context, outgoing = outgoing, io = Dispatchers.Unconfined,
        )
        vm.onDraft("first")
        vm.sendPicked(listOf(picked("11", "a.jpg", "image/jpeg", byteArrayOf(1))), "image")
        vm.onDraft("second")
        held.complete(Unit)                                         // now the first one fails
        assertEquals("second", vm.draft.value)
        assertEquals(ChatViewModel.FILES_FAILED_LOST, vm.notice.value)
        assertFalse(folder("c-1").exists())
    }

    @Test fun `a camera photo the queue would not take - no crash, the words untouched, the copy gone, and it says so`() {
        enqueueFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.onDraft("typed")
        val shot = cameraShot(vm)
        vm.sendCameraPhoto(shot)

        assertEquals("typed", vm.draft.value)
        assertFalse("forgotten", folder("c-1").exists())
        assertFalse(shot.exists())
        assertEquals(ChatViewModel.PHOTO_FAILED, vm.notice.value)
    }

    @Test fun `a voice note the queue would not take - no crash, the words untouched, the note gone, and it says so`() {
        enqueueFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.onDraft("typed")
        vm.startRecording(); vm.stopRecording()

        assertEquals("typed", vm.draft.value)
        assertFalse("forgotten", folder("c-1").exists())
        assertEquals(ChatViewModel.VOICE_FAILED, vm.notice.value)
        assertEquals(false, vm.recording.value)
    }

    @Test fun `a cancelled queueing is not taken for a failure - nothing is undone and nothing is said`() {
        enqueueFailure = CancellationException("cancelled")
        val vm = viewModel()
        vm.onDraft("caption")
        vm.sendPicked(listOf(picked("12", "a.jpg", "image/jpeg", byteArrayOf(1))), "image")
        assertNull(vm.notice.value)
        assertTrue("the row may have been written, so its files stay", folder("c-1").exists())
    }

    // ---- another api version: no media either ----

    @Test fun `another api version blocks every media path - picked, camera, microphone - and copies nothing`() {
        version = 2
        val vm = viewModel()
        assertEquals(true, vm.sendingBlocked.value)
        vm.onDraft("kept")                                          // refused while blocked, so the box stays empty

        vm.sendPicked(listOf(picked("13", "a.jpg", "image/jpeg", byteArrayOf(1))), "image")
        assertNull(vm.newCameraFile())
        val stray = tmp.newFile("shot.jpg").apply { writeBytes(byteArrayOf(1)) }
        vm.sendCameraPhoto(stray)
        vm.startRecording()
        vm.stopRecording()

        assertTrue(queued.isEmpty())
        assertTrue(nothingCopied())
        assertFalse("a photo that cannot be sent is not kept", stray.exists())
        assertNull("the microphone never started", recorder.recordingTo)
        assertEquals(false, vm.recording.value)
    }

    @Test fun `a note being recorded when the block comes down is dropped, not sent`() {
        val vm = viewModel()
        vm.startRecording()
        version = 2
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED                     // the next /me says another version
        assertEquals(true, vm.sendingBlocked.value)

        vm.stopRecording()
        assertTrue(queued.isEmpty())
        assertFalse(folder("c-1").exists())
    }

    @Test fun `the block is written to the gate the notification's Reply reads`() = runTest {
        val gate = SendGate()
        version = 2
        viewModel(gate)
        val said = mutableListOf<String>()
        val replies = mutableListOf<String>()
        ReplyReceiver.reply("hi", gate, { replies += it }, shown = { }, notSent = { said += it })
        assertTrue(replies.isEmpty())
        assertEquals(listOf(ReplyReceiver.BLOCKED), said)

        version = 1
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED
        assertEquals(false, gate.blocked.value)
    }

    @Test fun `after a cold start the last version heard blocks media before PaLLi answers again`() {
        val remembered = SendGate(readVersion = { 2 }, writeVersion = { })
        val vm = ChatViewModel(
            observe = { flowOf(emptyList()) }, enqueue = { }, retrySend = { }, sendTyping = { }, eventCount = { 0 },
            minId = { null }, insertNew = { it }, api = { null },          // no answer from /me yet
            unpaired = { }, askedNotifications = { true }, markAskedNotifications = { },
            herTyping = MutableStateFlow(false), connection = connection, clock = { now },
            newClientId = { "c-${++ids}" }, enqueueWith = { _, a, id -> queued += Queued("", a, id) },
            context = context, outgoing = outgoing, recorder = recorder, gate = remembered, io = Dispatchers.Unconfined,
        )
        assertEquals(true, vm.sendingBlocked.value)
        vm.sendPicked(listOf(picked("60", "a.jpg", "image/jpeg", byteArrayOf(1))), "image")
        vm.startRecording()
        assertTrue(queued.isEmpty())
        assertNull(recorder.recordingTo)
    }

    // ---- what came of saving her file ----

    private fun chatMedia(): ChatMedia {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("s.preferences_pb")
        }
        val sessions = SessionStore(ServerPrefs(store), object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(blob: String) = blob
        })
        return ChatMedia(ImageLoader.Builder(context).build(), VoicePlayer(context),
            FileCache(context, OkHttpClient(), sessions), session = { null },
            saveFile = { _, _, _ -> error("not reached") }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    @Test fun `what came of saving her file is said over the composer`() = runTest {
        val media = chatMedia()
        val vm = viewModel(media = media)

        media.download(FileRef("f_1", "a.pdf"))                    // unpaired: the fetch cannot be made
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            while (vm.notice.value == null) kotlinx.coroutines.delay(10)
        }
        assertEquals(ChatMedia.FETCH_FAILED, vm.notice.value)
    }

    companion object {
        const val PICKER = "com.palli.mobile.test.picker"
        val VOICE = "m4a-note".toByteArray()
    }
}
