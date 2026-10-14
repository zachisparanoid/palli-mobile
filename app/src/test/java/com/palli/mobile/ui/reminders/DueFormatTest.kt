package com.palli.mobile.ui.reminders

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class DueFormatTest {
    private val now = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, ZoneOffset.UTC).toEpochSecond().toDouble()  // a Tuesday
    private fun at(seconds: Double) = DueFormat.format(now + seconds, now, ZoneOffset.UTC, Locale.US)

    @Test fun `a due time reads the way you would say it`() {
        assertEquals("overdue", at(-7_200.0))
        assertEquals("now", at(30.0))
        assertEquals("in 30 min", at(1_800.0))
        assertEquals("today 3:00 PM", at(3 * 3_600.0))
        assertEquals("tomorrow 9:00 AM", at(21 * 3_600.0))
        assertEquals("Fri 12:00 PM", at(3 * 86_400.0))
        assertEquals("Nov 12, 12:00 PM", at(30 * 86_400.0))
    }

    @Test fun `each edge falls on the side a person would put it`() {
        assertEquals("now", at(-60.0))                     // a minute late is still "now"
        assertEquals("overdue", at(-61.0))
        assertEquals("now", at(59.0))
        assertEquals("in 1 min", at(60.0))
        assertEquals("in 59 min", at(3_599.0))
        assertEquals("today 1:00 PM", at(3_600.0))          // an hour ahead stops counting minutes
        assertEquals("tomorrow 12:00 AM", at(12 * 3_600.0))
        assertEquals("Mon 11:59 AM", at(6 * 86_400.0 - 60))       // the 6th day ahead still has a weekday
        assertEquals("Oct 20, 12:00 PM", at(7 * 86_400.0))        // the 7th would repeat today's weekday
    }

    @Test fun `the day is the phone's day, not UTC's`() {
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")                // 21:00 here, so 4 hours on is tomorrow there
        val evening = ZonedDateTime.of(2026, 10, 13, 21, 0, 0, 0, tokyo).toEpochSecond().toDouble()
        assertEquals("tomorrow 1:00 AM", DueFormat.format(evening + 4 * 3_600, evening, tokyo, Locale.US))
        assertEquals("today 11:00 PM", DueFormat.format(evening + 2 * 3_600, evening, tokyo, Locale.US))
    }
}
