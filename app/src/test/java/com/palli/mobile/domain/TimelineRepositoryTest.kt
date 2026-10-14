package com.palli.mobile.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
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
}
