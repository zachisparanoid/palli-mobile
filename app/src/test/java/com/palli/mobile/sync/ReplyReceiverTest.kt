package com.palli.mobile.sync

import com.palli.mobile.domain.SendGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Reply typed into the conversation notification: what is queued, and the one line the thread is updated with. */
class ReplyReceiverTest {
    private val queued = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val notSent = mutableListOf<String>()
    private val gate = SendGate()

    private suspend fun reply(
        text: String = "on my way",
        enqueue: suspend (String) -> Unit = { queued += it },
        show: (String) -> Unit = { shown += it },
    ) = ReplyReceiver.reply(text, gate, enqueue, show) { notSent += it }

    @Test fun `a reply is queued and shown in the thread`() = runTest {
        reply()
        assertEquals(listOf("on my way"), queued)
        assertEquals(listOf("on my way"), shown)
        assertTrue(notSent.isEmpty())
    }

    @Test fun `while the app refuses this PaLLi, a reply is not queued and the thread says so in one plain line`() = runTest {
        gate.set(true)
        reply()
        assertTrue(queued.isEmpty())
        assertTrue(shown.isEmpty())
        assertEquals(listOf(ReplyReceiver.BLOCKED), notSent)
    }

    @Test fun `a reply the queue would not take does not crash, and the thread says it was not sent`() = runTest {
        reply(enqueue = { throw IllegalStateException("disk full") })
        assertTrue(shown.isEmpty())
        assertEquals(listOf(ReplyReceiver.FAILED), notSent)
    }

    @Test fun `a thread that cannot be updated does not crash the receiver either`() = runTest {
        reply(show = { throw SecurityException("no notifications") })
        assertEquals(listOf("on my way"), queued)
    }

    @Test fun `a cancellation is not swallowed`() = runTest {
        val thrown = runCatching { reply(enqueue = { throw CancellationException("gone") }) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(notSent.isEmpty())
    }
}
