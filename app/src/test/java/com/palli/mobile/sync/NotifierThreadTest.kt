package com.palli.mobile.sync

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The conversation notification, posted for real (Robolectric's notification manager), with the clock in hand. */
@RunWith(RobolectricTestRunner::class)
class NotifierThreadTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var now = 100_000L
    private val notifier = Notifier(app) { now }.apply { createChannels() }

    private fun event(id: Long, kind: String = EventKinds.MESSAGE) = EventDto(id, kind, id.toDouble(), "t$id")
    private fun conversation(): Notification =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(Notifier.ID_CONVERSATION)
    /** Whether this post makes the phone buzz: an update that is not only-alert-once alerts again. */
    private fun buzzed() = conversation().flags and Notification.FLAG_ONLY_ALERT_ONCE == 0

    @Test fun `thirty events replayed within a second buzz once, and one three seconds later buzzes again`() {
        var buzzes = 0
        repeat(30) { i ->
            notifier.post(listOf(event(i + 1L)))                   // the stream hands the sink one event per frame
            if (buzzed()) buzzes++
            now += 33
        }
        assertEquals(1, buzzes)
        // Every one is in the thread (MessagingStyle keeps the newest 25), only quietly.
        assertEquals("t30", NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversation())!!
            .messages.last().text.toString())

        now += 3_000
        notifier.post(listOf(event(31)))
        assertTrue(buzzed())
    }

    @Test fun `a notice in the thread is not hers`() {
        notifier.post(listOf(event(1), event(2, EventKinds.NOTICE)))
        val (hers, notice) = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversation())!!.messages
        assertEquals("PaLLi", hers.person!!.name.toString())
        assertEquals(Notifier.NOTICE_SENDER, notice.person!!.name.toString())
        assertNotEquals("PaLLi", notice.person!!.name.toString())
        assertNotEquals(hers.person!!.key, notice.person!!.key)
    }
}
