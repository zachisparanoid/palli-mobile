package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns new events into notifications. It runs inline on the connection loop, and the loop's 60 s silence watchdog
 * wraps only `receive()`, so a sink that waits stalls the stream unnoticed. Keep it to fast, local work (deciding and
 * posting a notification) and never await the network or a slow disk here.
 */
@Singleton
class NotifyingSink @Inject constructor(
    private val notifier: Notifier,
    private val foreground: AppForeground,
) : EventSink {
    override suspend fun onNewEvents(events: List<EventDto>) {
        val worth = events.filter { NotifyRule.shouldNotify(it, foreground.inForeground.value) }
        if (worth.isNotEmpty()) notifier.post(worth)
    }
}
