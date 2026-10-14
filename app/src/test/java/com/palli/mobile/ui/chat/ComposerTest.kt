package com.palli.mobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.palli.mobile.ui.theme.PalliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The composer alone: words, the attach menu, hold-to-talk, and what is off when. */
@RunWith(RobolectricTestRunner::class)
class ComposerTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var draft by mutableStateOf("")
    private var enabled by mutableStateOf(true)
    private var recording by mutableStateOf(false)

    private fun show() = compose.setContent {
        PalliTheme {
            Composer(
                draft = draft, onDraft = { draft = it }, onSend = { calls += "send" }, enabled = enabled,
                recording = recording,
                onPickPhotos = { calls += "photos" }, onCamera = { calls += "camera" }, onPickFile = { calls += "file" },
                onRecordStart = { calls += "start" }, onRecordStop = { calls += "stop" }, onRecordCancel = { calls += "cancel" },
            )
        }
    }

    private val mic get() = compose.onNodeWithContentDescription("Hold to record a voice note")

    @Test fun `Send is always there, and on only when there are words`() {
        show()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsNotEnabled()
        draft = "hi"
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(listOf("send"), calls)
    }

    @Test fun `the microphone sits beside Send while the box is empty, and steps aside for words`() {
        show()
        mic.assertIsDisplayed().assertIsEnabled()
        draft = "hi"
        mic.assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test fun `the attach menu offers a photo, the camera and a file`() {
        show()
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Photo").performClick()
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("File").performClick()
        assertEquals(listOf("photos", "camera", "file"), calls)
    }

    @Test fun `holding the microphone records, and letting go sends`() {
        show()
        mic.performTouchInput { down(center); advanceEventTime(1_500); up() }
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun `sliding off the microphone drops the note`() {
        show()
        mic.performTouchInput { down(center); moveBy(Offset(0f, -600f)); up() }
        assertEquals(listOf("start", "cancel"), calls)
    }

    @Test fun `a hold the composer stops taking (switched off mid-hold) drops the note rather than leaving it recording`() {
        show()
        mic.performTouchInput { down(center) }
        enabled = false
        compose.waitForIdle()
        assertEquals(listOf("start", "cancel"), calls)
    }

    @Test fun `while recording the box says so, and nothing can be attached or sent`() {
        recording = true
        show()
        compose.onNodeWithText("Recording", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Attach").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        mic.assertIsDisplayed()
    }

    @Test fun `switched off, nothing can be typed, attached, recorded or sent`() {
        enabled = false
        draft = "words from before"
        show()
        compose.onNodeWithText("words from before").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Attach").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Photo").assertDoesNotExist()

        draft = ""
        mic.assertIsNotEnabled()
        mic.performTouchInput { down(center); advanceEventTime(1_500); up() }
        assertEquals(emptyList<String>(), calls)
    }
}
