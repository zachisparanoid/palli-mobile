package com.palli.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingThrottleTest {
    @Test fun `at most one typing frame every three seconds`() {
        val throttle = TypingThrottle(intervalMs = 3_000)
        assertEquals(listOf(true, false, true, false, true),
            listOf(0L, 1_000L, 3_000L, 5_999L, 6_000L).map(throttle::shouldSend))
    }
}
