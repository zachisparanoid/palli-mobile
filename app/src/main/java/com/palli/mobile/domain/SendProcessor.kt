package com.palli.mobile.domain

import com.palli.mobile.data.local.PendingSendDao
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.SessionStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject

/** One attempt at one send. A retry reuses the same client id, so PaLLi never starts a second turn for it. */
class SendProcessor @Inject constructor(
    private val pending: PendingSendDao,
    private val sessions: SessionStore,
    private val apis: ApiFactory,
    private val unpairer: Unpairer,
) {
    enum class Outcome { DONE, RETRY }

    suspend fun process(clientId: String): Outcome {
        val send = pending.get(clientId) ?: return Outcome.DONE                 // already settled
        if (send.state == SendStates.ACCEPTED || send.state == SendStates.FAILED) return Outcome.DONE
        val session = sessions.current ?: sessions.load() ?: return Outcome.DONE
        if (send.attachments().any { !File(it.path).isFile }) {
            pending.setState(clientId, SendStates.FAILED, "A file for this message is gone from the phone.")
            return Outcome.DONE
        }
        pending.setState(clientId, SendStates.SENDING, null)
        pending.bumpAttempts(clientId)
        val error = apiCall { apis.forBase(session.baseUrl).send(buildParts(send)) }.exceptionOrNull()
        return when (error) {
            null -> { pending.setState(clientId, SendStates.ACCEPTED, null); Outcome.DONE }
            is ApiError.TooLarge, is ApiError.Rejected -> {
                pending.setState(clientId, SendStates.FAILED, error.message); Outcome.DONE
            }
            is ApiError.Unauthorized -> { unpairer.unpaired("This phone was unpaired."); Outcome.DONE }
            else -> { pending.setState(clientId, SendStates.QUEUED, null); Outcome.RETRY }
        }
    }

    companion object {
        fun buildParts(send: PendingSendEntity): List<MultipartBody.Part> = buildList {
            add(MultipartBody.Part.createFormData("client_id", send.clientId))
            if (send.text.isNotBlank()) add(MultipartBody.Part.createFormData("text", send.text))
            for (a in send.attachments()) {
                val field = when (a.kind) { "image" -> "images"; "voice" -> "voice"; else -> "files" }
                add(MultipartBody.Part.createFormData(field, a.name, File(a.path).asRequestBody(a.mime.toMediaTypeOrNull())))
            }
        }
    }
}
