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
 * The primary constructor takes what it asks of WorkManager (scheduling and cancelling a send's work) and of the send's
 * folder, so a test can stand in for them or make them fail; the `@Inject` one is what Hilt uses (the same shape as
 * ConnectionController).
 */
@Singleton
class SendRepository(
    private val pending: PendingSendDao,
    private val forgetFiles: (clientId: String) -> Unit = {},
    private val cancelWork: (clientId: String) -> Unit = {},
    private val schedule: (clientId: String) -> Unit,
) {
    @Inject constructor(pending: PendingSendDao, work: WorkManager, outgoing: OutgoingFiles) : this(
        pending,
        forgetFiles = outgoing::forget,
        cancelWork = { work.cancelUniqueWork(SendWorker.uniqueName(it)) },
        schedule = { SendWorker.enqueue(work, it) },
    )

    /**
     * Takes a failed send off the phone, for good: its row, its files and its work. Only a failed one: a send that is on
     * its way (or already with PaLLi) is left alone, so a bubble that was retried just before cannot lose its files
     * mid-upload. The row goes first, so its bubble does; a death after it leaves only a folder nothing points at.
     */
    suspend fun remove(clientId: String) {
        if (pending.deleteIfFailed(clientId) == 0) return
        forgetFiles(clientId)
        cancelWork(clientId)
    }

    fun newClientId(): String = UUID.randomUUID().toString()

    /**
     * Writes the send, then schedules its work. It throws only when nothing was written, so a caller that gets an
     * exception can hand the text back without risking a second copy. Once the row is written it does not throw: if the
     * work cannot be scheduled the row is settled as failed, so its bubble offers Retry (and if even that cannot be
     * written, the row stays queued and the next app start gives it its work, see [resumeUnsettled]).
     */
    suspend fun enqueue(text: String, attachments: List<LocalAttachment> = emptyList(), clientId: String = newClientId()): String {
        pending.upsert(PendingSendEntity(
            clientId = clientId, text = text.trim(),
            attachmentsJson = Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), attachments),
            state = SendStates.QUEUED, failure = null, createdAt = System.currentTimeMillis(), attempts = 0))
        try {
            schedule(clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { pending.setState(clientId, SendStates.FAILED, COULD_NOT_START) }
        }
        return clientId
    }

    /**
     * Queues the send again. If its work cannot be scheduled the row goes back to failed (so Retry is offered again) and
     * the exception is thrown, for the caller to tell the owner.
     */
    suspend fun retry(clientId: String) {
        pending.setState(clientId, SendStates.QUEUED, null)
        try {
            schedule(clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { pending.setState(clientId, SendStates.FAILED, COULD_NOT_START) }
            throw e
        }
    }

    /**
     * At app start: gives every send still owed to PaLLi its work again. Writing the row and scheduling its work are not
     * one atomic step, so a process death between them (or in the middle of a send) leaves a QUEUED or SENDING row with
     * nothing behind it. Each such row goes back to QUEUED and gets the same unique work retry() schedules. Work that is
     * already there is kept, and a resend is safe because PaLLi dedupes by client id. FAILED and ACCEPTED rows are
     * settled and left alone.
     *
     * The move is one conditional write per row, and the work is scheduled only when that write changed a row: a send
     * that a running SendWorker settled after the ids were read is not dragged back to QUEUED or sent twice.
     */
    suspend fun resumeUnsettled() {
        val owed = listOf(SendStates.QUEUED, SendStates.SENDING)
        for (clientId in pending.idsIn(owed)) {
            if (pending.moveIf(clientId, from = owed, to = SendStates.QUEUED) > 0) schedule(clientId)
        }
    }

    companion object {
        /** What a bubble says when its send was written but could not be started. */
        const val COULD_NOT_START = "Could not start sending"
    }
}
