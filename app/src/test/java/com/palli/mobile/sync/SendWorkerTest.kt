package com.palli.mobile.sync

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.domain.SendProcessor
import com.palli.mobile.domain.SendRepository
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue's one worker, which drains it: what was written first reaches PaLLi first, one send at a time. The real
 * worker over a real database, the real processor and a (mock) PaLLi that notes the order sends arrive in and whether
 * two were ever in flight at once.
 */
@RunWith(RobolectricTestRunner::class)
class SendWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var db: PalliDatabase
    private lateinit var sessions: SessionStore
    private lateinit var processor: SendProcessor
    private val unpaired = mutableListOf<String?>()

    /** The client ids in the order PaLLi saw them, and the most sends she ever had in flight at once. */
    private val arrived: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()
    /** PaLLi's answer to each send, by client id; 202 unless set. */
    private val answers = mutableMapOf<String, MockResponse>()
    /** Held open while the first send is in flight, so a second one started meanwhile would be caught at it. */
    private val holdFirst = CountDownLatch(1)
    /** How long the first send is held, at most. */
    private var holdMs = 300L
    /** Counted down when the first send reaches PaLLi. */
    private val firstArrived = CountDownLatch(1)

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = inFlight.incrementAndGet()
                mostInFlight.accumulateAndGet(now, ::maxOf)
                try {
                    val id = Regex("name=\"client_id\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]+)")
                        .find(request.body.readUtf8())!!.groupValues[1]
                    arrived += id
                    if (arrived.size == 1) { firstArrived.countDown(); holdFirst.await(holdMs, TimeUnit.MILLISECONDS) }
                    return answers[id] ?: MockResponse().setResponseCode(202).setBody("""{"client_id":"$id"}""")
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        sessions = SessionStore(ServerPrefs(ServerPrefs.store(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("p.preferences_pb")
        }), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_test", 1, "Phone"))
        processor = processorOver(db.pendingSends())
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun processorOver(dao: PendingSendDao) = SendProcessor(dao, sessions,
        ApiFactory(OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()),
        object : Unpairer { override suspend fun unpaired(reason: String?) { unpaired += reason } })

    private fun worker(with: SendProcessor = processor, tags: List<String> = listOf(SendWorker.TAG)): SendWorker =
        TestListenableWorkerBuilder<SendWorker>(context)
            .setTags(tags)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SendWorker(appContext, workerParameters, with)
            }).build()

    private suspend fun row(id: String, createdAt: Long, state: String = SendStates.QUEUED, failure: String? = null,
                            attachmentsJson: String = "[]") =
        db.pendingSends().upsert(PendingSendEntity(id, "said $id", attachmentsJson, state, failure, createdAt, 0))

    private suspend fun state(id: String) = db.pendingSends().get(id)?.state

    @Test fun `two sends queued while offline reach PaLLi in the order they were written, one at a time`() = runTest {
        row("zebra", createdAt = 1)                                 // written first, though its id sorts last
        row("aardvark", createdAt = 2)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(listOf("zebra", "aardvark"), arrived)
        assertEquals("never two in flight at once", 1, mostInFlight.get())
        assertEquals(SendStates.ACCEPTED, state("zebra"))
        assertEquals(SendStates.ACCEPTED, state("aardvark"))
    }

    /** The real WorkManager on a real pool of worker threads, its drains made with [processor]. */
    private fun realWorkManager(): WorkManager {
        val config = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(4))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SendWorker(appContext, workerParameters, processor)
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
        return WorkManager.getInstance(context)
    }

    private fun WorkManager.drains(): List<WorkInfo> = getWorkInfosForUniqueWork(SendWorker.UNIQUE).get()

    /**
     * The network is there: every drain that comes up may run (the ones behind it wait for it to finish). Runs until
     * [done], or for [forMs] at most. A drain waiting out a backoff is not run: the test driver keeps to it.
     */
    private fun WorkManager.letRun(forMs: Long = 15_000, done: () -> Boolean = { drains().all { it.state.isFinished } }) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val deadline = System.currentTimeMillis() + forMs
        while (System.currentTimeMillis() < deadline && !done()) {
            drains().filter { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 }
                .forEach { runCatching { driver.setAllConstraintsMet(it.id) } }
            shadowOf(Looper.getMainLooper()).idle()                // WorkManager starts its workers from the main thread
            Thread.sleep(20)
        }
    }

    @Test fun `through WorkManager on real worker threads, sends written offline go when the network returns, in order, one at a time`() {
        val work = realWorkManager()
        val sends = SendRepository(db.pendingSends()) { SendWorker.enqueue(work) }

        val ids = runBlocking { listOf(sends.enqueue("one"), sends.enqueue("two"), sends.enqueue("three")) }   // offline
        assertTrue(arrived.isEmpty())

        work.letRun()

        assertEquals(ids, arrived)
        assertEquals("never two in flight at once", 1, mostInFlight.get())
        assertTrue(work.getWorkInfosForUniqueWork(SendWorker.UNIQUE).get().all { it.state == WorkInfo.State.SUCCEEDED })
        runBlocking { ids.forEach { assertEquals(SendStates.ACCEPTED, state(it)) } }
    }

    // ---- a drain waiting out its backoff ----

    @Test fun `the drain backs off linearly from ten seconds, so an hour's outage leaves a wait of minutes, not most of an hour`() {
        val spec = SendWorker.request().workSpec
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(10_000L, spec.backoffDelayDuration)
    }

    @Test fun `a send typed once PaLLi is back goes when she is known to answer, without waiting out the drain's backoff`() {
        val work = realWorkManager()
        val sends = SendRepository(db.pendingSends(), restartWaitingDrain = { SendWorker.restartIfWaiting(work) }) {
            SendWorker.enqueue(work)
        }
        answers["first"] = MockResponse().setResponseCode(503).setBody("""{"detail":{"reason":"door_closed"}}""")
        runBlocking { sends.enqueue("first", clientId = "first") }
        work.letRun { work.drains().any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } }
        assertEquals(listOf("first"), arrived)                     // the door was shut: the drain waits out its backoff

        answers.remove("first")                                    // the door opens, and he types again
        runBlocking { sends.enqueue("second", clientId = "second") }
        work.letRun(forMs = 1_000)
        assertEquals("both wait behind the backed-off drain", listOf("first"), arrived)

        runBlocking { sends.restartStalled() }                     // the stream connects, or /me says the door is open
        work.letRun(forMs = 5_000)                                 // well inside the ten seconds the backoff would take

        assertEquals(listOf("first", "first", "second"), arrived)
        runBlocking { listOf("first", "second").forEach { assertEquals(SendStates.ACCEPTED, state(it)) } }
        assertEquals("never two in flight at once", 1, mostInFlight.get())
    }

    @Test fun `a drain that is sending is never replaced`() {
        val work = realWorkManager()
        holdMs = 10_000                                            // the first send stays in flight until let go
        runBlocking { row("first", createdAt = 1) }
        SendWorker.enqueue(work)
        work.letRun(forMs = 10_000) { firstArrived.count == 0L }
        val running = work.drains().single()
        assertEquals(WorkInfo.State.RUNNING, running.state)

        assertEquals(false, runBlocking { SendWorker.restartIfWaiting(work) })
        assertEquals(listOf(running.id), work.drains().map { it.id })
        assertEquals(WorkInfo.State.RUNNING, work.drains().single().state)

        holdFirst.countDown()
        work.letRun()
        assertEquals(listOf("first"), arrived)                     // sent once, by the drain that was running
        assertEquals(WorkInfo.State.SUCCEEDED, work.getWorkInfoById(running.id).get()!!.state)
    }

    // ---- a send that always fails ----

    @Test fun `five server errors fail a send for good, and the next one goes`() = runTest {
        row("stuck", createdAt = 1)
        row("next", createdAt = 2)
        answers["stuck"] = MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}""")

        repeat(4) { n ->
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
            assertEquals(SendStates.QUEUED, state("stuck"))
            assertEquals(n + 1, db.pendingSends().get("stuck")!!.attempts)
        }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(SendStates.FAILED, state("stuck"))
        assertEquals(SendProcessor.COULD_NOT_TAKE, db.pendingSends().get("stuck")!!.failure)
        assertEquals(SendStates.ACCEPTED, state("next"))
        assertEquals(listOf("stuck", "stuck", "stuck", "stuck", "stuck", "next"), arrived)
    }

    @Test fun `a shut door or a missing brain says nothing about the send, however often`() = runTest {
        row("waiting", createdAt = 1)
        for (reason in listOf("door_closed", "no_brain")) {
            answers["waiting"] = MockResponse().setResponseCode(503).setBody("""{"detail":{"reason":"$reason"}}""")
            repeat(5) { assertEquals(ListenableWorker.Result.retry(), worker().doWork()) }
        }
        assertEquals(SendStates.QUEUED, state("waiting"))
        assertEquals(0, db.pendingSends().get("waiting")!!.attempts)
    }

    @Test fun `nor does a rate limit or the host gate, which are about the phone`() = runTest {
        row("waiting", createdAt = 1)
        for (code in listOf(429, 403)) {
            answers["waiting"] = MockResponse().setResponseCode(code).setBody("""{"detail":"not now"}""")
            repeat(5) { assertEquals(ListenableWorker.Result.retry(), worker().doWork()) }
        }
        assertEquals(SendStates.QUEUED, state("waiting"))
        assertEquals(0, db.pendingSends().get("waiting")!!.attempts)
    }

    @Test fun `nor does PaLLi being out of reach, however long`() = runTest {
        row("waiting", createdAt = 1)
        server.shutdown()                                          // nothing listens: the connection is refused
        repeat(5) { assertEquals(ListenableWorker.Result.retry(), worker().doWork()) }
        assertEquals(SendStates.QUEUED, state("waiting"))
        assertEquals(0, db.pendingSends().get("waiting")!!.attempts)
    }

    @Test fun `two written in the same millisecond go in client id order, every time`() = runTest {
        row("b", createdAt = 5)
        row("a", createdAt = 5)
        worker().doWork()
        assertEquals(listOf("a", "b"), arrived)
    }

    @Test fun `a permanent failure on the first does not block the second`() = runTest {
        row("first", createdAt = 1)
        row("second", createdAt = 2)
        answers["first"] = MockResponse().setResponseCode(413).setBody("""{"detail":"cat.jpg is over 20 MB."}""")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(listOf("first", "second"), arrived)
        assertEquals(SendStates.FAILED, state("first"))
        assertEquals("cat.jpg is over 20 MB.", db.pendingSends().get("first")!!.failure)
        assertEquals(SendStates.ACCEPTED, state("second"))
    }

    @Test fun `a transient failure on the first holds the second behind it, and the drain is retried`() = runTest {
        row("first", createdAt = 1)
        row("second", createdAt = 2)
        answers["first"] = MockResponse().setResponseCode(503).setBody("""{"detail":{"reason":"door_closed"}}""")

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        assertEquals(listOf("first"), arrived)                     // the second was not sent ahead of it
        assertEquals(SendStates.QUEUED, state("first"))
        assertEquals(SendStates.QUEUED, state("second"))

        answers.remove("first")                                    // the door opens; the retried drain sends both, in order
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(listOf("first", "first", "second"), arrived)
    }

    @Test fun `a retry of an old failed send sends it, and nothing already sent goes again or out of turn`() = runTest {
        row("old", createdAt = 1, state = SendStates.FAILED, failure = SendStates.NOT_DELIVERED)
        row("sent", createdAt = 2, state = SendStates.ACCEPTED)
        row("new", createdAt = 3)

        SendRepository(db.pendingSends()) { }.retry("old")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(listOf("old", "new"), arrived)                // written first, so it goes first; "sent" never again
        assertEquals(SendStates.ACCEPTED, state("old"))
        assertEquals(SendStates.ACCEPTED, state("new"))
    }

    @Test fun `a send left mid-flight by a dead process goes before the ones written after it`() = runTest {
        row("interrupted", createdAt = 1, state = SendStates.SENDING)
        row("later", createdAt = 2)
        worker().doWork()
        assertEquals(listOf("interrupted", "later"), arrived)
    }

    @Test fun `a send that cannot be read back fails for good and does not hold the queue`() = runTest {
        row("garbled", createdAt = 1, attachmentsJson = "{not a list")
        row("fine", createdAt = 2)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(listOf("fine"), arrived)
        assertEquals(SendStates.FAILED, state("garbled"))
        assertEquals(SendProcessor.UNREADABLE, db.pendingSends().get("garbled")!!.failure)
    }

    @Test fun `a revoked phone stops the drain, without failing it`() = runTest {
        row("first", createdAt = 1)
        row("second", createdAt = 2)
        answers["first"] = MockResponse().setResponseCode(401).setBody("""{"detail":"This phone is not paired."}""")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(listOf("first"), arrived)
        assertEquals(listOf<String?>("This phone was unpaired."), unpaired)
    }

    @Test fun `the drain never fails, which would cancel the drains queued behind it - trouble here is retried`() = runTest {
        row("first", createdAt = 1)
        val real = db.pendingSends()
        val broken = object : PendingSendDao by real {
            override suspend fun idsIn(states: List<String>): List<String> = throw IllegalStateException("disk I/O error")
        }
        assertEquals(ListenableWorker.Result.retry(), worker(with = processorOver(broken)).doWork())
        assertTrue(arrived.isEmpty())
    }

    @Test fun `a per-send work left by an earlier build sends nothing - the drain does the sending`() = runTest {
        row("first", createdAt = 1)
        assertEquals(ListenableWorker.Result.success(), worker(tags = listOf(SendWorker.OLD_TAG)).doWork())
        assertTrue(arrived.isEmpty())
        assertEquals(SendStates.QUEUED, state("first"))
    }

    @Test fun `files go in the send they were written with`() = runTest {
        val photo = tmp.newFile("cat.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        row("pic", createdAt = 1, attachmentsJson = Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()),
            listOf(LocalAttachment(photo.path, "image", "image/jpeg", "cat.jpg"))))
        worker().doWork()
        assertEquals(listOf("pic"), arrived)
        assertEquals(SendStates.ACCEPTED, state("pic"))
    }
}
