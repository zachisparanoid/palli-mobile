package com.palli.mobile.domain

import androidx.room.withTransaction
import com.palli.mobile.data.local.EventEntity
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.domain.timeline.TimelineReducer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimelineRepository @Inject constructor(
    private val db: PalliDatabase,
    private val outgoing: OutgoingFiles,
) {
    /**
     * The chat. A stored event that will not decode (written by a build whose idea of an event differs) is left out
     * rather than failing the whole flow, which would crash the chat on every open until unpair.
     */
    fun observe(window: Int): Flow<List<TimelineItem>> =
        combine(db.events().observeLatest(window), db.pendingSends().observeAll()) { events, pending ->
            TimelineReducer.reduce(events.mapNotNull { runCatching { it.toDto() }.getOrNull() }, pending)
        }

    /** Stores the events and returns only those that were new — a replay returns nothing,
     *  so nothing is notified twice. Your own message arriving settles its pending send. */
    suspend fun insertNew(events: List<EventDto>): List<EventDto> {
        if (events.isEmpty()) return emptyList()
        val settled = events.mapNotNull { it.clientId }
        val fresh = db.withTransaction {
            val ids = db.events().insertAll(events.map(EventEntity::from))
            if (settled.isNotEmpty()) db.pendingSends().deleteAll(settled)
            events.filterIndexed { index, _ -> ids[index] != -1L }
        }
        settled.forEach(outgoing::forget)
        return fresh
    }

    suspend fun maxId(): Long = db.events().maxId() ?: 0L
    suspend fun minId(): Long? = db.events().minId()
    /** How many events this phone holds, however many the chat is showing. */
    suspend fun eventCount(): Int = db.events().count()

    suspend fun clear() {
        db.withTransaction { db.events().clear(); db.pendingSends().clear() }
        outgoing.forgetAll()
    }
}
