package com.palli.mobile.sync

import android.content.Context
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The one rule for whether the connection runs, applied whenever any of its inputs changes. */
@Singleton
class ConnectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val prefs: ServerPrefs,
    private val foreground: AppForeground,
    @AppScope private val scope: CoroutineScope,
) {
    fun install() {
        scope.launch {
            combine(sessions.session, prefs.stayConnected, foreground.inForeground) { session, stay, open ->
                wanted(session != null, stay, open)
            }.distinctUntilChanged().collect { run ->
                if (run) ConnectionService.start(context) else ConnectionService.stop(context)
            }
        }
    }

    /** From boot or the catch-up worker: start the connection if it should be running. */
    suspend fun reassert() {
        val paired = (sessions.current ?: sessions.load()) != null
        if (wanted(paired, prefs.stayConnected.first(), foreground.inForeground.value)) ConnectionService.start(context)
    }

    companion object {
        fun wanted(paired: Boolean, stayConnected: Boolean, inForeground: Boolean): Boolean =
            paired && (stayConnected || inForeground)
    }
}
