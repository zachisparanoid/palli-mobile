package com.palli.mobile.domain

import android.util.Log
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.session.ServerPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether sending is refused because PaLLi reports another api version than this app speaks. One flag for the whole
 * process: the chat reports each `/me` answer here ([heard]), and every way of sending reads it (the composer, the
 * attach menu, the camera, the microphone, and the Reply typed into a notification).
 *
 * Each answer is also written down, so a cold start begins from the last one heard: a Reply from the notification
 * before the chat has asked `/me` again is still refused if the last answer was another version. A fresh answer always
 * wins over that remembered one.
 *
 * The primary constructor takes the two calls on storage, so a test can stand in for them; the no-argument one
 * remembers nothing (a test of the chat that does not care), and the `@Inject` one is Hilt's.
 */
@Singleton
class SendGate(
    private val readVersion: suspend () -> Int?,
    private val writeVersion: suspend (Int) -> Unit,
) {
    @Inject constructor(prefs: ServerPrefs) : this(prefs::lastApiVersion, prefs::setLastApiVersion)
    constructor() : this({ null }, { })

    private val _blocked = MutableStateFlow(false)
    /** The flag as it stands; read [isBlocked] where it must already reflect a cold start's remembered answer. */
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    private val lock = Mutex()
    @Volatile private var known = false

    /** Whether sending is refused now: by the answer heard in this process or, before one, by the last one written down. */
    suspend fun isBlocked(): Boolean {
        load()
        return _blocked.value
    }

    /** Takes up the remembered answer, once, unless an answer has already been heard in this process. */
    suspend fun load() {
        if (known) return
        lock.withLock {
            if (known) return
            val last = try {
                readVersion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the last api version could not be read: ${e.javaClass.simpleName}")
                null
            }
            _blocked.value = last != null && last != Wire.API_VERSION
            known = true
        }
    }

    /** What `/me` just said. It decides at once, and is written down for the next cold start. */
    suspend fun heard(apiVersion: Int) {
        lock.withLock {
            _blocked.value = apiVersion != Wire.API_VERSION
            known = true
        }
        try {
            writeVersion(apiVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the api version could not be written down: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "SendGate"
    }
}
