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
        assertNull(PairingLinks.normalizeBaseUrl("http://"))       // half-typed: a scheme, no host
        assertNull(PairingLinks.normalizeBaseUrl("https:///"))
    }

    @Test fun `a scheme with no slashes is half an address, not a host called http`() {
        assertNull(PairingLinks.normalizeBaseUrl("http:"))
        assertNull(PairingLinks.normalizeBaseUrl("http:/"))
        assertNull(PairingLinks.normalizeBaseUrl("https:"))
        assertNull(PairingLinks.normalizeBaseUrl("https:/"))
        assertNull(PairingLinks.normalizeBaseUrl("HTTP:"))
        assertNull(PairingLinks.normalizeBaseUrl("  https:  "))
        assertNull(PairingLinks.normalizeBaseUrl("http:palli-box.example.ts.net"))      // one colon, no slashes
        assertNull(PairingLinks.normalizeBaseUrl("http//palli-box.example.ts.net"))     // slashes, no colon
        assertNull(PairingLinks.normalizeBaseUrl("http"))
        assertNull(PairingLinks.parse("palli://pair?u=http%3A&c=K7QM2XPA"))
        assertNull(PairingLinks.parse("palli://pair?u=https%3A%2F&c=K7QM2XPA"))
    }

    @Test fun `scheme and host read the same in any case`() {
        assertEquals("http://palli-box.example.ts.net:8000", PairingLinks.normalizeBaseUrl("HTTP://palli-box.example.ts.net:8000"))
        assertEquals("https://100.64.0.7", PairingLinks.normalizeBaseUrl("Https://100.64.0.7/"))
        assertEquals("http://palli-box.example.ts.net:8000", PairingLinks.normalizeBaseUrl("Palli-Box.Example.TS.net:8000"))
        assertEquals("http://palli-box.example.ts.net/Palli", PairingLinks.normalizeBaseUrl("HTTP://Palli-Box.Example.ts.net/Palli/"))   // the path keeps its case
        assertNull(PairingLinks.normalizeBaseUrl("FTP://palli-box.example.ts.net"))
        assertNull(PairingLinks.normalizeBaseUrl("HTTP://"))
    }

    @Test fun `a link reads the same in any case`() {
        val expected = PairingLink("http://palli-box.example.ts.net:8000", "K7QM2XPA")
        assertEquals(expected, PairingLinks.parse("Palli://PAIR?u=http%3A%2F%2Fpalli-box.example.ts.net%3A8000&c=K7QM-2XPA"))
        assertEquals(expected, PairingLinks.parse("PALLI://pair?u=HTTP%3A%2F%2FPalli-Box.example.ts.net%3A8000&c=k7qm2xpa"))
    }

    @Test fun `an address cannot hide its host behind user-info`() {
        // Whatever follows the @ is the host, but a dialog showing the whole string would read as the part before it.
        assertNull(PairingLinks.normalizeBaseUrl("http://palli-box.example.ts.net@100.64.0.7:8000"))
        assertNull(PairingLinks.normalizeBaseUrl("palli-box.example.ts.net:8000@100.64.0.7"))
        assertNull(PairingLinks.parse("palli://pair?u=http%3A%2F%2Fpalli-box.example.ts.net%40100.64.0.7%3A8000&c=K7QM2XPA"))
    }

    @Test fun `a link with a broken escape is null, not a crash`() {
        assertNull(PairingLinks.parse("palli://pair?u=%zz&c=K7QM2XPA"))
        assertNull(PairingLinks.parse("palli://pair?u=http%3A%2F%2F100.64.0.7&c=%"))
    }
}
