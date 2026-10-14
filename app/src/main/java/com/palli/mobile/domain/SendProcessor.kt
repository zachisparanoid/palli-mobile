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

/**
 * The queue's sending: one attempt at one send ([process]), and the drain that sends every owed one in turn ([drain]).
 * A retry reuses the same client id, so PaLLi never starts a second turn for it.
 */
class SendProcessor @Inject constructor(
    private val pending: PendingSendDao,
    private val sessions: SessionStore,
    private val apis: ApiFactory,
    private val unpairer: Unpairer,
) {
    /** DONE: this send is settled (accepted, failed for good, or gone). RETRY: trouble that may pass; it is queued again. UNPAIRED: nothing can be sent. */
    enum class Outcome { DONE, RETRY, UNPAIRED }

    /**
     * Sends every send owed to PaLLi, oldest first (`createdAt`, then client id), one at a time, looking at the queue
     * again after each, so a send written meanwhile is taken in its turn. A send that fails for good is marked so and
     * the next goes; trouble that may pass stops the drain (RETRY) with the rest waiting behind it, in order.
     *
     * A SENDING row is owed too: only one drain runs at a time, so one it finds was left mid-flight by a process that
     * died, and it goes in its turn rather than after the sends written later. A send is tried at most once per drain.
     */
    suspend fun drain(): Outcome {
        val tried = HashSet<String>()
        while (true) {
            val next = pending.idsIn(OWED).firstOrNull { it !in tried } ?: return Outcome.DONE
            tried += next
            val outcome = process(next)
            if (outcome != Outcome.DONE) return outcome
        }
    }

    suspend fun process(clientId: String): Outcome {
        val send = pending.get(clientId) ?: return Outcome.DONE                 // already settled
        if (send.state == SendStates.ACCEPTED || send.state == SendStates.FAILED) return Outcome.DONE
        val session = sessions.current ?: sessions.load() ?: return Outcome.UNPAIRED
        // A row this build cannot read back would otherwise throw at every try, and hold every send behind it for ever.
        val attachments = try {
            send.attachments()
        } catch (e: IllegalArgumentException) {                               // SerializationException is one
            pending.setState(clientId, SendStates.FAILED, UNREADABLE)
            return Outcome.DONE
        }
        if (attachments.any { !File(it.path).isFile }) {
            pending.setState(clientId, SendStates.FAILED, "A file for this message is gone from the phone.")
            return Outcome.DONE
        }
        // The chat refuses these before queueing them; this holds for anything else (a row an older build wrote, the
        // notification's Reply). PaLLi would refuse it for good, and past 60 MB her early 413 can reach the phone as a
        // broken pipe, which would otherwise be retried for ever.
        if (!SendLimits.fits(attachments.map { File(it.path).length() })) {
            pending.setState(clientId, SendStates.FAILED, TOO_BIG)
            return Outcome.DONE
        }
        if (!SendLimits.fitsText(send.text)) {
            pending.setState(clientId, SendStates.FAILED, TEXT_TOO_LONG)
            return Outcome.DONE
        }
        pending.setState(clientId, SendStates.SENDING, null)
        val error = apiCall { apis.forBase(session.baseUrl).send(buildParts(send)) }.exceptionOrNull()
        // A 2xx whose body this app cannot read (ApiError.UNREADABLE, code 200) is accepted too: PaLLi took the send, and
        // her dedupe would only answer 202 again, for ever.
        val accepted = error == null || (error is ApiError.Server && error.code == 200)
        return when {
            // When she took it: if her record of it never comes, it becomes "Not delivered" (SendRepository.expireUnrecorded).
            accepted -> { pending.setAccepted(clientId, System.currentTimeMillis()); Outcome.DONE }
            error is ApiError.TooLarge || error is ApiError.Rejected -> {
                pending.setState(clientId, SendStates.FAILED, error.message); Outcome.DONE
            }
            error is ApiError.Unauthorized -> { unpairer.unpaired("This phone was unpaired."); Outcome.UNPAIRED }
            else -> tryAgainLater(clientId, error)
        }
    }

    /**
     * Trouble that may pass: the send is queued again and the drain stops, the sends behind it waiting. But one that
     * fails this way every time would hold them for ever, showing "sending", with no Remove (that is for failed sends).
     * So a try that says something about this send counts, and the [MAX_ATTEMPTS]th fails it, as [COULD_NOT_TAKE],
     * which offers retry and Remove, and the drain goes on to the next. What says nothing about this send does not
     * count: PaLLi out of reach, her door shut or her brain missing (any 503), her rate limit, or her host gate.
     */
    private suspend fun tryAgainLater(clientId: String, error: Throwable?): Outcome {
        val aboutThePhone = error is ApiError.Unreachable || error is ApiError.DoorClosed ||
            error is ApiError.RateLimited || error is ApiError.Forbidden
        if (!aboutThePhone) {
            pending.bumpAttempts(clientId)
            val tries = pending.get(clientId)?.attempts ?: return Outcome.DONE           // settled meanwhile
            if (tries >= MAX_ATTEMPTS) {
                pending.setState(clientId, SendStates.FAILED, COULD_NOT_TAKE)
                return Outcome.DONE
            }
        }
        pending.setState(clientId, SendStates.QUEUED, null)
        return Outcome.RETRY
    }

    companion object {
        const val TOO_BIG = "Too big to send: over 20 MB for one file, or 60 MB in all."
        const val TEXT_TOO_LONG = "Too long to send as a message: over 64 KB of words."
        const val UNREADABLE = "This message could not be read back on the phone."
        const val COULD_NOT_TAKE = "PaLLi couldn't take this one."
        /** Failed tries that were this send's own before it fails for good. */
        const val MAX_ATTEMPTS = 5
        private val OWED = listOf(SendStates.QUEUED, SendStates.SENDING)

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
