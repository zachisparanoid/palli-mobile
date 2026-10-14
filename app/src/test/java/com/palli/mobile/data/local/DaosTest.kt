package com.palli.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaosTest {
    private lateinit var db: PalliDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PalliDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun event(id: Long) = EventEntity.from(EventDto(id = id, kind = EventKinds.MESSAGE, at = id.toDouble(), text = "t$id"))

    @Test fun `insertAll says which events were new, so a replay notifies nothing`() = runTest {
        assertEquals(listOf(5L), db.events().insertAll(listOf(event(5))))
        assertEquals(listOf(-1L, 6L), db.events().insertAll(listOf(event(5), event(6))))
    }

    @Test fun `latest is newest first, cursors are the extremes`() = runTest {
        db.events().insertAll((1L..5L).map(::event))
        assertEquals(listOf(5L, 4L, 3L), db.events().observeLatest(3).first().map { it.id })
        assertEquals(5L, db.events().maxId()); assertEquals(1L, db.events().minId())
        assertEquals("t3", db.events().observeLatest(5).first().first { it.id == 3L }.toDto().text)
        db.events().clear()
        assertNull(db.events().maxId())
    }

    @Test fun `pending sends move through their states and leave by client id`() = runTest {
        val p = PendingSendEntity("c1", "hi", "[]", SendStates.QUEUED, null, 1L, 0)
        db.pendingSends().upsert(p)
        db.pendingSends().setState("c1", SendStates.FAILED, "too large")
        db.pendingSends().bumpAttempts("c1")
        val stored = db.pendingSends().get("c1")!!
        assertEquals(SendStates.FAILED, stored.state); assertEquals("too large", stored.failure); assertEquals(1, stored.attempts)
        db.pendingSends().deleteAll(listOf("c1"))
        assertEquals(emptyList<PendingSendEntity>(), db.pendingSends().observeAll().first())
    }

    @Test fun `idsIn finds the sends in the asked states, oldest first`() = runTest {
        val dao = db.pendingSends()
        dao.upsert(PendingSendEntity("late", "c", "[]", SendStates.QUEUED, null, 30L, 0))
        dao.upsert(PendingSendEntity("mid", "b", "[]", SendStates.SENDING, null, 20L, 1))
        dao.upsert(PendingSendEntity("early", "a", "[]", SendStates.QUEUED, null, 10L, 0))
        dao.upsert(PendingSendEntity("bad", "d", "[]", SendStates.FAILED, "too large", 15L, 1))
        dao.upsert(PendingSendEntity("done", "e", "[]", SendStates.ACCEPTED, null, 5L, 1))

        assertEquals(listOf("early", "mid", "late"), dao.idsIn(listOf(SendStates.QUEUED, SendStates.SENDING)))
        assertEquals(listOf("bad"), dao.idsIn(listOf(SendStates.FAILED)))
        assertEquals(emptyList<String>(), dao.idsIn(emptyList()))
    }

    @Test fun `moveIf moves a send only while it is still in one of the asked states`() = runTest {
        val dao = db.pendingSends()
        dao.upsert(PendingSendEntity("out", "a", "[]", SendStates.SENDING, null, 1L, 1))
        dao.upsert(PendingSendEntity("done", "b", "[]", SendStates.ACCEPTED, null, 2L, 1))
        dao.upsert(PendingSendEntity("bad", "c", "[]", SendStates.FAILED, "too large", 3L, 1))
        val owed = listOf(SendStates.QUEUED, SendStates.SENDING)

        assertEquals(1, dao.moveIf("out", owed, SendStates.QUEUED))
        assertEquals(SendStates.QUEUED, dao.get("out")!!.state)
        assertEquals(1, dao.moveIf("out", owed, SendStates.QUEUED))                  // already queued still counts: it is owed
        assertEquals(0, dao.moveIf("done", owed, SendStates.QUEUED))
        assertEquals(0, dao.moveIf("bad", owed, SendStates.QUEUED))
        assertEquals(0, dao.moveIf("nobody", owed, SendStates.QUEUED))
        assertEquals(SendStates.ACCEPTED, dao.get("done")!!.state)
        assertEquals(SendStates.FAILED, dao.get("bad")!!.state); assertEquals("too large", dao.get("bad")!!.failure)
    }

    @Test fun `expireAccepted fails, as Not delivered, only the accepted sends accepted before the cut`() = runTest {
        val dao = db.pendingSends()
        dao.upsert(PendingSendEntity("old", "a", "[]", SendStates.ACCEPTED, null, 1L, 1, acceptedAt = 100L))
        dao.upsert(PendingSendEntity("young", "b", "[]", SendStates.ACCEPTED, null, 2L, 1, acceptedAt = 900L))
        dao.upsert(PendingSendEntity("upgraded", "c", "[]", SendStates.ACCEPTED, null, 3L, 1, acceptedAt = null))
        dao.upsert(PendingSendEntity("queued", "d", "[]", SendStates.QUEUED, null, 4L, 0))
        dao.upsert(PendingSendEntity("sending", "e", "[]", SendStates.SENDING, null, 5L, 1))
        dao.upsert(PendingSendEntity("refused", "f", "[]", SendStates.FAILED, "too large", 6L, 1))

        assertEquals(2, dao.expireAccepted(before = 500L))

        for (id in listOf("old", "upgraded")) {                  // an accepted row from before acceptedAt existed is old
            assertEquals(SendStates.FAILED, dao.get(id)!!.state)
            assertEquals(SendStates.NOT_DELIVERED, dao.get(id)!!.failure)
        }
        assertEquals(SendStates.ACCEPTED, dao.get("young")!!.state)
        assertEquals(SendStates.QUEUED, dao.get("queued")!!.state)
        assertEquals(SendStates.SENDING, dao.get("sending")!!.state)
        assertEquals("too large", dao.get("refused")!!.failure)
    }

    @Test fun `setAccepted marks the send accepted and when`() = runTest {
        val dao = db.pendingSends()
        dao.upsert(PendingSendEntity("c1", "hi", "[]", SendStates.SENDING, null, 1L, 1))
        dao.setAccepted("c1", at = 1_234L)
        assertEquals(SendStates.ACCEPTED, dao.get("c1")!!.state)
        assertEquals(1_234L, dao.get("c1")!!.acceptedAt)
        assertNull(dao.get("c1")!!.failure)
    }
}
