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
}
