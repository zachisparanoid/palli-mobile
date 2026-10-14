package com.palli.mobile.ui.reminders

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reminder's due time the way you would say it: "in 30 min", "today 3:00 PM", "Fri 12:00 PM". */
object DueFormat {
    fun format(dueEpochS: Double, nowEpochS: Double, zone: ZoneId = ZoneId.systemDefault(),
               locale: Locale = Locale.getDefault()): String {
        val delta = dueEpochS - nowEpochS
        if (delta < -60) return "overdue"
        if (delta < 60) return "now"
        if (delta < 3_600) return "in ${(delta / 60).toInt()} min"
        val due = Instant.ofEpochMilli((dueEpochS * 1000).toLong()).atZone(zone)
        val today = Instant.ofEpochMilli((nowEpochS * 1000).toLong()).atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(due)
        return when {
            due.toLocalDate() == today -> "today $time"
            due.toLocalDate() == today.plusDays(1) -> "tomorrow $time"
            due.toLocalDate().isBefore(today.plusDays(7)) -> DateTimeFormatter.ofPattern("EEE h:mm a", locale).format(due)
            else -> DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).format(due)
        }
    }
}
