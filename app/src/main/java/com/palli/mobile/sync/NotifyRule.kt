package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds

/**
 * Which events wake the phone. Owner contribution point: this is taste, not plumbing — e.g. you might keep
 * `info` notices (a spending note) silent, or notify reminders only. NotifyRuleTest pins the reference rule:
 * nothing while the app is open; in the background, everything of hers and every notice; never your own words
 * (they may arrive from your other phone).
 */
object NotifyRule {
    fun shouldNotify(event: EventDto, appInForeground: Boolean): Boolean {
        if (appInForeground) return false
        return when (event.kind) {
            EventKinds.MESSAGE, EventKinds.VOICE_NOTE, EventKinds.FILE, EventKinds.NOTICE -> true
            else -> false
        }
    }
}
