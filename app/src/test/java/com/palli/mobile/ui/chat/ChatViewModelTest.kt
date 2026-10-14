package com.palli.mobile.ui.chat

import com.palli.mobile.data.remote.AcceptedResponse
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.DeviceRef
import com.palli.mobile.data.remote.DevicesResponse
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.remote.EventsPage
import com.palli.mobile.data.remote.MeResponse
import com.palli.mobile.data.remote.OkResponse
import com.palli.mobile.data.remote.PairRequest
import com.palli.mobile.data.remote.PairResponse
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.RemindersResponse
import com.palli.mobile.data.remote.SnoozeRequest
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.ConnState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The chat's logic, over stand-ins for the repositories, the stream and the two calls it makes on PaLLi. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** Only `me` and `eventsBefore` are ever called by the chat. */
    private class FakeApi(
        var onMe: suspend () -> MeResponse = { error("no answer set") },
        var onBefore: suspend (Long) -> EventsPage = { error("no answer set") },
    ) : PalliApi {
        override suspend fun me() = onMe()
        override suspend fun eventsBefore(before: Long, limit: Int) = onBefore(before)
        override suspend fun pair(body: PairRequest): PairResponse = error("not used")
        override suspend fun send(parts: List<MultipartBody.Part>): AcceptedResponse = error("not used")
        override suspend fun eventsAfter(after: Long, limit: Int): EventsPage = error("not used")
        override suspend fun reminders(): RemindersResponse = error("not used")
        override suspend fun snooze(id: Long, body: SnoozeRequest): OkResponse = error("not used")
        override suspend fun cancelReminder(id: Long): OkResponse = error("not used")
        override suspend fun devices(): DevicesResponse = error("not used")
        override suspend fun revoke(id: Long): OkResponse = error("not used")
    }

    private val api = FakeApi().also { it.onMe = { me() } }
    private var hasSession = true
    private var now = 10_000L
    private val typingFrames = mutableListOf<Long>()
    private val queued = mutableListOf<String>()
    private val retried = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var removeFailure: Throwable? = null
    private val unpaired = mutableListOf<String?>()
    private val windows = mutableListOf<Int>()
    private val stored = mutableListOf<List<Long>>()
    private var enqueueFailure: Throwable? = null
    private var retryFailure: Throwable? = null
    private var insertFailure: Throwable? = null
    private var held: CompletableDeferred<Unit>? = null
    private var count = 0
    private var oldest: Long? = 100L
    private var asked = false
    private var askedFailure: Throwable? = null
    private var batteryAsked = false
    private var batteryAskedFailure: Throwable? = null
    private val herTyping = MutableStateFlow(false)
    private val connection = MutableStateFlow(ConnState.IDLE)

    private fun me(door: String = "open", version: Int = 1) = MeResponse(DeviceRef(1, "Phone"), door, "", version)
    private fun page(ids: List<Long>, more: Boolean) =
        EventsPage(ids.map { EventDto(it, EventKinds.MESSAGE, it.toDouble(), "t$it") }, more)

    private fun viewModel(observe: (Int) -> Flow<List<TimelineItem>> = { windows += it; flowOf(emptyList()) }) = ChatViewModel(
        observe = observe,
        enqueue = { text -> held?.await(); enqueueFailure?.let { throw it }; queued += text },
        retrySend = { id -> retryFailure?.let { throw it }; retried += id },
        removeSend = { id -> removeFailure?.let { throw it }; removed += id },
        sendTyping = { typingFrames += now },
        eventCount = { count },
        minId = { oldest },
        insertNew = { events -> insertFailure?.let { throw it }; stored += events.map { it.id }; events },
        api = { if (hasSession) api else null },
        unpaired = { reason -> unpaired += reason },
        askedNotifications = { askedFailure?.let { throw it }; asked },
        markAskedNotifications = { asked = true },
        askedBattery = { batteryAskedFailure?.let { throw it }; batteryAsked },
        saveAskedBattery = { batteryAsked = true },
        herTyping = herTyping,
        connection = connection,
        clock = { now },
    )

    // ---- typing ----

    @Test fun `typing sends one frame, then at most one every three seconds, and never for a blank draft`() {
        val vm = viewModel()
        vm.onDraft("   ")
        assertTrue(typingFrames.isEmpty())

        vm.onDraft("h")
        now += 1_000; vm.onDraft("he")
        now += 1_999; vm.onDraft("hel")                            // 2 999 ms after the first
        assertEquals(listOf(10_000L), typingFrames)

        now += 1; vm.onDraft("hell")                               // 3 000 ms
        assertEquals(listOf(10_000L, 13_000L), typingFrames)
        assertEquals("hell", vm.draft.value)
    }

    @Test fun `sending never types, so the only frames are the ones typing made`() {
        val vm = viewModel()
        vm.onDraft("hello")
        assertEquals(listOf(10_000L), typingFrames)

        now += 4_000                                                // well past the throttle, so a send that typed would be heard
        vm.send()
        assertEquals(listOf(10_000L), typingFrames)
        assertEquals(listOf("hello"), queued)

        now += 4_000
        vm.send()                                                   // nothing to send: still silent
        assertEquals(listOf(10_000L), typingFrames)
    }

    // ---- sending ----

    @Test fun `send trims, clears the draft and queues the text once`() {
        val vm = viewModel()
        vm.onDraft("  hello there \n")
        vm.send()
        vm.send()                                                  // the draft is empty now

        assertEquals(listOf("hello there"), queued)
        assertEquals("", vm.draft.value)
    }

    @Test fun `words of 65,536 bytes go, and 65,537 stay in the box and say to send them as a file`() {
        val vm = viewModel()
        vm.onDraft("a".repeat(65_536))
        vm.send()
        assertEquals(listOf("a".repeat(65_536)), queued)
        assertEquals("", vm.draft.value)

        vm.onDraft("a".repeat(65_537))
        vm.send()
        assertEquals(1, queued.size)                               // nothing more was queued
        assertEquals("a".repeat(65_537), vm.draft.value)           // and the words are still there
        assertEquals(ChatViewModel.TEXT_TOO_LONG, vm.notice.value)
    }

    @Test fun `the limit is in bytes, so words in another script are refused under 65,536 characters`() {
        val vm = viewModel()
        val cjk = "中".repeat(21_846)                              // 65,538 bytes in 21,846 characters
        vm.onDraft(cjk)
        vm.send()
        assertTrue(queued.isEmpty())
        assertEquals(cjk, vm.draft.value)
        assertEquals(ChatViewModel.TEXT_TOO_LONG, vm.notice.value)
    }

    @Test fun `a blank draft sends nothing and is left as it is`() {
        val vm = viewModel()
        vm.onDraft("   ")
        vm.send()
        assertTrue(queued.isEmpty())
        assertEquals("   ", vm.draft.value)
    }

    @Test fun `a message that could not be queued comes back to the composer, and says so`() {
        enqueueFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.onDraft("keep me")
        vm.send()
        assertEquals("keep me", vm.draft.value)
        assertTrue(queued.isEmpty())
        assertEquals(ChatViewModel.SEND_FAILED_KEPT, vm.notice.value)
    }

    @Test fun `a message that could not be queued does not overwrite what was typed since, and says it was not kept`() {
        enqueueFailure = IllegalStateException("disk full")
        held = CompletableDeferred()
        val vm = viewModel()
        vm.onDraft("first")
        vm.send()
        assertEquals("", vm.draft.value)
        assertNull(vm.notice.value)
        vm.onDraft("second")

        held!!.complete(Unit)                                      // now the first one fails
        assertEquals("second", vm.draft.value)
        assertEquals(ChatViewModel.SEND_FAILED_LOST, vm.notice.value)
    }

    @Test fun `a send that went through says nothing and is not given back`() {
        val vm = viewModel()
        vm.onDraft("fine")
        vm.send()
        assertEquals("", vm.draft.value)
        assertNull(vm.notice.value)
    }

    @Test fun `a notice is forgotten once shown, unless a newer one has replaced it`() {
        enqueueFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.onDraft("a"); vm.send()
        vm.noticeShown("something else entirely")
        assertEquals(ChatViewModel.SEND_FAILED_KEPT, vm.notice.value)

        vm.noticeShown(ChatViewModel.SEND_FAILED_KEPT)
        assertNull(vm.notice.value)
    }

    // ---- retry ----

    @Test fun `retry takes the client id off a pending key, and ignores a key that is not a send`() {
        val vm = viewModel()
        vm.retry("p3f1c-0b")
        vm.retry("e42")
        assertEquals(listOf("3f1c-0b"), retried)
    }

    @Test fun `a retry that throws does not take the app down, and says so`() {
        retryFailure = IllegalStateException("work manager gone")
        val vm = viewModel()
        vm.retry("pabc")
        assertTrue(retried.isEmpty())
        assertEquals(ChatViewModel.RETRY_FAILED, vm.notice.value)
    }

    @Test fun `remove takes the client id off a pending key, and ignores a key that is not a send`() {
        val vm = viewModel()
        vm.remove("p3f1c-0b")
        vm.remove("e42")
        assertEquals(listOf("3f1c-0b"), removed)
    }

    @Test fun `a remove that throws does not take the app down, and says so`() {
        removeFailure = IllegalStateException("disk full")
        val vm = viewModel()
        vm.remove("pabc")
        assertTrue(removed.isEmpty())
        assertEquals(ChatViewModel.REMOVE_FAILED, vm.notice.value)
    }

    // ---- scrollback ----

    @Test fun `scrollback asks PaLLi for the page before the oldest event held, stores it, and widens the window by it`() = runTest {
        count = 100; oldest = 100L
        var asked = emptyList<Long>()
        api.onBefore = { before -> asked = asked + before; page((51L..99L).toList().reversed(), more = true) }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect { } }
        assertEquals(listOf(200), windows)

        vm.loadOlder()

        assertEquals(listOf(100L), asked)
        assertEquals(listOf((51L..99L).toList().reversed()), stored)
        assertEquals(listOf(200, 249), windows)                    // 49 events came back
    }

    @Test fun `no further page is asked for once PaLLi says there is no more`() {
        var calls = 0
        api.onBefore = { calls++; page(listOf(99L, 98L), more = false) }
        val vm = viewModel()

        vm.loadOlder()
        vm.loadOlder()
        vm.loadOlder()
        assertEquals(1, calls)
    }

    @Test fun `while a page is on its way another is not asked for`() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        api.onBefore = { calls++; gate.await(); page(listOf(99L), more = true) }
        val vm = viewModel()

        vm.loadOlder()
        vm.loadOlder()
        assertEquals(1, calls)

        gate.complete(Unit)
        vm.loadOlder()                                             // done now: the next page may be asked for
        assertEquals(2, calls)
    }

    @Test fun `with more held on the phone than the window shows, scrollback widens the window and does not touch the network`() = runTest {
        count = 480
        api.onBefore = { error("the network must not be asked") }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect { } }

        repeat(6) { vm.loadOlder() }                               // 200 → 250 → … → 480+ in steps of 50
        assertEquals(listOf(200, 250, 300, 350, 400, 450, 500), windows)

        api.onBefore = { page(listOf(99L), more = false) }
        vm.loadOlder()                                             // the window now covers all 480: now it asks
        assertEquals(listOf(200, 250, 300, 350, 400, 450, 500, 501), windows)
    }

    @Test fun `hearing there is no more does not hide events that live traffic later pushed out of the window`() = runTest {
        count = 10
        api.onBefore = { page(emptyList(), more = false) }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.items.collect { } }
        vm.loadOlder()                                             // asks, hears "no more"

        count = 400                                                // live events arrived; the oldest are out of the window
        vm.loadOlder()
        assertEquals(listOf(200, 250), windows)
    }

    @Test fun `nothing held means nothing to page from, and nobody paired means nothing to ask`() {
        api.onBefore = { error("the network must not be asked") }
        oldest = null
        viewModel().loadOlder()
        oldest = 100L
        hasSession = false
        viewModel().loadOlder()
        assertTrue(stored.isEmpty())
    }

    @Test fun `a page that cannot be fetched can be tried again, and says why once`() {
        var calls = 0
        api.onBefore = { calls++; if (calls == 1) throw IOException("offline") else page(listOf(99L), more = true) }
        val vm = viewModel()

        vm.loadOlder()
        assertTrue(stored.isEmpty())
        assertTrue(unpaired.isEmpty())
        assertEquals(ChatViewModel.OLDER_UNREACHABLE, vm.notice.value)
        vm.loadOlder()
        assertEquals(listOf(listOf(99L)), stored)
    }

    @Test fun `scrolling near the end while PaLLi cannot be reached says so once, not at every try, and again after a success`() {
        var failing = true
        api.onBefore = { if (failing) throw IOException("offline") else page(listOf(99L), more = true) }
        val vm = viewModel()

        vm.loadOlder()
        assertEquals(ChatViewModel.OLDER_UNREACHABLE, vm.notice.value)
        vm.noticeShown(ChatViewModel.OLDER_UNREACHABLE)
        repeat(3) { vm.loadOlder() }
        assertNull("the same trouble is not told again", vm.notice.value)

        failing = false
        vm.loadOlder()                                             // a page arrives: the run of failures is over
        failing = true
        vm.loadOlder()
        assertEquals(ChatViewModel.OLDER_UNREACHABLE, vm.notice.value)
    }

    @Test fun `a page PaLLi answers badly says the plain thing`() {
        api.onBefore = { throw ApiError.Server(500, "boom") }
        val vm = viewModel()
        vm.loadOlder()
        assertEquals(ChatViewModel.OLDER_FAILED, vm.notice.value)
    }

    @Test fun `a revoked phone found out by scrollback is unpaired, with no notice of its own`() {
        api.onBefore = { throw ApiError.Unauthorized("This phone is not paired.") }
        val vm = viewModel()
        vm.loadOlder()
        assertEquals(listOf<String?>("This phone was unpaired."), unpaired)
        assertNull(vm.notice.value)
    }

    @Test fun `a page that cannot be stored does not take the app down, and scrollback still works afterwards`() {
        api.onBefore = { page(listOf(99L), more = true) }
        insertFailure = IllegalStateException("database is locked")
        val vm = viewModel()
        vm.loadOlder()
        assertTrue(stored.isEmpty())
        assertEquals(ChatViewModel.OLDER_FAILED, vm.notice.value)

        insertFailure = null
        vm.loadOlder()
        assertEquals(listOf(listOf(99L)), stored)
    }

    // ---- the door ----

    @Test fun `the banner says why the door is not open, and nothing when it is`() {
        api.onMe = { me("closed") }
        val vm = viewModel()
        assertTrue(vm.banner.value!!.contains("shut"))

        api.onMe = { me("open") }
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED                     // the next check finds it open
        assertNull(vm.banner.value)
    }

    @Test fun `a server of another version shows the update banner`() {
        api.onMe = { me("open", version = 2) }
        assertTrue(viewModel().banner.value!!.contains("different versions"))
    }

    @Test fun `starting already connected asks after the door once, not twice, even while the first answer is still out`() {
        val gate = CompletableDeferred<Unit>()
        var asks = 0
        api.onMe = { asks++; gate.await(); me("closed") }
        connection.value = ConnState.CONNECTED
        val vm = viewModel()
        assertEquals("the start check, and no second one for the connect it started in", 1, asks)

        gate.complete(Unit)
        assertEquals(1, asks)
        assertTrue(vm.banner.value!!.contains("shut"))
    }

    @Test fun `each connect asks after the door again, but never within thirty seconds of an answer, and a drop alone asks nothing`() {
        var asks = 0
        api.onMe = { asks++; me("open") }
        val vm = viewModel()
        assertEquals(1, asks)                                      // at the start

        connection.value = ConnState.CONNECTING
        connection.value = ConnState.WAITING
        assertEquals(1, asks)
        connection.value = ConnState.CONNECTED                     // 0 s after the answer
        assertEquals(1, asks)

        now += ChatViewModel.DOOR_RECHECK_MS - 1
        connection.value = ConnState.WAITING
        connection.value = ConnState.CONNECTED                     // 29.999 s: a flapping link
        assertEquals(1, asks)

        now += 1
        connection.value = ConnState.WAITING
        connection.value = ConnState.CONNECTED                     // 30 s
        assertEquals(2, asks)

        connection.value = ConnState.WAITING
        connection.value = ConnState.CONNECTED                     // and the floor runs from that answer
        assertEquals(2, asks)
        assertNull(vm.banner.value)
    }

    @Test fun `a phone that started offline asks as soon as it connects, whatever the floor`() {
        var asks = 0
        api.onMe = { asks++; throw IOException("offline") }
        val vm = viewModel()
        assertEquals(1, asks)
        assertNull(vm.banner.value)

        api.onMe = { asks++; me("closed") }
        connection.value = ConnState.CONNECTED                     // no answer yet, so nothing to floor against
        assertEquals(2, asks)
        assertTrue(vm.banner.value!!.contains("shut"))
    }

    @Test fun `a newer door check replaces an older one still out, whose late answer is thrown away`() {
        val older = CompletableDeferred<Unit>()
        var asks = 0
        api.onMe = { if (asks++ == 0) { older.await(); me("closed") } else me("open") }
        val vm = viewModel()                                       // the first check is out, unanswered
        assertEquals(1, asks)

        connection.value = ConnState.CONNECTED                     // a connect: a second check, which answers at once
        assertEquals(2, asks)
        assertNull(vm.banner.value)

        older.complete(Unit)                                       // the first limps in afterwards with the old news
        assertNull("the older answer must not overwrite the newer", vm.banner.value)
    }

    @Test fun `a door that cannot be reached leaves the banner as it was`() {
        api.onMe = { me("closed") }
        val vm = viewModel()
        api.onMe = { throw IOException("offline") }
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED
        assertTrue(vm.banner.value!!.contains("shut"))
        assertTrue(unpaired.isEmpty())
    }

    @Test fun `a revoked phone found out by the door check is unpaired`() {
        api.onMe = { throw ApiError.Unauthorized("This phone is not paired.") }
        viewModel()
        assertEquals(listOf<String?>("This phone was unpaired."), unpaired)
    }

    @Test fun `nobody paired means the door is not asked after`() {
        api.onMe = { error("the network must not be asked") }
        hasSession = false
        assertNull(viewModel().banner.value)
    }

    // ---- another api version: the app will not chat ----

    @Test fun `another api version blocks sending and typing, and keeps what was in the box`() {
        val vm = viewModel()
        vm.onDraft("before")                                       // the check passed: typing works
        assertEquals(1, typingFrames.size)
        assertEquals(false, vm.sendingBlocked.value)

        api.onMe = { me("open", version = 2) }
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED
        assertEquals(true, vm.sendingBlocked.value)
        assertTrue(vm.banner.value!!.contains("different versions"))

        now += 10_000
        vm.onDraft("after")                                        // refused: no text taken, no frame sent
        assertEquals("before", vm.draft.value)
        assertEquals(1, typingFrames.size)
        vm.send()
        assertTrue(queued.isEmpty())
        assertEquals("before", vm.draft.value)
        assertNull(vm.notice.value)
    }

    @Test fun `another api version blocks retrying a pending send`() {
        api.onMe = { me("open", version = 2) }
        val vm = viewModel()
        vm.retry("pabc")
        assertTrue(retried.isEmpty())
        assertEquals(true, vm.sendingBlocked.value)
    }

    @Test fun `once PaLLi and the app speak the same version again, sending is back`() {
        api.onMe = { me("open", version = 2) }
        val vm = viewModel()
        assertEquals(true, vm.sendingBlocked.value)

        api.onMe = { me("open", version = 1) }
        now += ChatViewModel.DOOR_RECHECK_MS
        connection.value = ConnState.CONNECTED
        assertEquals(false, vm.sendingBlocked.value)
        vm.onDraft("hello"); vm.send(); vm.retry("pabc")
        assertEquals(listOf("hello"), queued)
        assertEquals(listOf("abc"), retried)
    }

    @Test fun `a shut door or a missing brain does not block sending, since those messages wait`() {
        api.onMe = { me("closed") }
        val vm = viewModel()
        vm.onDraft("wait for me"); vm.send()
        assertEquals(false, vm.sendingBlocked.value)
        assertEquals(listOf("wait for me"), queued)
    }

    // ---- the notification question ----

    @Test fun `the notification question is put once, and not again after it has been marked`() = runTest {
        val vm = viewModel()
        assertEquals(true, vm.shouldAskNotifications())
        vm.notificationsAsked()
        assertEquals(false, vm.shouldAskNotifications())
        assertEquals(false, viewModel().shouldAskNotifications())    // a new screen, a new process: the phone remembers
    }

    @Test fun `if the phone cannot say whether it has asked, it does not ask`() = runTest {
        askedFailure = java.io.IOException("datastore unreadable")
        assertEquals(false, viewModel().shouldAskNotifications())
    }

    // ---- the battery question ----

    @Test fun `the battery question is put once, and only while Android is limiting the app`() = runTest {
        val vm = viewModel()
        assertEquals("not limited: nothing to ask", false, vm.shouldAskBattery(limited = false))
        assertEquals(false, batteryAsked)                              // and not asking did not use up the one ask
        assertEquals(true, vm.shouldAskBattery(limited = true))

        vm.markAskedBattery()                                          // he said yes, no, or dismissed it
        assertEquals(true, batteryAsked)
        assertEquals(false, vm.shouldAskBattery(limited = true))
        assertEquals(false, viewModel().shouldAskBattery(limited = true))   // a new screen, a new process: the phone remembers
    }

    @Test fun `if the phone cannot say whether it has asked about the battery, it does not ask`() = runTest {
        batteryAskedFailure = java.io.IOException("datastore unreadable")
        assertEquals(false, viewModel().shouldAskBattery(limited = true))
    }

    // ---- what the stream says ----

    @Test fun `her typing and the connection are the stream's, as they change`() {
        val vm = viewModel()
        assertEquals(false, vm.herTyping.value)
        herTyping.value = true
        connection.value = ConnState.WAITING
        assertEquals(true, vm.herTyping.value)
        assertEquals(ConnState.WAITING, vm.connection.value)
    }
}
