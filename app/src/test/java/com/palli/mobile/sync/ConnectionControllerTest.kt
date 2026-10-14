package com.palli.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControllerTest {
    @Test fun `the connection runs only when paired, and then while open or told to stay`() {
        assertEquals(false, ConnectionController.wanted(paired = false, stayConnected = true, inForeground = true))
        assertEquals(true, ConnectionController.wanted(paired = true, stayConnected = false, inForeground = true))
        assertEquals(true, ConnectionController.wanted(paired = true, stayConnected = true, inForeground = false))
        assertEquals(false, ConnectionController.wanted(paired = true, stayConnected = false, inForeground = false))
    }
}
