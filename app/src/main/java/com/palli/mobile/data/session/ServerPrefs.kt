package com.palli.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this phone remembers: the pairing, and a few choices and answers.
 *
 * DataStore is read once, on first use, and written through [update]; every read and every watcher is served from the copy
 * held here. DataStore 1.1.7, the version this app is pinned to, can lose an update for a collector that starts while a write
 * is in flight (the collector is handed the old value and never the new one: a switch that does not move, a connection that
 * does not follow its setting). So no collector is ever started on it after the first read, and that first read and every
 * write take the same lock, so they never overlap. `ServerPrefsOverlapTest` is the proof.
 */
@Singleton
class ServerPrefs @Inject constructor(private val store: DataStore<Preferences>) {
    /** [unpairedReason] says why this phone is not paired, when it was not his choice: it outlives the process. */
    data class Stored(
        val baseUrl: String?, val deviceId: Long?, val deviceName: String?, val tokenBlob: String?, val unpairedReason: String? = null,
    )

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val DEVICE_ID = longPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TOKEN_BLOB = stringPreferencesKey("token_blob")
        val STAY_CONNECTED = booleanPreferencesKey("stay_connected")
        val ASKED_BATTERY = booleanPreferencesKey("asked_battery")
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val LAST_API_VERSION = intPreferencesKey("last_api_version")
        val UNPAIRED_REASON = stringPreferencesKey("unpaired_reason")
    }

    /** The stored preferences, as last read or written here. Null until the first read. */
    private val cache = MutableStateFlow<Preferences?>(null)
    private val lock = Mutex()

    /** The preferences now. The first call reads DataStore, under the lock so that no write is in flight at the time. */
    private suspend fun snapshot(): Preferences =
        cache.value ?: lock.withLock { cache.value ?: store.data.first().also { cache.value = it } }

    /**
     * Applies [change] and keeps the copy in step. The write is not cancelled half way: DataStore finishes a write it has
     * begun, and a copy that did not hear of it would go on answering with the old value. But a caller that was cancelled
     * meanwhile is told so once it is done, as with any suspending call, so that a screen that has been left does not go on
     * to what it was going to do next (the chat launching a permission question from a launcher it no longer has).
     */
    private suspend fun update(change: (MutablePreferences) -> Unit) {
        withContext(NonCancellable) {
            lock.withLock {
                if (cache.value == null) cache.value = store.data.first()
                cache.value = store.edit(change)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    /** The preferences now, and again at each change. */
    private fun preferences(): Flow<Preferences> = flow {
        snapshot()
        emitAll(cache.filterNotNull())
    }

    suspend fun read(): Stored = snapshot().let {
        Stored(it[Keys.BASE_URL], it[Keys.DEVICE_ID], it[Keys.DEVICE_NAME], it[Keys.TOKEN_BLOB], it[Keys.UNPAIRED_REASON])
    }

    /** A new pairing. Whatever reason the last one ended for is over with it. */
    suspend fun write(baseUrl: String, deviceId: Long, deviceName: String, tokenBlob: String) {
        update {
            it[Keys.BASE_URL] = baseUrl; it[Keys.DEVICE_ID] = deviceId
            it[Keys.DEVICE_NAME] = deviceName; it[Keys.TOKEN_BLOB] = tokenBlob
            it.remove(Keys.UNPAIRED_REASON)
        }
    }

    /** The pairing is over, and [reason] (null when it was his choice) is why: one write, so the two never disagree. */
    suspend fun clearPairing(reason: String? = null) {
        update {
            it.remove(Keys.BASE_URL); it.remove(Keys.DEVICE_ID); it.remove(Keys.DEVICE_NAME); it.remove(Keys.TOKEN_BLOB)
            it.remove(Keys.LAST_API_VERSION)           // it was that server's; the next one says its own
            if (reason != null) it[Keys.UNPAIRED_REASON] = reason else it.remove(Keys.UNPAIRED_REASON)
        }
    }

    /** Why the pairing this phone holds cannot be used (its token will not unseal), for the Pair screen to say. */
    suspend fun setUnpairedReason(reason: String) { update { it[Keys.UNPAIRED_REASON] = reason } }

    /** On by default: the persistent connection is how her messages arrive at once. */
    val stayConnected: Flow<Boolean> = preferences().map { it[Keys.STAY_CONNECTED] ?: true }
    suspend fun setStayConnected(on: Boolean) { update { it[Keys.STAY_CONNECTED] = on } }

    suspend fun askedBattery(): Boolean = snapshot()[Keys.ASKED_BATTERY] ?: false
    suspend fun markAskedBattery() { update { it[Keys.ASKED_BATTERY] = true } }

    /** Whether the system's notification question has been put to the owner. Asked once ever, so a denial is remembered. */
    suspend fun askedNotifications(): Boolean = snapshot()[Keys.ASKED_NOTIFICATIONS] ?: false
    suspend fun markAskedNotifications() { update { it[Keys.ASKED_NOTIFICATIONS] = true } }

    /** The `api_version` the paired PaLLi last reported on `/me`; null before any answer. Read again after a cold start. */
    suspend fun lastApiVersion(): Int? = snapshot()[Keys.LAST_API_VERSION]
    suspend fun setLastApiVersion(version: Int) { update { it[Keys.LAST_API_VERSION] = version } }

    companion object {
        const val DAMAGED = "This phone's settings were damaged; pair again."

        /**
         * The store these preferences live in. A file that cannot be read (a damaged proto) is replaced rather than thrown
         * at every start, which would be a crash loop the owner could only end by clearing the app's data: the phone opens
         * unpaired, on the Pair screen, and says why. The reason is in the replacement itself, so it is written by the
         * store's own replacing write — nothing else ever writes the file.
         */
        fun store(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()), file: () -> File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(Keys.UNPAIRED_REASON to DAMAGED) },
                scope = scope,
                produceFile = file,
            )
    }
}
