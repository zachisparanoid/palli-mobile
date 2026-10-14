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
}
