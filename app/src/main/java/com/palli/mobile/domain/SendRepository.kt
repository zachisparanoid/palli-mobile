package com.palli.mobile.domain

import androidx.work.WorkManager
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.sync.SendWorker
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes sends and asks for the queue to be drained (SendWorker: one drain, so sends reach PaLLi in the order they were
 * written, one at a time). The primary constructor takes what it asks of WorkManager (a drain) and of a send's folder,
 * so a test can stand in for them or make them fail; the `@Inject` one is what Hilt uses (the same shape as
 * ConnectionController).
 */
@Singleton
class SendRepository(
    private val pending: PendingSendDao,
    private val forgetFiles: (clientId: String) -> Unit = {},
    private val schedule: () -> Unit,
) {
    @Inject constructor(pending: PendingSendDao, work: WorkManager, outgoing: OutgoingFiles) : this(
        pending,
        forgetFiles = outgoing::forget,
        schedule = { SendWorker.enqueue(work) },
    )

    /**
     * Takes a failed send off the phone, for good: its row and its files. Only a failed one: a send that is on its way
     * (or already with PaLLi) is left alone, so a bubble that was retried just before cannot lose its files mid-upload.
     * A failed send has no work of its own to cancel: the one drain takes only the sends still owed. The row goes first,
     * so its bubble does; a death after it leaves only a folder nothing points at.
     */
    suspend fun remove(clientId: String) {
        if (pending.deleteIfFailed(clientId) == 0) return
        forgetFiles(clientId)
    }

    fun newClientId(): String = UUID.randomUUID().toString()

    /**
     * Writes the send, then asks for the queue to be drained. It throws only when nothing was written, so a caller that
     * gets an exception can hand the text back without risking a second copy. Once the row is written it does not throw:
     * if the drain cannot be scheduled the row is settled as failed, so its bubble offers Retry (and if even that cannot
     * be written, the row stays queued and the next app start drains it, see [resumeUnsettled]).
     */
    suspend fun enqueue(text: String, attachments: List<LocalAttachment> = emptyList(), clientId: String = newClientId()): String {
        pending.upsert(PendingSendEntity(
            clientId = clientId, text = text.trim(),
            attachmentsJson = Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), attachments),
            state = SendStates.QUEUED, failure = null, createdAt = System.currentTimeMillis(), attempts = 0))
        try {
            schedule()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { pending.setState(clientId, SendStates.FAILED, COULD_NOT_START) }
        }
        return clientId
    }

    /**
     * Queues the send again, in the turn it was written in. If the drain cannot be scheduled the row goes back to failed
     * (so Retry is offered again) and the exception is thrown, for the caller to tell the owner.
     */
    suspend fun retry(clientId: String) {
        pending.setState(clientId, SendStates.QUEUED, null)
        try {
            schedule()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { pending.setState(clientId, SendStates.FAILED, COULD_NOT_START) }
            throw e
        }
    }

    /**
     * At app start: drains every send still owed to PaLLi. Writing the row and scheduling the drain are not one atomic
     * step, so a process death between them (or in the middle of a send) leaves a QUEUED or SENDING row with nothing
     * behind it. Each such row goes back to QUEUED, and the drain is scheduled once. A resend is safe because PaLLi
     * dedupes by client id. FAILED and ACCEPTED rows are settled and left alone.
     *
     * The move is one conditional write per row: a send that the running drain settled after the ids were read is not
     * dragged back to QUEUED.
     */
    suspend fun resumeUnsettled() {
        val owed = listOf(SendStates.QUEUED, SendStates.SENDING)
        var moved = false
        for (clientId in pending.idsIn(owed)) {
            if (pending.moveIf(clientId, from = owed, to = SendStates.QUEUED) > 0) moved = true
        }
        if (moved) schedule()
    }

    /**
     * A send PaLLi answered 202 for but never recorded (she died mid-intake) would show its check for ever: its message
     * never comes, and the message coming is what takes the row away. So once the timeline is known fresh (a catch-up
     * that reached the end, a `/me` answer), a send accepted more than [UNRECORDED_AFTER_MS] ago whose message is still
     * not in it fails as "Not delivered", and its bubble offers the retry, which goes under the same client id, so a
     * send she did record after all is not taken twice.
     */
    suspend fun expireUnrecorded(now: Long = System.currentTimeMillis()) {
        pending.expireAccepted(before = now - UNRECORDED_AFTER_MS)
    }

    companion object {
        /** What a bubble says when its send was written but could not be started. */
        const val COULD_NOT_START = "Could not start sending"
        /** How long after PaLLi accepted a send its message may still be on its way into the timeline. */
        const val UNRECORDED_AFTER_MS = 5 * 60_000L
    }
}
