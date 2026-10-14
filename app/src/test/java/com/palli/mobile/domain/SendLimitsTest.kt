package com.palli.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PaLLi takes at most 65,536 UTF-8 bytes of words in one message (a 422 past that). Bytes, not characters. */
class SendLimitsTest {
    @Test fun `words of 65,536 bytes fit, and 65,537 do not`() {
        assertEquals(65_536, SendLimits.MAX_TEXT_BYTES)
        assertTrue(SendLimits.fitsText(""))
        assertTrue(SendLimits.fitsText("a".repeat(65_536)))
        assertFalse(SendLimits.fitsText("a".repeat(65_537)))
    }

    @Test fun `it counts bytes, so words in another script or emoji are refused well under 65,536 characters`() {
        val cjk = "中".repeat(21_846)                              // 3 bytes each: 65,538 bytes in 21,846 characters
        assertTrue(cjk.length < 65_536)
        assertFalse(SendLimits.fitsText(cjk))
        assertTrue(SendLimits.fitsText("a" + "中".repeat(21_845)))  // 1 + 65,535 = 65,536 bytes

        val emoji = "😀".repeat(16_385)                             // 4 bytes each: 65,540 bytes in 32,770 chars
        assertTrue(emoji.length < 65_536)
        assertFalse(SendLimits.fitsText(emoji))
        assertTrue(SendLimits.fitsText("😀".repeat(16_384)))         // 65,536 bytes
    }
}
