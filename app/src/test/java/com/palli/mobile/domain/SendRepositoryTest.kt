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

/** The real SendRepository over a real database and the (test) WorkManager. */
@RunWith(RobolectricTestRunner::class)
class SendRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PalliDatabase
    private lateinit var work: WorkManager
    private lateinit var repo: SendRepository

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        work = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        repo = SendRepository(db.pendingSends(), work)
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

        SendRepository(dao, work).resumeUnsettled()

        assertEquals(SendStates.ACCEPTED, real.get("racing")!!.state)                     // not dragged back to queued
        assertEquals(emptyList<WorkInfo.State>(), workStates("racing"))                  // and no second send scheduled for it
        assertEquals(listOf(WorkInfo.State.ENQUEUED), workStates("steady"))
    }
}
