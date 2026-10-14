package com.palli.mobile.ui.chat

import android.Manifest
import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
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
import com.palli.mobile.domain.timeline.SendStatus
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.ConnState
import com.palli.mobile.ui.theme.PalliTheme
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The chat screen on Robolectric, over a ChatViewModel whose repositories, stream and API are stand-ins. */
@RunWith(RobolectricTestRunner::class)
class ChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val items = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val herTyping = MutableStateFlow(false)
    private val connection = MutableStateFlow(ConnState.CONNECTED)
    private val queued = mutableListOf<String>()
    private val retried = mutableListOf<String>()
    private val typed = mutableListOf<Long>()
    private var door = "open"
    private var version = 1
    private var enqueueFailure: Throwable? = null
    private var retryFailure: Throwable? = null
    /** What the phone remembers between starts: whether the notification question has been put. */
    private var askedFlag = false

    private class Api(val me: () -> MeResponse) : PalliApi {
        override suspend fun me() = me.invoke()
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

    private fun viewModel(): ChatViewModel {
        val api = Api { MeResponse(DeviceRef(1, "Phone"), door, "", version) }
        return ChatViewModel(
            observe = { items },
            enqueue = { enqueueFailure?.let { e -> throw e }; queued += it },
            retrySend = { retryFailure?.let { e -> throw e }; retried += it },
            sendTyping = { typed += 1L },
            eventCount = { 0 },
            minId = { null },
            insertNew = { it },
            api = { api },
            unpaired = { },
            askedNotifications = { askedFlag },
            markAskedNotifications = { askedFlag = true },
            herTyping = herTyping,
            connection = connection,
            clock = { System.nanoTime() / 1_000_000 },
        )
    }

    /** Stands in for the activity's registry, so what the screen asks the system for can be counted. */
    private class CountingRegistry : ActivityResultRegistry() {
        val asked = mutableListOf<Any?>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked += input
        }
    }

    private val registry = CountingRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    private fun screen(vm: ChatViewModel, onReminders: () -> Unit = {}, onSettings: () -> Unit = {}): @Composable () -> Unit = {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            PalliTheme { ChatScreen(onReminders, onSettings, vm) }
        }
    }

    private fun show(vm: ChatViewModel = viewModel(), onReminders: () -> Unit = {}, onSettings: () -> Unit = {}) =
        compose.setContent(screen(vm, onReminders, onSettings))

    private fun hers(id: Int, text: String, reminder: Boolean = false) = TimelineItem.Hers("e$id", text, reminder, id.toDouble())
    private fun mine(key: String, text: String, status: SendStatus? = null, failure: String? = null) =
        TimelineItem.Mine(key, text, null, emptyList(), emptyList(), status, failure)

    // ---- what is on the screen ----

    @Test fun `her bubbles and yours are shown, her reminder with its bell, a notice as a line`() {
        items.value = listOf(
            mine("e1", "are you there"),
            hers(2, "I am here"),
            hers(3, "Take the bins out", reminder = true),
            TimelineItem.Notice("e4", "She couldn't answer that one.", trouble = true),
        )
        show()

        compose.onNodeWithText("are you there").assertIsDisplayed()
        compose.onNodeWithText("I am here").assertIsDisplayed()
        compose.onNodeWithText("Take the bins out").assertIsDisplayed()
        compose.onNodeWithText("Reminder").assertIsDisplayed()
        compose.onNodeWithText("She couldn't answer that one.").assertIsDisplayed()
    }

    @Test fun `the connection line follows the stream, in plain words`() {
        show()
        compose.onNodeWithText("connected").assertIsDisplayed()
        connection.value = ConnState.WAITING
        compose.onNodeWithText("offline — retrying").assertIsDisplayed()
    }

    @Test fun `her typing bubble is there while she types and gone when she stops`() {
        show()
        compose.onNodeWithContentDescription("PaLLi is typing").assertDoesNotExist()
        herTyping.value = true
        compose.onNodeWithContentDescription("PaLLi is typing").assertIsDisplayed()
        herTyping.value = false
        compose.onNodeWithContentDescription("PaLLi is typing").assertDoesNotExist()
    }

    @Test fun `the door banner shows when the door is not open`() {
        door = "closed"
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("shut", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PaLLi's phone door is shut right now. Your messages will wait here.").assertIsDisplayed()
    }

    @Test fun `no banner when the door is open`() {
        show()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("door", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the Reminders and Settings buttons are in the top bar`() {
        var opened = ""
        show(onReminders = { opened += "r" }, onSettings = { opened += "s" })
        compose.onNodeWithContentDescription("Reminders").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertEquals("rs", opened)
    }

    @Test fun `your bubbles are on the right and hers on the left`() {
        items.value = listOf(mine("e1", "mine"), hers(2, "hers"))
        show()
        val width = compose.onRoot().getUnclippedBoundsInRoot().let { it.right.value - it.left.value }
        val his = compose.onNodeWithText("mine").getUnclippedBoundsInRoot()
        val hers = compose.onNodeWithText("hers").getUnclippedBoundsInRoot()

        assertTrue("his starts in the right half (${his.left} of $width)", his.left.value > width / 2)
        assertTrue("hers ends in the left half (${hers.right} of $width)", hers.right.value < width / 2)
    }

    @Test fun `a short chat sits at the bottom, just above the composer, newest lowest`() {
        items.value = listOf(hers(1, "older"), mine("e2", "newest"))
        show()
        compose.waitForIdle()
        val list = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val newest = compose.onNodeWithText("newest").getUnclippedBoundsInRoot()
        val older = compose.onNodeWithText("older").getUnclippedBoundsInRoot()

        assertTrue("the newest is near the list's bottom (${newest.bottom} vs ${list.bottom})",
            list.bottom.value - newest.bottom.value < 40f)
        assertTrue("and well below its middle (${newest.top} vs ${list.top}..${list.bottom})",
            newest.top.value > (list.top.value + list.bottom.value) / 2)
        assertTrue("the older sits above it", older.bottom.value <= newest.top.value)
    }

    @Test fun `another api version shows the update banner and switches the composer off`() {
        version = 2
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("different versions", substring = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Message PaLLi").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun `a shut door leaves the composer on, because those messages wait`() {
        door = "closed"
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("shut", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Message PaLLi").assertIsEnabled()
    }

    // ---- following the conversation ----

    private fun longChat(n: Int = 40) = (1..n).map { hers(it, "line $it") }

    @Test fun `a new bubble from her appears at the bottom when you were already there`() {
        items.value = longChat()
        show()
        compose.onNodeWithText("line 40").assertIsDisplayed()

        items.value = longChat(41)
        compose.waitForIdle()
        compose.onNodeWithText("line 41").assertIsDisplayed()
    }

    @Test fun `three bubbles arriving at once are followed to the newest when you were at the bottom`() {
        items.value = longChat()
        show()
        compose.onNodeWithText("line 40").assertIsDisplayed()

        items.value = longChat(43)
        compose.waitForIdle()
        compose.onNodeWithText("line 43").assertIsDisplayed()
    }

    @Test fun `six bubbles arriving at once are followed to the newest when you were at the bottom`() {
        items.value = longChat()
        show()
        compose.onNodeWithText("line 40").assertIsDisplayed()

        items.value = longChat(46)
        compose.waitForIdle()
        compose.onNodeWithText("line 46").assertIsDisplayed()
    }

    @Test fun `a second arrival while the first is still being followed down is followed too`() {
        items.value = longChat()
        show()
        compose.mainClock.autoAdvance = false
        items.value = longChat(43)
        compose.mainClock.advanceTimeByFrame()                        // the list re-anchors, the follow begins
        items.value = longChat(46)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("line 46").assertIsDisplayed()
    }

    @Test fun `three or six bubbles arriving at once do not pull you away from reading back`() {
        items.value = longChat()
        show()
        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.waitForIdle()

        items.value = longChat(43)
        compose.waitForIdle()
        compose.onNodeWithText("line 43").assertDoesNotExist()
        items.value = longChat(49)                                    // and six more
        compose.waitForIdle()
        compose.onNodeWithText("line 49").assertDoesNotExist()
    }

    @Test fun `a new bubble from her does not pull you away from reading back`() {
        items.value = longChat()
        show()
        compose.onNode(hasScrollAction()).performScrollToIndex(30)        // well up her log
        compose.waitForIdle()

        items.value = longChat(41)
        compose.waitForIdle()
        compose.onNodeWithText("line 41").assertDoesNotExist()           // not even composed: it is far below the viewport
    }

    @Test fun `what you send shows at the bottom even when you were reading back`() {
        items.value = longChat()
        show()
        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.waitForIdle()

        items.value = longChat() + mine("pnew", "my reply", SendStatus.SENDING)
        compose.waitForIdle()
        compose.onNodeWithText("my reply").assertIsDisplayed()
    }

    @Test fun `her typing that stops while it is being followed down leaves nothing behind to yank you later`() {
        items.value = longChat()
        show()
        compose.onNodeWithText("line 40").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        herTyping.value = true
        compose.mainClock.advanceTimeByFrame()                        // her typing lands below; the follow begins
        compose.mainClock.advanceTimeByFrame()
        herTyping.value = false
        compose.mainClock.advanceTimeByFrame()                        // and stops mid-follow: that follow is replaced
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNode(hasScrollAction()).performScrollToIndex(30)    // you go back to read
        compose.waitForIdle()
        items.value = longChat(41)
        compose.waitForIdle()
        compose.onNodeWithText("line 41").assertDoesNotExist()
    }

    @Test fun `her typing bubble comes into view when you are at the bottom`() {
        items.value = longChat()
        show()
        herTyping.value = true
        compose.waitForIdle()
        val typing = compose.onNodeWithContentDescription("PaLLi is typing").getUnclippedBoundsInRoot()
        val composer = compose.onNodeWithText("Message PaLLi").getUnclippedBoundsInRoot()
        assertTrue("it is in the list, above the composer (${typing.bottom} vs ${composer.top})", typing.bottom <= composer.top)
    }

    // ---- your sends ----

    @Test fun `a send shows its state, and a failed one says why and retries on a tap`() {
        items.value = listOf(
            mine("pa", "waiting", SendStatus.SENDING),
            mine("pb", "arrived", SendStatus.ACCEPTED),
            mine("pc", "broken", SendStatus.FAILED, failure = "PaLLi's door is shut"),
        )
        show()

        compose.onNodeWithContentDescription("sending").assertIsDisplayed()
        compose.onNodeWithContentDescription("sent").assertIsDisplayed()
        compose.onNodeWithText("PaLLi's door is shut — tap to retry").performClick()
        assertEquals(listOf("c"), retried)
    }

    @Test fun `a message that could not be queued says so over the composer, and is back in the box`() {
        enqueueFailure = IllegalStateException("disk full")
        show()
        compose.onNodeWithText("Message PaLLi").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(ChatViewModel.SEND_FAILED_KEPT).assertIsDisplayed()
        compose.onNodeWithText("hello").assertIsDisplayed()              // the text is in the box again
    }

    @Test fun `a retry that could not be started says so`() {
        retryFailure = IllegalStateException("work manager gone")
        items.value = listOf(mine("pc", "broken", SendStatus.FAILED, failure = "PaLLi's door is shut"))
        show()
        compose.onNodeWithText("PaLLi's door is shut — tap to retry").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(ChatViewModel.RETRY_FAILED).assertIsDisplayed()
    }

    @Test fun `a failed send with no reason still says it was not sent`() {
        items.value = listOf(mine("pc", "broken", SendStatus.FAILED))
        show()
        compose.onNodeWithText("Not sent — tap to retry").assertIsDisplayed()
    }

    // ---- the composer ----

    @Test fun `typing then pressing send queues the text and empties the composer`() {
        show()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        compose.onNodeWithText("Message PaLLi").performTextInput("  hello  ")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()

        assertEquals(listOf("hello"), queued)
        compose.onNodeWithText("Message PaLLi").assertIsDisplayed()          // the hint is back: the composer is empty
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertEquals(1, typed.size)
    }

    // ---- notifications ----

    @Test fun `the first time the chat opens it asks to post notifications`() {
        show()
        compose.waitForIdle()
        assertEquals(listOf<Any?>(Manifest.permission.POST_NOTIFICATIONS), registry.asked)
    }

    @Test fun `it asks once ever, not again when the screen is rebuilt or the app is started afresh, even after a no`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { screen(remember { viewModel() })() }   // a rebuilt screen has a new view model, as a new process does
        compose.waitForIdle()
        assertEquals(1, registry.asked.size)
        assertEquals("and the phone wrote it down", true, askedFlag)

        restoration.emulateSavedInstanceStateRestore()                  // the answer was "no": nothing was granted
        compose.waitForIdle()
        assertEquals("the screen was rebuilt, and did not ask again", 1, registry.asked.size)
    }

    @Test fun `a phone that has already been asked is not asked at the first sight of the chat`() {
        askedFlag = true
        show()
        compose.waitForIdle()
        assertTrue(registry.asked.isEmpty())
    }

    @Test fun `it does not ask when notifications are already allowed`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        compose.waitForIdle()
        assertTrue(registry.asked.isEmpty())
    }

    // ---- insets ----

    private fun dispatchInsets(nav: Int, ime: Int, status: Int = 0) {
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            val composeView = content.getChildAt(0) as ComposeView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, status, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeView, insets)
        }
        compose.waitForIdle()
    }

    private fun rootHeightDp(): Float = compose.onRoot().getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    @Test fun `the composer sits above the navigation bar, and above the keyboard when it is up`() {
        show()
        compose.waitForIdle()
        val full = rootHeightDp()
        val resting = compose.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot().bottom.value
        assertTrue("with no bars the composer is within the screen", resting <= full)

        dispatchInsets(nav = 48, ime = 0)
        val aboveNav = compose.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot().bottom.value
        assertTrue("clear of the navigation bar ($aboveNav vs ${full - 48})", aboveNav <= full - 48)

        dispatchInsets(nav = 48, ime = 300)
        val aboveKeyboard = compose.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot().bottom.value
        assertTrue("clear of the keyboard ($aboveKeyboard vs ${full - 300})", aboveKeyboard <= full - 300)
        assertTrue("and not floating above it by the navigation bar's height as well ($aboveKeyboard)", aboveKeyboard > full - 300 - 48)
    }

    @Test fun `the top bar sits below the status bar`() {
        show()
        compose.waitForIdle()
        dispatchInsets(nav = 0, ime = 0, status = 24)
        val title = compose.onNodeWithText("PaLLi").getUnclippedBoundsInRoot().top.value
        assertTrue("the title starts below the 24 dp status bar ($title)", title >= 24f)
    }
}
