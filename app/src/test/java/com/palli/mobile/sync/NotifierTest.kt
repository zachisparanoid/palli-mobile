package com.palli.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    @Test fun `each state of the connection has its own title`() {
        assertEquals("Connected to PaLLi", Notifier.connectionTitle(ConnState.CONNECTED))
        assertEquals("Connecting to PaLLi", Notifier.connectionTitle(ConnState.CONNECTING))
        assertEquals("Reconnecting to PaLLi", Notifier.connectionTitle(ConnState.WAITING))
        assertEquals("Not connected to PaLLi", Notifier.connectionTitle(ConnState.IDLE))
    }

    @Test fun `it says Connected only while connected`() {
        ConnState.entries.forEach { state ->
            val connected = Notifier.connectionTitle(state).startsWith("Connected")
            assertEquals("$state", state == ConnState.CONNECTED, connected)
        }
        assertEquals(ConnState.entries.size, ConnState.entries.map(Notifier::connectionTitle).toSet().size)
        assertFalse(Notifier.connectionTitle(ConnState.WAITING).contains("Connected"))
        assertTrue(Notifier.connectionTitle(ConnState.IDLE).startsWith("Not"))
    }
}
