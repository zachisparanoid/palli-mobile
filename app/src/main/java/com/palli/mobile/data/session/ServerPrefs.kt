package com.palli.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerPrefs @Inject constructor(private val store: DataStore<Preferences>) {
    data class Stored(val baseUrl: String?, val deviceId: Long?, val deviceName: String?, val tokenBlob: String?)

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val DEVICE_ID = longPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TOKEN_BLOB = stringPreferencesKey("token_blob")
        val STAY_CONNECTED = booleanPreferencesKey("stay_connected")
        val ASKED_BATTERY = booleanPreferencesKey("asked_battery")
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notifications")
        val LAST_API_VERSION = intPreferencesKey("last_api_version")
    }

    suspend fun read(): Stored = store.data.first().let {
        Stored(it[Keys.BASE_URL], it[Keys.DEVICE_ID], it[Keys.DEVICE_NAME], it[Keys.TOKEN_BLOB])
    }

    suspend fun write(baseUrl: String, deviceId: Long, deviceName: String, tokenBlob: String) {
        store.edit {
            it[Keys.BASE_URL] = baseUrl; it[Keys.DEVICE_ID] = deviceId
            it[Keys.DEVICE_NAME] = deviceName; it[Keys.TOKEN_BLOB] = tokenBlob
        }
    }

    suspend fun clearPairing() {
        store.edit {
            it.remove(Keys.BASE_URL); it.remove(Keys.DEVICE_ID); it.remove(Keys.DEVICE_NAME); it.remove(Keys.TOKEN_BLOB)
            it.remove(Keys.LAST_API_VERSION)           // it was that server's; the next one says its own
        }
    }

    /** On by default: the persistent connection is how her messages arrive at once. */
    val stayConnected: Flow<Boolean> = store.data.map { it[Keys.STAY_CONNECTED] ?: true }
    suspend fun setStayConnected(on: Boolean) { store.edit { it[Keys.STAY_CONNECTED] = on } }

    suspend fun askedBattery(): Boolean = store.data.first()[Keys.ASKED_BATTERY] ?: false
    suspend fun markAskedBattery() { store.edit { it[Keys.ASKED_BATTERY] = true } }

    /** Whether the system's notification question has been put to the owner. Asked once ever, so a denial is remembered. */
    suspend fun askedNotifications(): Boolean = store.data.first()[Keys.ASKED_NOTIFICATIONS] ?: false
    suspend fun markAskedNotifications() { store.edit { it[Keys.ASKED_NOTIFICATIONS] = true } }

    /** The `api_version` the paired PaLLi last reported on `/me`; null before any answer. Read again after a cold start. */
    suspend fun lastApiVersion(): Int? = store.data.first()[Keys.LAST_API_VERSION]
    suspend fun setLastApiVersion(version: Int) { store.edit { it[Keys.LAST_API_VERSION] = version } }
}
