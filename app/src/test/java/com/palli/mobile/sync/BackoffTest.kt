package com.palli.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun `the ceiling doubles from a second up to a minute and a connect resets it`() {
        val backoff = Backoff(random = { 1.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            List(8) { backoff.nextDelayMs() })
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test fun `jitter never makes a hot loop`() {
        val backoff = Backoff(random = { 0.0 })
        assertEquals(500L, backoff.nextDelayMs())
    }
}
