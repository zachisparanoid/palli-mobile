package com.palli.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<EventEntity>>

    /** −1 for an event already here: replays and the catch-up worker insert the same ids harmlessly. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Query("SELECT MAX(id) FROM events") suspend fun maxId(): Long?
    @Query("SELECT MIN(id) FROM events") suspend fun minId(): Long?
    @Query("SELECT COUNT(*) FROM events") suspend fun count(): Int
    @Query("DELETE FROM events") suspend fun clear()
}

@Dao
interface PendingSendDao {
    @Query("SELECT * FROM pending_sends ORDER BY createdAt") fun observeAll(): Flow<List<PendingSendEntity>>
    @Upsert suspend fun upsert(entity: PendingSendEntity)
    @Query("SELECT * FROM pending_sends WHERE clientId = :clientId") suspend fun get(clientId: String): PendingSendEntity?
    @Query("UPDATE pending_sends SET state = :state, failure = :failure WHERE clientId = :clientId")
    suspend fun setState(clientId: String, state: String, failure: String?)
    @Query("UPDATE pending_sends SET attempts = attempts + 1 WHERE clientId = :clientId")
    suspend fun bumpAttempts(clientId: String)
    /** The ids of the sends in any of these states, oldest first. */
    @Query("SELECT clientId FROM pending_sends WHERE state IN (:states) ORDER BY createdAt, clientId")
    suspend fun idsIn(states: List<String>): List<String>
    /** Moves the send to `to` only if it is still in one of the `from` states; returns the rows changed (0 if it settled meanwhile). */
    @Query("UPDATE pending_sends SET state = :to WHERE clientId = :clientId AND state IN (:from)")
    suspend fun moveIf(clientId: String, from: List<String>, to: String): Int
    @Query("DELETE FROM pending_sends WHERE clientId IN (:clientIds)") suspend fun deleteAll(clientIds: List<String>)
    @Query("DELETE FROM pending_sends") suspend fun clear()
}
