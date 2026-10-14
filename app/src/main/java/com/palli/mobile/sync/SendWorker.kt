package com.palli.mobile.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.palli.mobile.domain.SendProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Drains the queue whenever there is a network: every send owed to PaLLi, oldest first, one at a time
 * ([SendProcessor.drain]), so what was typed in a dead zone reaches her in the order it was typed. A dead zone only
 * delays it.
 *
 * There is one drain, a unique chain ([UNIQUE]) that every way of sending appends to ([enqueue]), so two never run at
 * once: a send written while a drain runs is picked up by the drain appended after it. Appending, not replacing (which
 * would cancel an upload in flight) and not keeping (which could drop a send written after the running drain's last
 * look at the queue).
 *
 * A drain never fails: a failed one would cancel the drains appended behind it. Trouble that may pass is a retry, and
 * the sends wait behind the one that met it, in order.
 */
@HiltWorker
class SendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: SendProcessor,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A per-send work an earlier build scheduled (cancelled at start, see PalliApp): the drain does its sending.
        if (OLD_TAG in tags) return Result.success()
        return try {
            when (processor.drain()) {
                SendProcessor.Outcome.RETRY -> Result.retry()
                SendProcessor.Outcome.DONE, SendProcessor.Outcome.UNPAIRED -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "the queue could not be drained: ${e.javaClass.simpleName}")   // the class name only
            Result.retry()
        }
    }

    companion object {
        /** The one drain's unique name. */
        const val UNIQUE = "palli-sends"
        /** Every drain carries it, so unpairing can stop them all. */
        const val TAG = "palli-sends"
        /** The tag of the per-send works earlier builds scheduled, one per message ("send-<client id>"). */
        const val OLD_TAG = "palli-send"
        private const val LOG_TAG = "SendWorker"

        /** Asks for the queue to be drained: a drain is appended to the chain, after any that is running or waiting. */
        fun enqueue(work: WorkManager) {
            work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * One drain. Its backoff is linear from 10 s: exponential doubled to a wait of most of an hour after an hour's
         * outage, with every send typed since waiting behind it; linear leaves a few minutes (and [restartIfWaiting]
         * cuts even that short once PaLLi is known to answer).
         */
        fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        /**
         * PaLLi is known to answer: if the drain is waiting out a backoff (enqueued after a run, `runAttemptCount > 0`),
         * replace it, and the drains appended behind it, with one that may go at once. Never while a drain is running:
         * replacing that would cancel an upload in flight. Racing a drain that is just starting is safe: the send it
         * began stays SENDING, which the new drain takes first, and PaLLi dedupes by client id. Returns whether it did.
         */
        suspend fun restartIfWaiting(work: WorkManager): Boolean {
            val chain = work.getWorkInfosForUniqueWorkFlow(UNIQUE).first()
            if (chain.any { it.state == WorkInfo.State.RUNNING }) return false
            if (chain.none { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) return false
            work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request())
            return true
        }

        /** Cancels the per-send works an earlier build left behind: they would send alongside the drain, out of turn. */
        fun forgetOldWork(work: WorkManager) {
            work.cancelAllWorkByTag(OLD_TAG)
        }
    }
}
