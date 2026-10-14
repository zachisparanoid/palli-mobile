package com.palli.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The server's timeline, mirrored. The whole event is kept as JSON; id/kind/at/clientId are columns for queries. */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val at: Double,
    val clientId: String?,
    val json: String,
) {
    fun toDto(): EventDto = Wire.json.decodeFromString(EventDto.serializer(), json)

    companion object {
        fun from(dto: EventDto) = EventEntity(dto.id, dto.kind, dto.at, dto.clientId,
            Wire.json.encodeToString(EventDto.serializer(), dto))
    }
}

/** A file in the app's own storage waiting to be sent (copied at enqueue, so no URI grant can expire). */
@Serializable
data class LocalAttachment(
    val path: String,
    val kind: String,          // "image" | "file" | "voice"
    val mime: String,
    val name: String,
    val durationMs: Long? = null,
)

object SendStates {
    const val QUEUED = "queued"
    const val SENDING = "sending"
    const val ACCEPTED = "accepted"
    const val FAILED = "failed"
}

@Entity(tableName = "pending_sends")
data class PendingSendEntity(
    @PrimaryKey val clientId: String,
    val text: String,
    val attachmentsJson: String,
    val state: String,
    val failure: String?,
    val createdAt: Long,
    val attempts: Int,
) {
    fun attachments(): List<LocalAttachment> =
        Wire.json.decodeFromString(ListSerializer(LocalAttachment.serializer()), attachmentsJson)
}
