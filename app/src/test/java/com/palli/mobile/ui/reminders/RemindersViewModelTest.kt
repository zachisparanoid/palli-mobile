package com.palli.mobile.ui.reminders

import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ReminderDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The reminders panel's logic, over stand-ins for the three calls it makes on PaLLi. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemindersViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val pills = ReminderDto(id = 7, text = "take the pills", dueAt = 1_000.0)
    private val call = ReminderDto(id = 8, text = "call Sam back", dueAt = 2_000.0)

    /** What PaLLi currently holds, and what has been asked of her. */
    private var open = listOf(pills, call)
    private var listAnswer: (() -> Result<List<ReminderDto>>)? = null
    private var snoozeAnswer: (Long) -> Result<Unit> = { Result.success(Unit) }
    private var cancelAnswer: (Long) -> Result<Unit> = { id -> open = open.filter { it.id != id }; Result.success(Unit) }
    private var listCalls = 0
    private val snoozed = mutableListOf<Pair<Long, Int>>()
    private val cancelled = mutableListOf<Long>()

    private fun viewModel() = RemindersViewModel(
        fetch = { listCalls++; listAnswer?.invoke() ?: Result.success(open) },
        snoozeOnServer = { id, minutes -> snoozed += id to minutes; snoozeAnswer(id) },
        cancelOnServer = { id -> cancelled += id; cancelAnswer(id) },
    )

    private val gone = Result.failure<Unit>(ApiError.Rejected("Reminder not found", 404))

    // ---- the list ----

    @Test fun `opening the panel loads her open reminders`() {
        val vm = viewModel()
        assertEquals(listOf(pills, call), vm.state.value.reminders)
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertEquals(1, listCalls)
    }

    @Test fun `a list that cannot load says so, and shows no reminders as though there were none`() {
        listAnswer = { Result.failure(ApiError.Unreachable(IOException("down"))) }
        val vm = viewModel()
        assertEquals(RemindersViewModel.UNREACHABLE, vm.state.value.error)
        assertFalse(vm.state.value.loaded)                           // so the screen does not say "no reminders"
        assertFalse(vm.state.value.loading)

        listAnswer = null
        vm.refresh()
        assertNull(vm.state.value.error)
        assertEquals(listOf(pills, call), vm.state.value.reminders)
        assertTrue(vm.state.value.loaded)
    }

    @Test fun `a refresh that fails keeps the list that was already there`() {
        val vm = viewModel()
        listAnswer = { Result.failure(ApiError.Server(500, "")) }
        vm.refresh()
        assertEquals(listOf(pills, call), vm.state.value.reminders)
        assertEquals(RemindersViewModel.LOAD_FAILED, vm.state.value.error)
        assertTrue(vm.state.value.loaded)
    }

    @Test fun `an older refresh that answers late does not replace a newer one`() {
        val first = CompletableDeferred<Result<List<ReminderDto>>>()
        var asked = 0
        val vm = RemindersViewModel(
            fetch = { if (asked++ == 0) first.await() else Result.success(listOf(call)) },
            snoozeOnServer = { _, _ -> error("not used") },
            cancelOnServer = { error("not used") },
        )
        vm.refresh()                                                 // the newer one answers at once: just her call
        assertEquals(listOf(call), vm.state.value.reminders)
        first.complete(Result.success(listOf(pills)))                // the first, held up, answers now
        assertEquals(listOf(call), vm.state.value.reminders)
    }

    // ---- snooze and cancel ----

    @Test fun `snoozing asks for those minutes, then shows the list as she now holds it`() {
        val vm = viewModel()
        snoozeAnswer = { Result.success(Unit) }
        vm.snooze(7, 15)
        vm.snooze(8, 60)
        assertEquals(listOf(7L to 15, 8L to 60), snoozed)
        assertEquals(3, listCalls)                                   // the opening load, and one after each snooze
        assertNull(vm.notice.value)
    }

    @Test fun `cancelling a reminder takes it off the list`() {
        val vm = viewModel()
        vm.cancel(7)
        assertEquals(listOf(7L), cancelled)
        assertEquals(listOf(call), vm.state.value.reminders)
        assertNull(vm.notice.value)
    }

    // ---- a reminder that is gone ----

    @Test fun `snoozing a reminder that has already gone off says so plainly and refreshes the list`() {
        val vm = viewModel()
        snoozeAnswer = { id -> open = open.filter { it.id != id }; gone }          // it fired meanwhile: PaLLi answers 404
        vm.snooze(7, 15)

        assertEquals("That reminder has already gone off or been cancelled.", vm.notice.value)
        assertEquals(RemindersViewModel.GONE, vm.notice.value)
        assertEquals(2, listCalls)                                   // refreshed, so it is no longer on the screen
        assertEquals(listOf(call), vm.state.value.reminders)
        assertNull(vm.state.value.error)
    }

    @Test fun `cancelling one that is already gone says so plainly and refreshes the list`() {
        val vm = viewModel()
        cancelAnswer = { id -> open = open.filter { it.id != id }; gone }          // cancelled from the web door meanwhile
        vm.cancel(8)

        assertEquals(RemindersViewModel.GONE, vm.notice.value)
        assertEquals(2, listCalls)
        assertEquals(listOf(pills), vm.state.value.reminders)
    }

    @Test fun `any 404 from snooze or cancel means it is gone - she answers it alike for missing, not his, and done`() {
        val vm = viewModel()
        snoozeAnswer = { Result.failure(ApiError.Rejected("Not Found", 404)) }
        vm.snooze(99, 15)
        assertEquals(RemindersViewModel.GONE, vm.notice.value)
        assertEquals(2, listCalls)
    }

    // ---- any other failure ----

    @Test fun `any other failure is a plain notice and leaves the list as it is`() {
        val vm = viewModel()
        val before = vm.state.value

        snoozeAnswer = { Result.failure(ApiError.Unreachable(IOException("down"))) }
        vm.snooze(7, 15)
        assertEquals(RemindersViewModel.UNREACHABLE, vm.notice.value)
        vm.noticeShown(RemindersViewModel.UNREACHABLE)

        cancelAnswer = { Result.failure(ApiError.Server(500, "boom")) }
        vm.cancel(7)
        assertEquals(RemindersViewModel.CANCEL_FAILED, vm.notice.value)
        vm.noticeShown(RemindersViewModel.CANCEL_FAILED)

        snoozeAnswer = { Result.failure(ApiError.Rejected("minutes must be at least 1", 422)) }
        vm.snooze(7, 0)
        assertEquals("minutes must be at least 1", vm.notice.value)

        assertEquals(1, listCalls)                                   // nothing was refreshed
        assertEquals(before, vm.state.value)                         // and nothing on the screen changed
    }

    @Test fun `a call that throws instead of answering is a notice, not a crash`() {
        val vm = RemindersViewModel(
            fetch = { Result.success(listOf(pills)) },
            snoozeOnServer = { _, _ -> throw IllegalStateException("odd") },
            cancelOnServer = { throw IllegalStateException("odd") },
        )
        vm.snooze(7, 15)
        assertEquals(RemindersViewModel.SNOOZE_FAILED, vm.notice.value)
        vm.cancel(7)
        assertEquals(RemindersViewModel.CANCEL_FAILED, vm.notice.value)
        assertEquals(listOf(pills), vm.state.value.reminders)
    }

    @Test fun `a notice is forgotten once shown, unless a newer one has replaced it`() {
        val vm = viewModel()
        snoozeAnswer = { gone }
        vm.snooze(7, 15)
        vm.noticeShown("something else")
        assertEquals(RemindersViewModel.GONE, vm.notice.value)
        vm.noticeShown(RemindersViewModel.GONE)
        assertNull(vm.notice.value)
    }
}
