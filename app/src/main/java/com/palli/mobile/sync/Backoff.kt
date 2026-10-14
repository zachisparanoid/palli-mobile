package com.palli.mobile.sync

import kotlin.math.max
import kotlin.math.min

/**
 * How long to wait before trying PaLLi again. Full jitter: a random wait up to a ceiling that doubles from 1 s to
 * a minute, so a phone does not hammer a PaLLi that is restarting, and a reconnect after a network change (kick())
 * skips the wait entirely. Floor of half the base: never a hot loop.
 *
 * Owner contribution point: this is the one policy in the app that is a matter of taste (battery vs. how fast her
 * messages land after an outage). Replace the body of nextDelayMs() with your own if you like; BackoffTest pins
 * only the ceiling sequence and the floor.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val capMs: Long = 60_000,
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val ceiling = min(capMs, baseMs shl min(attempt, 16))
        attempt++
        return max(baseMs / 2, (random() * ceiling).toLong())
    }

    fun reset() { attempt = 0 }
}
