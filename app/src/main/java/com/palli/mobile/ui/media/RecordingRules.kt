package com.palli.mobile.ui.media

object RecordingRules {
    /** Shorter than this is a slip of the thumb, not a voice note (MediaRecorder may not even have data). */
    const val MIN_MS = 600L
    /** The recorder stops itself here; a 5-minute m4a at 64 kbps is ~2.4 MB, far under the 20 MB part limit. */
    const val MAX_MS = 300_000L

    fun shouldSend(durationMs: Long): Boolean = durationMs >= MIN_MS
}
