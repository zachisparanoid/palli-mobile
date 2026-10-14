package com.palli.mobile.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Every 15 minutes: fetch what Android's killing of the service made the phone miss, and restart it if allowed. */
@HiltWorker
class CatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: CatchUpProcessor,
    private val controller: ConnectionController,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The restart is the backstop for a killed service, so it happens however the catch-up ends.
        val done = try { processor.run() } finally { controller.reassert() }
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(work: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE, not KEEP: a later build's interval or constraints reach a phone that already has the work.
            work.enqueueUniquePeriodicWork("palli-catch-up", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
