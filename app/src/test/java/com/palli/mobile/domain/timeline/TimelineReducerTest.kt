package com.palli.mobile.domain.timeline

import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.remote.FileRef
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import com.palli.mobile.data.remote.Wire

class TimelineReducerTest {
    private fun ev(id: Long, kind: String, at: Double = id.toDouble(), text: String? = "t$id",
                   clientId: String? = null, file: FileRef? = null, level: String? = null) =
        EventDto(id = id, kind = kind, at = at, text = text, clientId = clientId, file = file, level = level)

    private fun pending(id: String, state: String = SendStates.SENDING, created: Long = 0L) = PendingSendEntity(
        clientId = id, text = "pending $id",
        attachmentsJson = Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), emptyList()),
        state = state, failure = if (state == SendStates.FAILED) "too large" else null,
        createdAt = created, attempts = 0)

    @Test fun `order is by id even when the server's clock went backwards`() {
        val items = TimelineReducer.reduce(
            listOf(ev(3, EventKinds.MESSAGE, at = 100.0), ev(1, EventKinds.MESSAGE, at = 300.0), ev(2, EventKinds.MESSAGE, at = 200.0)),
            emptyList())
        assertEquals(listOf("e1", "e2", "e3"), items.map { it.key })
    }

    @Test fun `a send whose message is in the timeline is shown once, as delivered`() {
        val items = TimelineReducer.reduce(
            listOf(ev(1, EventKinds.USER_MESSAGE, clientId = "c1", text = "hi")),
            listOf(pending("c1", SendStates.ACCEPTED), pending("c2", created = 5)))
        assertEquals(listOf("e1", "pc2"), items.map { it.key })
        val delivered = items[0] as TimelineItem.Mine
        assertEquals(null, delivered.status)
        assertEquals(SendStatus.SENDING, (items[1] as TimelineItem.Mine).status)
    }

    @Test fun `waiting sends come after the timeline in the order they were made`() {
        val items = TimelineReducer.reduce(listOf(ev(1, EventKinds.MESSAGE)),
            listOf(pending("late", created = 20), pending("early", SendStates.FAILED, created = 10)))
        assertEquals(listOf("e1", "pearly", "plate"), items.map { it.key })
        assertEquals(SendStatus.FAILED, (items[1] as TimelineItem.Mine).status)
        assertEquals("too large", (items[1] as TimelineItem.Mine).failure)
    }

    @Test fun `each kind becomes its own item and an unknown kind is skipped`() {
        val voice = FileRef("f_v", "voice.ogg", "audio/ogg")
        val doc = FileRef("f_d", "plot.png", "image/png")
        val items = TimelineReducer.reduce(listOf(
            ev(1, EventKinds.MESSAGE),
            ev(2, EventKinds.VOICE_NOTE, text = "aloud", file = voice),
            ev(3, EventKinds.FILE, text = null, file = doc),
            ev(4, EventKinds.NOTICE, text = "That turn failed", level = "trouble"),
            ev(5, "hologram"),
            ev(6, EventKinds.VOICE_NOTE, file = null),
        ), emptyList())
        assertEquals(4, items.size)
        assertEquals("aloud", (items[1] as TimelineItem.HerVoice).transcript)
        assertEquals(doc, (items[2] as TimelineItem.HerFile).file)
        assertEquals(true, (items[3] as TimelineItem.Notice).trouble)
    }
}
