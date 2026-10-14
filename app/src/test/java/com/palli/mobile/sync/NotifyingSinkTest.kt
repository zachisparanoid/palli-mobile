package com.palli.mobile.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.remote.FileRef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The real sink and the real notifier, posting into Robolectric's notification manager. */
@RunWith(RobolectricTestRunner::class)
class NotifyingSinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val system: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val foreground = AppForeground()
    private lateinit var notifier: Notifier
    private lateinit var sink: NotifyingSink

    @Before fun setUp() {
        notifier = Notifier(context).also { it.createChannels() }
        sink = NotifyingSink(notifier, foreground)
    }

    private fun hers(id: Long, text: String) = EventDto(id = id, kind = EventKinds.MESSAGE, at = id.toDouble(), text = text)

    private fun conversation(): Notification? = shadowOf(system).getNotification(null, Notifier.ID_CONVERSATION)

    private fun lines(): List<String> = NotificationCompat.MessagingStyle
        .extractMessagingStyleFromNotification(conversation()!!)!!.messages.map { it.text.toString() }

    private val owner = object : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun `in the background her message is posted on the spot, with nothing awaited`() = runTest {
        // Started undispatched, so it only finishes before launch() returns if the sink never suspends:
        // the connection loop's watchdog does not watch the sink, so a sink that waits would stall the stream unseen.
        val call = launch(start = CoroutineStart.UNDISPATCHED) { sink.onNewEvents(listOf(hers(1, "hello"))) }
        assertTrue(call.isCompleted)
        assertEquals(listOf("hello"), lines())
    }

    @Test fun `the app being open posts nothing, and neither does what you said yourself`() = runTest {
        foreground.onStart(owner)                                   // the app is open
        sink.onNewEvents(listOf(hers(1, "hello")))
        assertNull(conversation())

        foreground.onStop(owner)                                    // closed again: still never your own words
        sink.onNewEvents(listOf(EventDto(id = 2, kind = EventKinds.USER_MESSAGE, at = 2.0, text = "from my other phone")))
        assertNull(conversation())
    }

    @Test fun `later messages join the same conversation, and your reply from the notification joins it too`() = runTest {
        sink.onNewEvents(listOf(hers(1, "one")))
        sink.onNewEvents(listOf(hers(2, "two"), hers(3, "three")))
        assertEquals(listOf("one", "two", "three"), lines())
        assertEquals(1, shadowOf(system).allNotifications.size)

        notifier.addMyReply("on my way")
        assertEquals(listOf("one", "two", "three", "on my way"), lines())
        assertEquals(1, shadowOf(system).allNotifications.size)

        notifier.clearConversation()
        assertNull(conversation())
    }

    @Test fun `voice notes, files and reminders read as what they are`() = runTest {
        sink.onNewEvents(listOf(
            EventDto(id = 1, kind = EventKinds.VOICE_NOTE, at = 1.0, text = "see you at six"),
            EventDto(id = 2, kind = EventKinds.VOICE_NOTE, at = 2.0),
            EventDto(id = 3, kind = EventKinds.FILE, at = 3.0, file = FileRef(id = "f1", filename = "map.pdf")),
            EventDto(id = 4, kind = EventKinds.FILE, at = 4.0, file = FileRef(id = "f2")),
            EventDto(id = 5, kind = EventKinds.MESSAGE, at = 5.0, text = "call the dentist", reminder = true),
            EventDto(id = 6, kind = EventKinds.NOTICE, at = 6.0, text = "tea is ready", level = "info"),
        ))
        assertEquals(
            listOf("Voice note: see you at six", "Voice note", "File: map.pdf", "File: a file",
                "Reminder: call the dentist", "tea is ready"),
            lines())
    }

    @Test fun `the conversation notification carries the inline reply`() = runTest {
        sink.onNewEvents(listOf(hers(1, "hello")))
        val action = conversation()!!.actions.single()
        assertEquals("Reply", action.title.toString())
        assertNotNull(action.remoteInputs.single())
        assertEquals(Notifier.REPLY_KEY, action.remoteInputs.single().resultKey)
    }
}
