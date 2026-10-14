package com.palli.mobile.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.domain.timeline.SendStatus
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.sync.CatchUpProcessor
import com.palli.mobile.sync.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A send PaLLi answered 202 but never recorded (she died mid-intake): once the timeline is known fresh, a send accepted
 * more than five minutes ago whose own message is still not in it never will be, and it becomes "Not delivered", which
 * a tap sends again under the same client id. Over a real database, catch-up, queue and (mock) PaLLi.
 */
@RunWith(RobolectricTestRunner::class)
class UnrecordedSendsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var db: PalliDatabase
    private lateinit var sessions: SessionStore
    private lateinit var timeline: TimelineRepository
    private lateinit var sends: SendRepository
    private lateinit var processor: SendProcessor
    private lateinit var api: ApiFactory
    private val noUnpair = object : Unpairer { override suspend fun unpaired(reason: String?) = Unit }
    private val minute = 60_000L

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        sessions = SessionStore(ServerPrefs(ServerPrefs.store(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("p.preferences_pb")
        }), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_test", 1, "Phone"))
        api = ApiFactory(OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build())
        timeline = TimelineRepository(db, OutgoingFiles(context))
        sends = SendRepository(db.pendingSends()) { }               // the work itself is run by hand below
        processor = SendProcessor(db.pendingSends(), sessions, api, noUnpair)
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun catchUp() = CatchUpProcessor(
        currentSession = { sessions.current }, apis = api, insertNew = timeline::insertNew, maxId = timeline::maxId,
        sink = object : EventSink { override suspend fun onNewEvents(events: List<EventDto>) = Unit },
        unpairer = noUnpair, expireUnrecorded = { sends.expireUnrecorded() },
    )

    private suspend fun accepted(id: String, ago: Long) = db.pendingSends().upsert(PendingSendEntity(
        id, "said $id", "[]", SendStates.ACCEPTED, null, createdAt = 1L, attempts = 1,
        acceptedAt = System.currentTimeMillis() - ago))

    private fun page(json: String) = MockResponse().setBody(json)

    @Test fun `after a catch-up, a send accepted over five minutes ago and never recorded is Not delivered, a younger one is left, and one that arrived is gone`() = runTest {
        accepted("lost", ago = 6 * minute)
        accepted("young", ago = 1 * minute)
        accepted("arrived", ago = 10 * minute)
        server.enqueue(page("""{"events":[{"id":7,"kind":"user_message","at":1.0,"text":"said arrived","client_id":"arrived"}],"more":false}"""))

        assertEquals(true, catchUp().run())

        val lost = db.pendingSends().get("lost")!!
        assertEquals(SendStates.FAILED, lost.state)
        assertEquals(SendStates.NOT_DELIVERED, lost.failure)
        assertEquals(SendStates.ACCEPTED, db.pendingSends().get("young")!!.state)
        assertNull(db.pendingSends().get("arrived"))                     // settled by its own message, as before

        // and the chat says so, with the retry the bubble always offers
        val bubble = timeline.observe(50).first().filterIsInstance<TimelineItem.Mine>().single { it.key == "plost" }
        assertEquals(SendStatus.FAILED, bubble.status)
        assertEquals("Not delivered", bubble.failure)
    }

    @Test fun `a catch-up that could not reach PaLLi expires nothing, since the timeline is not known fresh`() = runTest {
        accepted("lost", ago = 6 * minute)
        server.shutdown()
        assertEquals(false, catchUp().run())
        assertEquals(SendStates.ACCEPTED, db.pendingSends().get("lost")!!.state)
    }

    @Test fun `a catch-up of several pages expires only once it has the last one`() = runTest {
        accepted("late", ago = 6 * minute)
        server.enqueue(page("""{"events":[{"id":1,"kind":"message","at":1.0,"text":"a"}],"more":true}"""))
        server.enqueue(page("""{"events":[{"id":2,"kind":"user_message","at":2.0,"text":"said late","client_id":"late"}],"more":false}"""))

        assertEquals(true, catchUp().run())
        assertNull(db.pendingSends().get("late"))                          // its message was on the second page
    }

    @Test fun `a Not delivered send is sent again under the same client id`() = runTest {
        accepted("lost", ago = 6 * minute)
        server.enqueue(page("""{"events":[],"more":false}"""))
        catchUp().run()
        assertEquals(SendStates.FAILED, db.pendingSends().get("lost")!!.state)

        sends.retry("lost")
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"client_id":"lost"}"""))
        assertEquals(SendProcessor.Outcome.DONE, processor.process("lost"))

        server.takeRequest()                                                // the catch-up's page
        val resend = server.takeRequest().body.readUtf8()
        assertTrue(resend, resend.contains("name=\"client_id\"") && resend.contains("\r\n\r\nlost\r\n"))
        assertEquals(SendStates.ACCEPTED, db.pendingSends().get("lost")!!.state)
        assertTrue(db.pendingSends().get("lost")!!.acceptedAt!! > System.currentTimeMillis() - minute)   // a fresh five minutes
    }
}
