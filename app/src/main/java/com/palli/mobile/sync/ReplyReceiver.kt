package com.palli.mobile.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.palli.mobile.domain.SendGate
import com.palli.mobile.domain.SendRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A reply typed into the notification: queued like any other message, unless the app refuses to chat with this PaLLi. */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var sends: SendRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var gate: SendGate

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.REPLY_KEY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reply(text, gate, { sends.enqueue(it) }, shown = notifier::addMyReply, notSent = notifier::addNotSent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val BLOCKED = "PaLLi and this app speak different versions. Update the older one, then reply."
        const val FAILED = "Couldn't send your reply. Open PaLLi and send it from there."
        private const val TAG = "ReplyReceiver"

        /**
         * Queues [text] unless [gate] is blocked, then updates the conversation: the reply itself when it is on its way,
         * one plain line when it is not. Either update also stops the reply button's spinner. It throws nothing but a
         * cancellation: the receiver's coroutine has no handler, and an escaped exception would take the app down.
         */
        suspend fun reply(
            text: String,
            gate: SendGate,
            enqueue: suspend (String) -> Unit,
            shown: (String) -> Unit,
            notSent: (String) -> Unit,
        ) {
            if (gate.blocked.value) {
                say { notSent(BLOCKED) }
                return
            }
            val queued = try {
                enqueue(text)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `enqueue` throws only when nothing was written, so the reply is not on its way: say so.
                Log.w(TAG, "a reply could not be queued: ${e.javaClass.simpleName}")
                false
            }
            say { if (queued) shown(text) else notSent(FAILED) }
        }

        private inline fun say(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "the conversation could not be updated: ${e.javaClass.simpleName}")
            }
        }
    }
}
