package com.palli.mobile.domain.timeline

import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds

/** The chat is one projection of the server's ordered log plus what is still on its way. */
object TimelineReducer {
    fun reduce(events: List<EventDto>, pending: List<PendingSendEntity>): List<TimelineItem> {
        val ordered = events.sortedBy { it.id }                       // ids, never clocks
        val delivered = ordered.mapNotNull { it.clientId }.toSet()
        val items = ordered.mapNotNull(::fromEvent)
        val waiting = pending.filter { it.clientId !in delivered }.sortedBy { it.createdAt }.map(::fromPending)
        return items + waiting
    }

    private fun fromEvent(e: EventDto): TimelineItem? = when (e.kind) {
        EventKinds.USER_MESSAGE -> TimelineItem.Mine("e${e.id}", e.text.orEmpty(), e.heardAs, e.files, emptyList(), null, null)
        EventKinds.MESSAGE -> TimelineItem.Hers("e${e.id}", e.text.orEmpty(), e.reminder, e.at)
        EventKinds.VOICE_NOTE -> e.file?.let { TimelineItem.HerVoice("e${e.id}", it, e.text?.takeIf(String::isNotBlank)) }
        EventKinds.FILE -> e.file?.let { TimelineItem.HerFile("e${e.id}", it) }
        EventKinds.NOTICE -> TimelineItem.Notice("e${e.id}", e.text.orEmpty(), e.level == "trouble")
        else -> null
    }

    /** A send whose files will not decode still shows, with its words: the queue fails it for good (SendProcessor). */
    private fun fromPending(p: PendingSendEntity) = TimelineItem.Mine(
        key = "p${p.clientId}", text = p.text, heardAs = null, files = emptyList(),
        local = runCatching { p.attachments() }.getOrDefault(emptyList()),
        status = when (p.state) {
            SendStates.FAILED -> SendStatus.FAILED
            SendStates.ACCEPTED -> SendStatus.ACCEPTED
            else -> SendStatus.SENDING
        },
        failure = p.failure,
    )
}
