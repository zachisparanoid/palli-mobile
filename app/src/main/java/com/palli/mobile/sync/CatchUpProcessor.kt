package com.palli.mobile.sync

import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer

/** The safety net under the connection: fetch what arrived while Android had the service stopped. */
class CatchUpProcessor(
    private val currentSession: suspend () -> Session?,
    private val apis: ApiFactory,
    private val insertNew: suspend (List<EventDto>) -> List<EventDto>,
    private val maxId: suspend () -> Long,
    private val sink: EventSink,
    private val unpairer: Unpairer,
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
            if (fresh.isNotEmpty()) sink.onNewEvents(fresh)
            if (!page.more || page.events.isEmpty()) return true
            after = page.events.last().id
        }
    }
}
