package com.palli.mobile.sync

import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyRuleTest {
    private fun ev(kind: String, level: String? = null) = EventDto(id = 1, kind = kind, at = 1.0, text = "x", level = level)

    @Test fun `with the app open nothing notifies`() {
        listOf(EventKinds.MESSAGE, EventKinds.VOICE_NOTE, EventKinds.FILE, EventKinds.NOTICE).forEach {
            assertEquals(false, NotifyRule.shouldNotify(ev(it), appInForeground = true))
        }
    }

    @Test fun `in the background her words, voice, files and notices notify, your own never`() {
        assertEquals(true, NotifyRule.shouldNotify(ev(EventKinds.MESSAGE), false))
        assertEquals(true, NotifyRule.shouldNotify(ev(EventKinds.VOICE_NOTE), false))
        assertEquals(true, NotifyRule.shouldNotify(ev(EventKinds.FILE), false))
        assertEquals(true, NotifyRule.shouldNotify(ev(EventKinds.NOTICE, "trouble"), false))
        assertEquals(false, NotifyRule.shouldNotify(ev(EventKinds.USER_MESSAGE), false))
        assertEquals(false, NotifyRule.shouldNotify(ev("hologram"), false))
    }
}
