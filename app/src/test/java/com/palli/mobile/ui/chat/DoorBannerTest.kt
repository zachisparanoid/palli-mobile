package com.palli.mobile.ui.chat

import com.palli.mobile.data.remote.DeviceRef
import com.palli.mobile.data.remote.MeResponse
import com.palli.mobile.sync.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorBannerTest {
    private fun me(state: String, version: Int = 1) = MeResponse(DeviceRef(1, "Phone"), state, "", version)

    @Test fun `an open door says nothing, and shut, brainless and mismatched doors say why`() {
        assertNull(DoorBanner.text(me("open")))
        assertTrue(DoorBanner.text(me("closed"))!!.contains("shut"))
        assertTrue(DoorBanner.text(me("no_brain"))!!.contains("no brain"))
        assertTrue(DoorBanner.text(me("open", version = 2))!!.contains("different versions"))
    }

    @Test fun `the connection reads in plain words`() {
        assertEquals("connected", DoorBanner.connection(ConnState.CONNECTED))
        assertEquals("offline — retrying", DoorBanner.connection(ConnState.WAITING))
    }
}
