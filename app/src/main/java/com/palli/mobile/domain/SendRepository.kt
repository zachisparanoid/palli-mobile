package com.palli.mobile.domain

import androidx.work.WorkManager
import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.Wire
import com.palli.mobile.sync.SendWorker
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SendRepository @Inject constructor(
    private val pending: PendingSendDao,
    private val work: WorkManager,
) {
    fun newClientId(): String = UUID.randomUUID().toString()

    suspend fun enqueue(text: String, attachments: List<LocalAttachment> = emptyList(), clientId: String = newClientId()): String {
        pending.upsert(PendingSendEntity(
            clientId = clientId, text = text.trim(),
            attachmentsJson = Wire.json.encodeToString(ListSerializer(LocalAttachment.serializer()), attachments),
            state = SendStates.QUEUED, failure = null, createdAt = System.currentTimeMillis(), attempts = 0))
        SendWorker.enqueue(work, clientId)
        return clientId
    }

    suspend fun retry(clientId: String) {
        pending.setState(clientId, SendStates.QUEUED, null)
        SendWorker.enqueue(work, clientId)
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
            if (pending.moveIf(clientId, from = owed, to = SendStates.QUEUED) > 0) SendWorker.enqueue(work, clientId)
        }
    }
}
