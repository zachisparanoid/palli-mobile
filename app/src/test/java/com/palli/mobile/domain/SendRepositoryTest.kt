package com.palli.mobile.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.sync.SendWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The real SendRepository over a real database and the (test) WorkManager. */
@RunWith(RobolectricTestRunner::class)
class SendRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PalliDatabase
    private lateinit var work: WorkManager
    private lateinit var repo: SendRepository
    private val outgoing = OutgoingFiles(context)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        work = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        repo = SendRepository(db.pendingSends(), work, outgoing)
    }

    @After fun tearDown() = db.close()

    private suspend fun row(id: String, state: String, failure: String? = null, createdAt: Long = 1L) =
        db.pendingSends().upsert(PendingSendEntity(id, "hi $id", "[]", state, failure, createdAt, 1))

    private fun workStates(id: String): List<WorkInfo.State> =
        work.getWorkInfosForUniqueWork("send-$id").get().map { it.state }

    @Test fun `a send stranded without its work gets the work again, and one stranded mid-send is queued again first`() = runTest {
        row("orphan", SendStates.QUEUED, createdAt = 1)          // the row was written, the process died before the work was
        row("midflight", SendStates.SENDING, createdAt = 2)      // the process died while the send was out
        row("covered", SendStates.QUEUED, createdAt = 3)         // its work survived: WorkManager keeps its own
        SendWorker.enqueue(work, "covered")
        row("refused", SendStates.FAILED, "too large", createdAt = 4)
        row("accepted", SendStates.ACCEPTED, createdAt = 5)

        repo.resumeUnsettled()

        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("orphan"))
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("midflight"))
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("covered"))                  // kept, not doubled
        assertEquals(emptyList<WorkInfo.State>(), workStates("refused"))                      // settled rows get nothing
        assertEquals(emptyList<WorkInfo.State>(), workStates("accepted"))
        assertEquals(SendStates.QUEUED, db.pendingSends().get("orphan")!!.state)
        assertEquals(SendStates.QUEUED, db.pendingSends().get("midflight")!!.state)
        assertEquals(SendStates.FAILED, db.pendingSends().get("refused")!!.state)
        assertEquals("too large", db.pendingSends().get("refused")!!.failure)
        assertEquals(SendStates.ACCEPTED, db.pendingSends().get("accepted")!!.state)
    }

    @Test fun `resuming twice schedules nothing twice, and with nothing owed it does nothing`() = runTest {
        repo.resumeUnsettled()
        assertEquals(0, work.getWorkInfosByTag(SendWorker.TAG).get().size)

        row("c1", SendStates.SENDING)
        repo.resumeUnsettled()
        repo.resumeUnsettled()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("c1"))
        assertEquals(1, work.getWorkInfosByTag(SendWorker.TAG).get().size)
    }

    @Test fun `a send settled between the read and the write is not queued again or sent twice`() = runTest {
        row("racing", SendStates.SENDING, createdAt = 1)
        row("steady", SendStates.QUEUED, createdAt = 2)
        val real = db.pendingSends()
        // The ids are read, then a running SendWorker finishes "racing" before resume gets to write.
        val dao = object : PendingSendDao by real {
            override suspend fun idsIn(states: List<String>): List<String> =
                real.idsIn(states).also { real.setState("racing", SendStates.ACCEPTED, null) }
        }

        SendRepository(dao, work, outgoing).resumeUnsettled()

        assertEquals(SendStates.ACCEPTED, real.get("racing")!!.state)                     // not dragged back to queued
        assertEquals(emptyList<WorkInfo.State>(), workStates("racing"))                  // and no second send scheduled for it
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("steady"))
    }

    private val boom = IllegalStateException("work manager is not initialised")

    @Test fun `a send whose work cannot be scheduled is kept as a failed bubble, and enqueue does not throw`() = runTest {
        val id = SendRepository(db.pendingSends()) { throw boom }.enqueue("  hello ", clientId = "c1")

        assertEquals("c1", id)
        val row = db.pendingSends().get("c1")!!
        assertEquals("hello", row.text)
        assertEquals(SendStates.FAILED, row.state)
        assertEquals(SendRepository.COULD_NOT_START, row.failure)
    }

    @Test fun `a send that could not be written throws, and leaves nothing behind to duplicate it`() = runTest {
        val real = db.pendingSends()
        val dao = object : PendingSendDao by real {
            override suspend fun upsert(entity: PendingSendEntity) { throw java.io.IOException("disk full") }
        }
        var scheduled = 0

        val failure = runCatching { SendRepository(dao) { scheduled++ }.enqueue("hello", clientId = "c1") }.exceptionOrNull()

        assertEquals(java.io.IOException::class, failure!!::class)
        assertEquals(null, real.get("c1"))
        assertEquals(0, scheduled)
    }

    @Test fun `if even the failed mark cannot be written the row stays queued for the next start, and enqueue still does not throw`() = runTest {
        val real = db.pendingSends()
        val dao = object : PendingSendDao by real {
            override suspend fun setState(clientId: String, state: String, failure: String?) { throw java.io.IOException("disk full") }
        }

        SendRepository(dao) { throw boom }.enqueue("hello", clientId = "c1")

        assertEquals(SendStates.QUEUED, real.get("c1")!!.state)
        SendRepository(real, work, outgoing).resumeUnsettled()
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("c1"))
    }

    @Test fun `a retry whose work cannot be scheduled goes back to failed and says so`() = runTest {
        row("c1", SendStates.FAILED, "PaLLi's door is shut")

        val failure = runCatching { SendRepository(db.pendingSends()) { throw boom }.retry("c1") }.exceptionOrNull()

        assertEquals(boom, failure)
        assertEquals(SendStates.FAILED, db.pendingSends().get("c1")!!.state)
        assertEquals(SendRepository.COULD_NOT_START, db.pendingSends().get("c1")!!.failure)
    }

    @Test fun `a retry queues the row and schedules its work`() = runTest {
        row("c1", SendStates.FAILED, "PaLLi's door is shut")
        repo.retry("c1")
        assertEquals(SendStates.QUEUED, db.pendingSends().get("c1")!!.state)
        assertEquals(null, db.pendingSends().get("c1")!!.failure)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("c1"))
    }

    // ---- removing a failed send ----

    @Test fun `removing a failed send deletes its row, its folder and its work`() = runTest {
        row("c1", SendStates.FAILED, "Too long to send as a message: over 64 KB of words.")
        val photo = File(outgoing.dirFor("c1"), "cat.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        SendWorker.enqueue(work, "c1")                              // say a retry had scheduled it once
        row("c2", SendStates.FAILED, "Not delivered")               // another, left alone

        repo.remove("c1")

        assertEquals(null, db.pendingSends().get("c1"))
        assertEquals(false, photo.exists())
        assertEquals(false, File(context.filesDir, "outgoing/c1").exists())
        assertEquals(listOf(WorkInfo.State.CANCELLED), workStates("c1"))
        assertEquals(SendStates.FAILED, db.pendingSends().get("c2")!!.state)
    }

    @Test fun `a send that is on its way, or already with PaLLi, is never removed`() = runTest {
        for ((id, state) in listOf("q" to SendStates.QUEUED, "s" to SendStates.SENDING, "a" to SendStates.ACCEPTED)) {
            row(id, state)
            val file = File(outgoing.dirFor(id), "note.m4a").apply { writeBytes(byteArrayOf(9)) }
            SendWorker.enqueue(work, id)

            repo.remove(id)

            assertEquals(state, db.pendingSends().get(id)!!.state)
            assertEquals(true, file.exists())
            assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates(id))
        }
    }
}
