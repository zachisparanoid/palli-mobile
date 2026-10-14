package com.palli.mobile.ui.pair

import com.palli.mobile.data.remote.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PairErrorsTest {
    private val base = "http://palli-box.example.ts.net:8000"

    @Test fun `each refusal gets the sentence spec 12 promises`() {
        assertTrue(PairErrors.message(ApiError.Unauthorized("x"), base).startsWith("That code is not one PaLLi made"))
        assertTrue(PairErrors.message(ApiError.Gone("x"), base).startsWith("That code expired"))
        assertTrue(PairErrors.message(ApiError.RateLimited("x"), base).startsWith("Too many tries"))
        assertTrue(PairErrors.message(ApiError.Forbidden("x"), base).contains("refused this address"))
        assertEquals("Can't reach PaLLi at $base. Is Tailscale on, and is she running?",
            PairErrors.message(ApiError.Unreachable(IOException("timeout")), base))
        assertEquals("version mismatch", PairErrors.message(ApiError.Rejected("version mismatch"), base))
    }

    @Test fun `anything else is named, never swallowed`() {
        assertEquals("Pairing failed: boom", PairErrors.message(IllegalStateException("boom"), base))
    }

    @Test fun `a server that is not PaLLi is said to be not PaLLi, not read out as a web page`() {
        val notPaLLi = "That address answered, but it isn't PaLLi."
        assertEquals(notPaLLi, PairErrors.message(ApiError.Rejected(""), base))
        assertEquals(notPaLLi, PairErrors.message(ApiError.Rejected("   "), base))
        assertEquals(notPaLLi, PairErrors.message(ApiError.Rejected("<!DOCTYPE html><html><body>Not Found</body></html>"), base))
        assertEquals(notPaLLi, PairErrors.message(ApiError.Rejected("  <html>"), base))
        assertEquals(notPaLLi, PairErrors.message(ApiError.Server(502, "<html><h1>Bad Gateway</h1></html>"), base))
        assertEquals(notPaLLi, PairErrors.message(ApiError.Server(500, ""), base))
        // PaLLi's own sentences still come through as they are
        assertEquals("pair code is malformed", PairErrors.message(ApiError.Rejected("pair code is malformed"), base))
        assertEquals("Pairing failed: database is down", PairErrors.message(ApiError.Server(500, "database is down"), base))
    }

    @Test fun `an address the networking library refused asks for the address again`() {
        assertEquals("Enter PaLLi's address, like http://your-host.your-tailnet.ts.net:8000.",
            PairErrors.message(IllegalArgumentException("Invalid URL port: \"99999\""), base))
    }
}
