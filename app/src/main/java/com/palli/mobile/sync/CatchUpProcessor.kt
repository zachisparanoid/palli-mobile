package com.palli.mobile.sync

import android.util.Log
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CancellationException

/**
 * The safety net under the connection: fetch what arrived while Android had the service stopped. A catch-up that reaches
 * the end of her log leaves the timeline known fresh, so it ends by expiring the sends she accepted long ago and never
 * recorded ([expireUnrecorded]).
 */
class CatchUpProcessor(
    private val currentSession: suspend () -> Session?,
    private val apis: ApiFactory,
    private val insertNew: suspend (List<EventDto>) -> List<EventDto>,
    private val maxId: suspend () -> Long,
    private val sink: EventSink,
    private val unpairer: Unpairer,
    private val expireUnrecorded: suspend () -> Unit = { },
) {
    /** True when done (or there is nothing to do); false when PaLLi could not be reached — try later. */
    suspend fun run(): Boolean {
        val session = currentSession() ?: return true
        val api = apis.forBase(session.baseUrl)
        var after = maxId()
        while (true) {
            val page = apiCall { api.eventsAfter(after) }.getOrElse { error ->
                if (error is ApiError.Unauthorized) { unpairer.unpaired("This phone was unpaired."); return true }
                return false
            }
            val fresh = insertNew(page.events)
            // The events are stored by now, so a notifier that fails must not abandon the rest of the catch-up.
            if (fresh.isNotEmpty()) {
                try {
                    sink.onNewEvents(fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("CatchUp", "notifying failed: ${e.javaClass.simpleName}")   // the class name only
                }
            }
            if (!page.more || page.events.isEmpty()) {
                try {
                    expireUnrecorded()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("CatchUp", "expiring unrecorded sends failed: ${e.javaClass.simpleName}")
                }
                return true
            }
            after = page.events.last().id
        }
    }
}
