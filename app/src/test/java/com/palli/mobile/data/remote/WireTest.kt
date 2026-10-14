package com.palli.mobile.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test fun `every event kind decodes from the backend's flat shape`() {
        val mine = Wire.json.decodeFromString(EventDto.serializer(),
            """{"id":812,"kind":"user_message","at":1760400000.1,"client_id":"9f","text":"hi","heard_as":"hi there","files":[{"id":"f_a","filename":"cat.jpg","content_type":"image/jpeg","size":12}]}""")
        assertEquals(EventKinds.USER_MESSAGE, mine.kind)
        assertEquals("9f", mine.clientId); assertEquals("hi there", mine.heardAs)
        assertEquals("image/jpeg", mine.files.single().contentType)

        val hers = Wire.json.decodeFromString(EventDto.serializer(),
            """{"id":813,"kind":"message","at":1.0,"text":"call the co-op","reminder":true}""")
        assertTrue(hers.reminder)

        val voice = Wire.json.decodeFromString(EventDto.serializer(),
            """{"id":814,"kind":"voice_note","at":1.0,"text":"aloud","file":{"id":"f_v","filename":"voice.ogg","content_type":"audio/ogg","size":9,"duration_s":7.4}}""")
        assertEquals(7.4, voice.file!!.durationS!!, 0.0)

        val notice = Wire.json.decodeFromString(EventDto.serializer(),
            """{"id":815,"kind":"notice","at":1.0,"level":"trouble","text":"That turn failed: x","future_field":1}""")
        assertEquals("trouble", notice.level)
    }

    @Test fun `server frames decode and unknown ones do not crash`() {
        val event = Frames.decode("""{"type":"event","event":{"id":1,"kind":"message","at":1.0,"text":"a"}}""")
        assertEquals(1L, (event as ServerFrame.Event).event.id)
        assertEquals(ServerFrame.Typing(true), Frames.decode("""{"type":"typing","on":true}"""))
        assertEquals(ServerFrame.Ping, Frames.decode("""{"type":"ping"}"""))
        assertEquals(ServerFrame.Unknown("later"), Frames.decode("""{"type":"later"}"""))
        assertNull(Frames.decode("not json"))
    }

    @Test fun `malformed frames decode to nothing instead of throwing`() {
        assertNull(Frames.decode("""{"type":"event","event":{"id":1}}"""))
        assertNull(Frames.decode("""{"type":"event","event":null}"""))
        assertNull(Frames.decode("""{"type":"event"}"""))
        assertNull(Frames.decode("""{"type":{}}"""))
        assertNull(Frames.decode("""{"type":"typing","on":[]}"""))
    }

    @Test fun `client frames are the exact strings the backend reads`() {
        assertEquals("""{"type":"ack","through":42}""", Frames.ack(42))
        assertEquals("""{"type":"typing"}""", Frames.TYPING)
        assertEquals("""{"type":"pong"}""", Frames.PONG)
    }

    @Test fun `requests encode snake_case`() {
        assertEquals("""{"code":"K7QM2XPA","device_name":"Pixel 9"}""",
            Wire.json.encodeToString(PairRequest.serializer(), PairRequest("K7QM2XPA", "Pixel 9")))
        assertEquals("""{"minutes":15}""", Wire.json.encodeToString(SnoozeRequest.serializer(), SnoozeRequest(15)))
    }
}
