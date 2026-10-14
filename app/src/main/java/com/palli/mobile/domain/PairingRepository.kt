package com.palli.mobile.domain

import android.util.Log
import androidx.work.WorkManager
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.PairRequest
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.sync.SendWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepository @Inject constructor(
    private val apis: ApiFactory,
    private val sessions: SessionStore,
    private val timeline: TimelineRepository,
    private val work: WorkManager,
    private val cached: CachedFiles,
) : Unpairer {
    suspend fun pair(baseUrl: String, code: String, deviceName: String): Result<Session> = apiCall {
        val paired = apis.forBase(baseUrl).pair(PairRequest(code, deviceName))
        if (paired.apiVersion != Wire.API_VERSION) {
            throw ApiError.Rejected(
                "This PaLLi speaks version ${paired.apiVersion} and this app speaks ${Wire.API_VERSION}. Update the older one.")
        }
        timeline.clear()                                   // never mix two servers' conversations
        Session(baseUrl, paired.token, paired.deviceId, deviceName).also { sessions.save(it) }
    }

    /** He chose to unpair: stop the queue so no in-flight send can 401 over his null reason, tell PaLLi
     *  (best effort — she may be unreachable), then forget everything here.
     *
     *  All of it runs whole whatever the caller does: the screen that asked can be left, and its scope cancelled,
     *  while she is still being told, and that must not leave the queue stopped but the session kept. */
    suspend fun unpairThisPhone() {
        withContext(NonCancellable) {
            work.cancelAllWorkByTag(SendWorker.TAG)
            sessions.current?.let { s -> apiCall { apis.forBase(s.baseUrl).revoke(s.deviceId) } }
            unpaired(null)
        }
    }

    /** The wipe comes first and cannot be cancelled: on the 401 path this runs inside a SendWorker, and
     *  cancelling the queue is what cancels that very worker. So the queue is stopped last — still inside
     *  the uncancellable block, so a cancelled caller cannot skip it either. */
    override suspend fun unpaired(reason: String?) {
        withContext(NonCancellable) {
            timeline.clear()
            sessions.clear(reason)
            clearHerFiles()
            work.cancelAllWorkByTag(SendWorker.TAG)
        }
    }

    /** Her files (fetched for playing and saving, and the pictures Coil kept) must not outlive the pairing. Best effort:
     *  a file that will not go must not keep the token or the queue, so it is logged (class name only) and the rest goes on. */
    private suspend fun clearHerFiles() {
        try {
            cached.clear()
        } catch (e: Exception) {
            Log.w("PairingRepository", "her cached files could not all be cleared: ${e.javaClass.simpleName}")
        }
    }
}
