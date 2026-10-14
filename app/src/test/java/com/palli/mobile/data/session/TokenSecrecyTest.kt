package com.palli.mobile.data.session

import com.palli.mobile.data.remote.PairResponse
import com.palli.mobile.data.remote.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device token is the phone's only credential: no string form of anything that holds it may carry it. */
class TokenSecrecyTest {
    private val token = "pmd_s3cret_t0ken"

    @Test fun `a Session never prints its token`() {
        val session = Session("http://100.64.0.7:8000", token, 7, "Pixel 9")
        val printed = session.toString()
        assertFalse(printed, printed.contains(token))
        assertTrue(printed, printed.contains("***"))
        assertTrue(printed, printed.contains("100.64.0.7") && printed.contains("Pixel 9") && printed.contains("deviceId=7"))
        assertFalse("$session".contains(token))
    }

    @Test fun `a PairResponse never prints its token`() {
        val response = Wire.json.decodeFromString<PairResponse>("""{"token":"$token","device_id":7,"api_version":1}""")
        assertEquals(token, response.token)
        val printed = response.toString()
        assertFalse(printed, printed.contains(token))
        assertTrue(printed, printed.contains("***"))
        assertTrue(printed, printed.contains("deviceId=7") && printed.contains("apiVersion=1"))
    }
}
