package com.palli.mobile.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The version block: what /me says decides it at once, and is written down so a cold start begins from it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendGateTest {
    private var stored: Int? = null
    private fun gate() = SendGate(readVersion = { stored }, writeVersion = { stored = it })

    @Test fun `with nothing written down, nothing is blocked`() = runTest {
        assertEquals(false, gate().isBlocked())
    }

    @Test fun `a cold start begins from the last version heard`() = runTest {
        stored = 2
        assertEquals(true, gate().isBlocked())
        stored = 1
        assertEquals(false, gate().isBlocked())
    }

    @Test fun `what is heard decides at once and is written down for the next start`() = runTest {
        val first = gate()
        first.heard(2)
        assertEquals(true, first.blocked.value)
        assertEquals(2, stored)
        assertEquals("a new process", true, gate().isBlocked())

        first.heard(1)
        assertEquals(false, first.blocked.value)
        assertEquals(false, gate().isBlocked())
    }

    @Test fun `a fresh answer wins over the remembered one, even one still being read`() = runTest {
        stored = 2
        val reading = CompletableDeferred<Unit>()
        val slow = SendGate(readVersion = { reading.await(); stored }, writeVersion = { stored = it })
        val load = launch(UnconfinedTestDispatcher(testScheduler)) { slow.load() }
        val answer = launch(UnconfinedTestDispatcher(testScheduler)) { slow.heard(1) }
        reading.complete(Unit)
        load.join(); answer.join()
        assertEquals(false, slow.isBlocked())

        val after = gate()
        after.heard(1)
        stored = 2                                                // written down by someone else, too late to count
        assertEquals(false, after.isBlocked())
    }

    @Test fun `storage that cannot be read blocks nothing and does not throw`() = runTest {
        val broken = SendGate(readVersion = { throw IOException("unreadable") }, writeVersion = { throw IOException("full") })
        assertEquals(false, broken.isBlocked())
        broken.heard(2)
        assertEquals(true, broken.blocked.value)
    }
}
