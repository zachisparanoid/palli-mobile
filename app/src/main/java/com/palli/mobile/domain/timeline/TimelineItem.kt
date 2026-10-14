package com.palli.mobile.domain.timeline

import com.palli.mobile.data.local.LocalAttachment
import com.palli.mobile.data.remote.FileRef

enum class SendStatus { SENDING, ACCEPTED, FAILED }

/** One bubble or line in the chat. `key` is stable for the Lazy list: "e<id>" for a timeline event, "p<clientId>" for a send still waiting. */
sealed interface TimelineItem {
    val key: String

    /** Yours. `status` is null once your message is in the timeline (delivered). */
    data class Mine(
        override val key: String,
        val text: String,
        val heardAs: String?,
        val files: List<FileRef>,
        val local: List<LocalAttachment>,
        val status: SendStatus?,
        val failure: String?,
    ) : TimelineItem

    data class Hers(override val key: String, val text: String, val reminder: Boolean, val at: Double) : TimelineItem
    data class HerVoice(override val key: String, val file: FileRef, val transcript: String?) : TimelineItem
    data class HerFile(override val key: String, val file: FileRef) : TimelineItem
    /** Plain words that are not hers: a failed turn, a spending notice. */
    data class Notice(override val key: String, val text: String, val trouble: Boolean) : TimelineItem
}
