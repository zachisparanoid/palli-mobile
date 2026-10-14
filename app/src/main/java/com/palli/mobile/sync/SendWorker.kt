package com.palli.mobile.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.palli.mobile.domain.SendProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Sends one queued message whenever there is a network; a dead zone only delays it. */
@HiltWorker
class SendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: SendProcessor,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val clientId = inputData.getString(KEY) ?: return Result.success()
        return when (processor.process(clientId)) {
            SendProcessor.Outcome.DONE -> Result.success()
            SendProcessor.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY = "client_id"
        const val TAG = "palli-send"

        fun enqueue(work: WorkManager, clientId: String) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf(KEY to clientId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            work.enqueueUniqueWork("send-$clientId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
