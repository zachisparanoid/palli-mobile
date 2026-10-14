package com.palli.mobile.domain

import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.Wire
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The multipart field names are the wire contract with the server; these pin them one by one. */
class SendPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun send(text: String, attachments: List<LocalAttachment> = emptyList()) = PendingSendEntity(
        "c-9", text, Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), attachments),
        SendStates.QUEUED, null, 1L, 0)

    private fun disposition(part: okhttp3.MultipartBody.Part) = part.headers!!["Content-Disposition"]

    @Test fun `each kind goes to its own field, after the client id and the words`() {
        val photo = tmp.newFile("p.bin").path
        val note = tmp.newFile("n.bin").path
        val doc = tmp.newFile("d.bin").path
        val parts = SendProcessor.buildParts(send("look", listOf(
            LocalAttachment(photo, "image", "image/jpeg", "cat.jpg"),
            LocalAttachment(note, "voice", "audio/mp4", "note.m4a"),
            LocalAttachment(doc, "file", "application/pdf", "bill.pdf"),
        )))

        assertEquals(listOf(
            """form-data; name="client_id"""",
            """form-data; name="text"""",
            """form-data; name="images"; filename="cat.jpg"""",
            """form-data; name="voice"; filename="note.m4a"""",
            """form-data; name="files"; filename="bill.pdf"""",
        ), parts.map(::disposition))
        assertEquals(listOf("image/jpeg", "audio/mp4", "application/pdf"),
            parts.drop(2).map { it.body.contentType().toString() })
    }

    @Test fun `a send of only a photo has no text part, and neither does blank text`() {
        val photo = tmp.newFile("p.bin").path
        val photoOnly = SendProcessor.buildParts(send("", listOf(LocalAttachment(photo, "image", "image/png", "a.png"))))
        assertEquals(listOf("""form-data; name="client_id"""", """form-data; name="images"; filename="a.png""""),
            photoOnly.map(::disposition))

        val blank = SendProcessor.buildParts(send("  \n "))
        assertEquals(listOf("""form-data; name="client_id""""), blank.map(::disposition))
        assertNull(blank.firstOrNull { disposition(it)!!.contains("name=\"text\"") })
    }

    @Test fun `the client id part carries the id itself`() {
        val part = SendProcessor.buildParts(send("hi")).first()
        val sink = okio.Buffer().also { part.body.writeTo(it) }
        assertEquals("c-9", sink.readUtf8())
    }
}
