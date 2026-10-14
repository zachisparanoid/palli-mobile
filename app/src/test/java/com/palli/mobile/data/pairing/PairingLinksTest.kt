package com.palli.mobile.data.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinksTest {
    @Test fun `the QR's link gives the address and the code`() {
        val link = PairingLinks.parse("palli://pair?u=http%3A%2F%2Fbuilder.tail2d20b5.ts.net%3A8000&c=K7QM-2XPA")
        assertEquals(PairingLink("http://builder.tail2d20b5.ts.net:8000", "K7QM2XPA"), link)
    }

    @Test fun `anything that is not a pairing link is null`() {
        assertNull(PairingLinks.parse("https://pair?u=x&c=K7QM2XPA"))
        assertNull(PairingLinks.parse("palli://other?u=http%3A%2F%2Fa&c=K7QM2XPA"))
        assertNull(PairingLinks.parse("palli://pair?c=K7QM2XPA"))
        assertNull(PairingLinks.parse("palli://pair?u=http%3A%2F%2Fa"))
        assertNull(PairingLinks.parse("not a link at all"))
    }

    @Test fun `a typed code forgives case, spaces, the dash and lookalikes`() {
        assertEquals("K7QM2XPA", PairingLinks.normalizeCode("k7qm-2xpa"))
        assertEquals("K7QM2XPA", PairingLinks.normalizeCode(" K7QM 2XPA "))
        assertEquals("01110000", PairingLinks.normalizeCode("O1IL-0000"))
        assertNull(PairingLinks.normalizeCode("K7QM2XP"))      // seven
        assertNull(PairingLinks.normalizeCode("K7QM2XPU"))     // U is not Crockford
    }

    @Test fun `an address gains http, loses trailing slashes, keeps https and a path`() {
        assertEquals("http://100.87.134.112:8000", PairingLinks.normalizeBaseUrl("100.87.134.112:8000"))
        assertEquals("http://builder:8000", PairingLinks.normalizeBaseUrl("http://builder:8000///"))
        assertEquals("https://builder.ts.net/palli", PairingLinks.normalizeBaseUrl("https://builder.ts.net/palli/"))
        assertNull(PairingLinks.normalizeBaseUrl("ftp://builder"))
        assertNull(PairingLinks.normalizeBaseUrl("   "))
    }
}
