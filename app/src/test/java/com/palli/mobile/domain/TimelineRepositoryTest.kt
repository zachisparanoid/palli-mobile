package com.palli.mobile.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.local.EventEntity
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.domain.timeline.TimelineItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelineRepositoryTest {
    private lateinit var db: PalliDatabase
    private lateinit var repo: TimelineRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PalliDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TimelineRepository(db, OutgoingFiles(ApplicationProvider.getApplicationContext()))
    }

    @After fun tearDown() = db.close()

    private fun hers(id: Long) = EventDto(id = id, kind = EventKinds.MESSAGE, at = id.toDouble(), text = "t$id")

    @Test fun `a replayed event is stored once and reported new once`() = runTest {
        assertEquals(listOf(1L, 2L), repo.insertNew(listOf(hers(1), hers(2))).map { it.id })
        assertEquals(listOf(3L), repo.insertNew(listOf(hers(2), hers(3))).map { it.id })
        assertEquals(emptyList<EventDto>(), repo.insertNew(listOf(hers(1), hers(2), hers(3))))
        assertEquals(3L, repo.maxId())
    }

    @Test fun `your message arriving settles its pending send`() = runTest {
        db.pendingSends().upsert(PendingSendEntity("c1", "hi", "[]", SendStates.ACCEPTED, null, 1, 1))
        repo.insertNew(listOf(EventDto(id = 9, kind = EventKinds.USER_MESSAGE, at = 9.0, text = "hi", clientId = "c1")))
        assertEquals(emptyList<PendingSendEntity>(), db.pendingSends().observeAll().first())
        assertEquals(listOf("e9"), repo.observe(50).first().map { it.key })
    }

    @Test fun `the count is of every event held, not of the ones in view`() = runTest {
        assertEquals(0, repo.eventCount())
        repo.insertNew((1L..5L).map(::hers))
        repo.insertNew(listOf(hers(5), hers(6)))
        assertEquals(6, repo.eventCount())
        assertEquals(3, repo.observe(3).first().size)
    }

    @Test fun `a stored row that will not decode is left out, and the chat shows the rest`() = runTest {
        db.events().insertAll(listOf(
            EventEntity.from(hers(1)),
            EventEntity(id = 2, kind = EventKinds.MESSAGE, at = 2.0, clientId = null, json = "{not json"),
            EventEntity.from(hers(3)),
        ))
        db.pendingSends().upsert(PendingSendEntity("bad", "words kept", "{not a list", SendStates.FAILED, "x", 1, 1))
        db.pendingSends().upsert(PendingSendEntity("good", "fine", "[]", SendStates.QUEUED, null, 2, 0))

        val items = repo.observe(50).first()

        assertEquals(listOf("e1", "e3", "pbad", "pgood"), items.map { it.key })
        val bad = items[2] as TimelineItem.Mine
        assertEquals("words kept", bad.text)                          // its words still show, without the files it lost
        assertEquals(emptyList<LocalAttachment>(), bad.local)
    }
}
