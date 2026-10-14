package com.palli.mobile.ui.reminders

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ReminderDto
import com.palli.mobile.ui.theme.PalliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** The reminders panel on Robolectric, over a RemindersViewModel whose calls on PaLLi are stand-ins. */
@RunWith(RobolectricTestRunner::class)
class RemindersScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var open = listOf(
        ReminderDto(7, "take the pills", System.currentTimeMillis() / 1000.0 + 1_830),   // a little over, so "30" holds as the test runs
        ReminderDto(8, "call Sam back", System.currentTimeMillis() / 1000.0 - 7_200),
    )
    private var loadFails = false
    private val snoozed = mutableListOf<Pair<Long, Int>>()
    private val cancelled = mutableListOf<Long>()
    private var snoozeAnswer: (Long) -> Result<Unit> = { Result.success(Unit) }
    private var backs = 0

    private fun show() {
        val vm = RemindersViewModel(
            fetch = { if (loadFails) Result.failure(ApiError.Unreachable(IOException("down"))) else Result.success(open) },
            snoozeOnServer = { id, minutes -> snoozed += id to minutes; snoozeAnswer(id) },
            cancelOnServer = { id -> cancelled += id; open = open.filter { it.id != id }; Result.success(Unit) },
        )
        compose.setContent { PalliTheme { RemindersScreen(onBack = { backs++ }, vm = vm) } }
    }

    private fun assertAbsent(text: String) = assertEquals(0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    @Test fun `each reminder shows its words and when it is due, the way you would say it`() {
        show()
        compose.onNodeWithText("take the pills").assertIsDisplayed()
        compose.onNode(hasText("in 30 min") or hasText("in 29 min")).assertIsDisplayed()   // which, depends on how long the setup took
        compose.onNodeWithText("call Sam back").assertIsDisplayed()
        compose.onNodeWithText("overdue").assertIsDisplayed()
        assertAbsent("No reminders. Ask her for one in the chat.")
    }

    @Test fun `snooze for 15 minutes or an hour asks for that on that reminder`() {
        show()
        compose.onAllNodesWithText("Snooze 15 min")[0].performClick()
        compose.onAllNodesWithText("1 hr")[1].performClick()
        assertEquals(listOf(7L to 15, 8L to 60), snoozed)
    }

    @Test fun `cancel asks first, and keeping it cancels nothing`() {
        show()
        compose.onAllNodesWithText("Cancel")[0].performClick()
        compose.onNodeWithText("Cancel this reminder?").assertIsDisplayed()
        compose.onAllNodesWithText("take the pills")[1].assertIsDisplayed()            // the dialog names which one
        assertTrue(cancelled.isEmpty())

        compose.onNodeWithText("Keep it").performClick()
        compose.waitForIdle()
        assertAbsent("Cancel this reminder?")
        assertTrue(cancelled.isEmpty())
        compose.onNodeWithText("take the pills").assertIsDisplayed()
    }

    @Test fun `cancelling it cancels that reminder and it leaves the list`() {
        show()
        compose.onAllNodesWithText("Cancel")[0].performClick()
        compose.onNodeWithText("Cancel it").performClick()
        compose.waitForIdle()
        assertEquals(listOf(7L), cancelled)
        assertAbsent("take the pills")
        compose.onNodeWithText("call Sam back").assertIsDisplayed()
    }

    @Test fun `a reminder that has already gone is said plainly, and the list stops showing it`() {
        snoozeAnswer = { open = open.filter { it.id != 7L }; Result.failure(ApiError.Rejected("Reminder not found", 404)) }
        show()
        compose.onAllNodesWithText("Snooze 15 min")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("That reminder has already gone off or been cancelled.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("That reminder has already gone off or been cancelled.").assertIsDisplayed()
        assertAbsent("take the pills")
        compose.onNodeWithText("call Sam back").assertIsDisplayed()
    }

    @Test fun `with none open it says so, and how to get one`() {
        open = emptyList()
        show()
        compose.onNodeWithText("No reminders. Ask her for one in the chat.").assertIsDisplayed()
    }

    @Test fun `when PaLLi cannot be reached it says that, offers another go, and does not claim there are none`() {
        loadFails = true
        show()
        compose.onNodeWithText(RemindersViewModel.UNREACHABLE).assertIsDisplayed()
        assertAbsent("No reminders. Ask her for one in the chat.")

        loadFails = false
        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("take the pills").assertIsDisplayed()
        assertAbsent(RemindersViewModel.UNREACHABLE)
    }

    @Test fun `back goes back`() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
