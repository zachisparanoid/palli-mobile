package com.palli.mobile.ui.chat

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.remote.AcceptedResponse
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.DeviceRef
import com.palli.mobile.data.remote.DevicesResponse
import com.palli.mobile.data.remote.EventsPage
import com.palli.mobile.data.remote.FileRef
import com.palli.mobile.data.remote.MeResponse
import com.palli.mobile.data.remote.OkResponse
import com.palli.mobile.data.remote.PairRequest
import com.palli.mobile.data.remote.PairResponse
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.RemindersResponse
import com.palli.mobile.data.remote.SnoozeRequest
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.domain.OutgoingFiles
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.ConnState
import com.palli.mobile.ui.media.ChatMedia
import com.palli.mobile.ui.media.Downloads
import com.palli.mobile.ui.media.FileCache
import com.palli.mobile.ui.media.Recorder
import com.palli.mobile.ui.media.VoicePlayer
import com.palli.mobile.ui.theme.PalliTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The chat screen's photos, files and voice notes on Robolectric: what it asks the system for, and what comes back. */
@RunWith(RobolectricTestRunner::class)
class ChatScreenMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val items = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val queued = mutableListOf<List<LocalAttachment>>()
    private var version = 1
    private var ids = 0
    private val server = MockWebServer()

    /**
     * FileProvider keeps each authority's roots in a static map, and Robolectric gives every test a new data folder in
     * the same class loader: forget the roots, so this test's cache folder is the one shared.
     */
    @Before fun forgetFileProviderRoots() {
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>).clear()
    }

    @After fun tearDown() = server.shutdown()

    private class Api(val version: () -> Int) : PalliApi {
        override suspend fun me() = MeResponse(DeviceRef(1, "Phone"), "open", "", version())
        override suspend fun pair(body: PairRequest): PairResponse = error("not used")
        override suspend fun send(parts: List<MultipartBody.Part>): AcceptedResponse = error("not used")
        override suspend fun eventsAfter(after: Long, limit: Int): EventsPage = error("not used")
        override suspend fun eventsBefore(before: Long, limit: Int): EventsPage = EventsPage()
        override suspend fun reminders(): RemindersResponse = error("not used")
        override suspend fun snooze(id: Long, body: SnoozeRequest): OkResponse = error("not used")
        override suspend fun cancelReminder(id: Long): OkResponse = error("not used")
        override suspend fun devices(): DevicesResponse = error("not used")
        override suspend fun revoke(id: Long): OkResponse = error("not used")
    }

    private val recorder = object : Recorder {
        var file: File? = null
        override fun start(file: File) { file.writeBytes(byteArrayOf(1, 2, 3)); this.file = file }
        override fun stop(): Pair<File, Long>? = file?.let { it to 2_000L }.also { file = null }
        override fun cancel() { file?.delete(); file = null }
    }

    private fun viewModel(media: ChatMedia? = null) = ChatViewModel(
        observe = { items }, enqueue = { }, retrySend = { }, sendTyping = { }, eventCount = { 0 }, minId = { null },
        insertNew = { it }, api = { Api { version } }, unpaired = { },
        askedNotifications = { true }, markAskedNotifications = { },             // the notification question is not this test's
        herTyping = MutableStateFlow(false), connection = MutableStateFlow(ConnState.CONNECTED),
        clock = { System.nanoTime() / 1_000_000 },
        newClientId = { "c-${++ids}" },
        enqueueWith = { _, attachments, _ -> queued += attachments },
        context = app, outgoing = OutgoingFiles(app), recorder = recorder, media = media, io = Dispatchers.Unconfined,
    )

    /** Stands in for the activity's registry: counts what the screen asks the system for, and can answer it. */
    private class Registry : ActivityResultRegistry() {
        val asked = mutableListOf<Any?>()
        val codes = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked += input; codes += requestCode
        }
    }

    private val registry = Registry()
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    private fun show(vm: ChatViewModel = viewModel()) = compose.setContent {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            PalliTheme { ChatScreen({}, {}, vm) }
        }
    }

    private fun attach(item: String) {
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()
    }

    private val mic get() = compose.onNodeWithContentDescription("Hold to record a voice note")
    private fun hold() = mic.performTouchInput { down(center); advanceEventTime(1_500); up() }

    // ---- the attach menu ----

    @Test fun `Photo opens the photo picker and File the document picker`() {
        show()
        attach("Photo")
        attach("File")
        assertTrue(registry.asked[0] is PickVisualMediaRequest)
        assertEquals(listOf("*/*"), (registry.asked[1] as Array<*>).toList())
    }

    @Test fun `Camera asks for the camera first, and a no is said plainly`() {
        show()
        attach("Camera")
        assertEquals(listOf<Any?>(Manifest.permission.CAMERA), registry.asked)

        compose.runOnUiThread { registry.dispatchResult(registry.codes.single(), false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(ChatViewModel.CAMERA_REFUSED).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `with the camera allowed, the camera app writes into the app's own camera folder, and its photo is sent`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        show()
        attach("Camera")
        val uri = registry.asked.single() as Uri
        assertEquals("${app.packageName}.files", uri.authority)
        assertTrue(uri.toString(), uri.path!!.startsWith("/camera/"))

        val shot = File(File(app.cacheDir, "camera"), uri.lastPathSegment!!)
        shot.writeBytes(ByteArray(64))                                 // the camera app takes the picture
        compose.runOnUiThread { registry.dispatchResult(registry.codes.single(), true) }
        compose.waitForIdle()

        val photo = queued.single().single()
        assertEquals("image" to "photo.jpg", photo.kind to photo.name)
        assertFalse(shot.exists())
    }

    @Test fun `a picture the camera did not take sends nothing and leaves nothing behind`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        show()
        attach("Camera")
        val shot = File(File(app.cacheDir, "camera"), (registry.asked.single() as Uri).lastPathSegment!!)
        shot.writeBytes(ByteArray(0))
        compose.runOnUiThread { registry.dispatchResult(registry.codes.single(), false) }
        compose.waitForIdle()
        assertTrue(queued.isEmpty())
        assertFalse(shot.exists())
    }

    // ---- the microphone ----

    @Test fun `holding the microphone without the permission asks for it and records nothing`() {
        show()
        hold()
        compose.waitForIdle()
        assertEquals(listOf<Any?>(Manifest.permission.RECORD_AUDIO), registry.asked)
        assertTrue(queued.isEmpty())
    }

    @Test fun `with the microphone allowed, a hold records a voice note and letting go sends it`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        hold()
        compose.waitForIdle()
        val note = queued.single().single()
        assertEquals(Triple("voice", "audio/mp4", 2_000L), Triple(note.kind, note.mime, note.durationMs))
    }

    // ---- another api version ----

    @Test fun `another api version switches off the attach menu and the microphone as well as the words`() {
        version = 2
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("different versions", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Attach").assertIsNotEnabled()
        mic.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        hold()
        compose.waitForIdle()
        assertTrue(registry.asked.isEmpty())
        assertTrue(queued.isEmpty())
    }

    // ---- her files ----

    /**
     * A tap on something inside her bubble. Her bubble's shape has one sharp corner, so its outline is a Path, and
     * Robolectric's legacy graphics cannot hit-test a Path: an injected touch never lands there (on a phone it does).
     * The click action runs the same onClick.
     */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun media(saved: (String) -> String): ChatMedia {
        server.start()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("c.preferences_pb")
        }
        val sessions = SessionStore(ServerPrefs(store), object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(blob: String) = blob
        })
        runBlocking { sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_screen", 1, "Phone")) }
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        return ChatMedia(ImageLoader.Builder(app).okHttpClient(client).build(), VoicePlayer(app),
            FileCache(app, client, sessions), session = { sessions.current },
            saveFile = { _, name, _ -> Downloads.Saved(Uri.parse("content://media/external/downloads/1"), saved(name)) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @Test fun `tapping her file saves it, and a line over the composer says where it went`() {
        server.enqueue(MockResponse().setBody("%PDF"))
        items.value = listOf(TimelineItem.HerFile("e1", FileRef("f_bill", "Bill.pdf", "application/pdf", 4)))
        show(viewModel(media { "Download/PaLLi/$it" }))
        compose.onNodeWithText("Bill.pdf").assertIsDisplayed().tap()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Saved to Download/PaLLi/Bill.pdf").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Bearer pmd_screen", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `her file that PaLLi no longer has says so`() {
        server.enqueue(MockResponse().setResponseCode(404))
        items.value = listOf(TimelineItem.HerFile("e1", FileRef("f_old", "Old.pdf", "application/pdf")))
        show(viewModel(media { error("not reached") }))
        compose.onNodeWithText("Old.pdf").tap()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(ChatMedia.GONE).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `her voice note PaLLi no longer has says so in its bubble`() {
        server.enqueue(MockResponse().setResponseCode(404))
        items.value = listOf(TimelineItem.HerVoice("e1", FileRef("f_note", "note.ogg", "audio/ogg", durationS = 75.0), "hello"))
        show(viewModel(media { error("not reached") }))
        compose.onNodeWithText("1:15").assertIsDisplayed()
        compose.onNodeWithText("Show what she said").tap()
        compose.onNodeWithText("hello").assertIsDisplayed()

        compose.onNodeWithContentDescription("Play her voice note").tap()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(ChatMedia.GONE).fetchSemanticsNodes().isNotEmpty() }
    }
}
