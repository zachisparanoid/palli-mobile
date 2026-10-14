package com.palli.mobile.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRulesTest {
    @Test fun `an accidental tap sends nothing, a real note does`() {
        assertEquals(false, RecordingRules.shouldSend(0))
        assertEquals(false, RecordingRules.shouldSend(599))
        assertEquals(true, RecordingRules.shouldSend(600))
        assertEquals(true, RecordingRules.shouldSend(RecordingRules.MAX_MS))
    }
}
