package com.palli.mobile.sync

import android.content.Context
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one rule for whether the connection runs, and the one place it is applied. Whenever any input changes (install),
 * and whenever boot or the catch-up worker asks (reassert), the answer is worked out afresh from the inputs as they are
 * now and the service is started or stopped to match, under a lock so two callers can never leave the older answer last.
 *
 * Nothing is remembered between decisions, on purpose: starting a running service and stopping a stopped one are both
 * no-ops, and a start that Android refused (the app was in the background) must be tried again the next time anything
 * changes, such as the app being opened.
 */
@Singleton
class ConnectionController(
    private val paired: Flow<Boolean>,
    private val pairedNow: suspend () -> Boolean,
    private val stayConnected: Flow<Boolean>,
    private val inForeground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val startService: () -> Boolean,
    private val stopService: () -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        sessions: SessionStore,
        prefs: ServerPrefs,
        foreground: AppForeground,
        @AppScope scope: CoroutineScope,
    ) : this(
        paired = sessions.session.map { it != null },
        pairedNow = { (sessions.current ?: sessions.load()) != null },
        stayConnected = prefs.stayConnected,
        inForeground = foreground.inForeground,
        scope = scope,
        startService = { ConnectionService.start(context) },
        stopService = { ConnectionService.stop(context) },
    )

    private val decision = Mutex()

    fun install() {
        scope.launch {
            // Every change of any input, not only a change of the answer: a refused start is not "the answer holding".
            combine(paired, stayConnected, inForeground) { _, _, _ -> }.collect { reassert() }
        }
    }

    /** From install, boot or the catch-up worker: start the connection if it should run, stop it if it should not. */
    suspend fun reassert() {
        decision.withLock {
            val run = wanted(pairedNow(), stayConnected.first(), inForeground.value)
            if (run) startService() else stopService()
        }
    }

    companion object {
        fun wanted(paired: Boolean, stayConnected: Boolean, inForeground: Boolean): Boolean =
            paired && (stayConnected || inForeground)
    }
}
