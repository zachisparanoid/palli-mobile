package com.palli.mobile.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.palli.mobile.domain.SendLimits
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SendProcessorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var db: PalliDatabase
    private lateinit var sessions: SessionStore
    private lateinit var processor: SendProcessor
    private val unpaired = mutableListOf<String?>()

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PalliDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("p.preferences_pb")
        }
        sessions = SessionStore(ServerPrefs(store), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_test", 1, "Phone"))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        processor = SendProcessor(db.pendingSends(), sessions, ApiFactory(client),
            object : Unpairer { override suspend fun unpaired(reason: String?) { unpaired += reason } })
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun queue(id: String, text: String, attachments: List<LocalAttachment> = emptyList()) {
        db.pendingSends().upsert(PendingSendEntity(id, text,
            Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), attachments),
            SendStates.QUEUED, null, 1L, 0))
    }

    @Test fun `an accepted send carries its id, its words and its photo, with the token`() = runTest {
        val photo = tmp.newFile("cat.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        queue("c-1", "look", listOf(LocalAttachment(photo.path, "image", "image/jpeg", "cat.jpg")))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"client_id":"c-1"}"""))

        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-1"))
        assertEquals(SendStates.ACCEPTED, db.pendingSends().get("c-1")!!.state)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("Bearer pmd_test", request.getHeader("Authorization"))
        assertTrue(body.contains("name=\"client_id\"") && body.contains("c-1"))
        assertTrue(body.contains("name=\"text\"") && body.contains("look"))
        assertTrue(body.contains("name=\"images\"; filename=\"cat.jpg\""))
    }

    @Test fun `a retry after a lost answer sends the very same client id`() = runTest {
        queue("c-2", "again?")
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":{"reason":"door_closed"}}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"client_id":"c-2"}"""))
        assertEquals(SendProcessor.Outcome.RETRY, processor.process("c-2"))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-2"))
        val first = server.takeRequest().body.readUtf8(); val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("c-2") && second.contains("c-2"))
    }

    @Test fun `too large or refused fails for good, with the reason`() = runTest {
        queue("c-3", "big")
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"detail":"cat.jpg is over 20 MB."}"""))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-3"))
        val failed = db.pendingSends().get("c-3")!!
        assertEquals(SendStates.FAILED, failed.state); assertEquals("cat.jpg is over 20 MB.", failed.failure)
    }

    @Test fun `a send over PaLLi's limits fails for good without an upload, so a broken pipe is never retried`() = runTest {
        fun sparse(name: String, size: Long) = tmp.newFile(name).apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(size) }
        }
        val onePart = sparse("one.bin", SendLimits.MAX_PART_BYTES + 1)
        queue("c-7", "", listOf(LocalAttachment(onePart.path, "file", "application/octet-stream", "one.bin")))
        val parts = (1..4).map { sparse("p$it.bin", 16L * 1024 * 1024) }
        queue("c-8", "", parts.map { LocalAttachment(it.path, "file", "application/octet-stream", it.name) })

        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-7"))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-8"))
        for (id in listOf("c-7", "c-8")) {
            val failed = db.pendingSends().get(id)!!
            assertEquals(SendStates.FAILED, failed.state)
            assertEquals(SendProcessor.TOO_BIG, failed.failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `an empty or invalid message fails for good with the server's sentence`() = runTest {
        queue("c-6", "")
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"Say something, or attach something."}"""))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-6"))
        val failed = db.pendingSends().get("c-6")!!
        assertEquals(SendStates.FAILED, failed.state)
        assertEquals("Say something, or attach something.", failed.failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a revoked phone unpairs instead of retrying`() = runTest {
        queue("c-4", "hello?")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"This phone is not paired."}"""))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-4"))
        assertEquals(listOf<String?>("This phone was unpaired."), unpaired)
    }

    @Test fun `a file gone from the phone fails without a request`() = runTest {
        queue("c-5", "", listOf(LocalAttachment("/nope/missing.jpg", "image", "image/jpeg", "missing.jpg")))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("c-5"))
        assertEquals(SendStates.FAILED, db.pendingSends().get("c-5")!!.state)
        assertEquals(0, server.requestCount)
    }
}
