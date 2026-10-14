package com.palli.mobile

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.di.AppScope
import com.palli.mobile.domain.SendRepository
import com.palli.mobile.sync.AppForeground
import com.palli.mobile.sync.CatchUpWorker
import com.palli.mobile.sync.ConnectionController
import com.palli.mobile.sync.Notifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PalliApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var foreground: AppForeground
    @Inject lateinit var controller: ConnectionController
    @Inject lateinit var sessions: SessionStore
    // Lazy: SendRepository holds the WorkManager, and building that during injection would ask for `workerFactory`
    // (through workManagerConfiguration) before it has necessarily been injected. It is first used after onCreate.
    @Inject lateinit var sends: dagger.Lazy<SendRepository>
    @Inject @AppScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        foreground.install()
        scope.launch {
            sessions.load()
            controller.install()
        }
        scope.launch { resumeUnsettledSends() }
        CatchUpWorker.schedule(WorkManager.getInstance(this))
    }

    /**
     * A send's row and its work are not written atomically, and a process death mid-send leaves a SENDING row with no
     * work behind it. Schedule every owed send again. Best effort: an Exception is logged and the rows wait for the next
     * start, but an Error is not caught here (nor by the scope), so it still crashes visibly.
     */
    private suspend fun resumeUnsettledSends() {
        try {
            sends.get().resumeUnsettled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PalliApp", "unsent messages could not be rescheduled: ${e.javaClass.simpleName}")
        }
    }
}
