package com.palli.mobile.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The wire contract with PaLLi's /api/mobile (spec §5). */
object Wire {
    const val API_VERSION = 1
    val json = Json {
        ignoreUnknownKeys = true      // a newer backend may add fields
        explicitNulls = false
        encodeDefaults = true
    }
}

object EventKinds {
    const val USER_MESSAGE = "user_message"
    const val MESSAGE = "message"
    const val VOICE_NOTE = "voice_note"
    const val FILE = "file"
    const val NOTICE = "notice"
}

@Serializable data class PairRequest(val code: String, @SerialName("device_name") val deviceName: String)

@Serializable data class PairResponse(
    val token: String,
    @SerialName("device_id") val deviceId: Long,
    @SerialName("api_version") val apiVersion: Int,
)

@Serializable data class DeviceRef(val id: Long, val name: String)

@Serializable data class MeResponse(
    val device: DeviceRef,
    @SerialName("door_state") val doorState: String,
    @SerialName("owner_display_name") val ownerDisplayName: String = "",
    @SerialName("api_version") val apiVersion: Int,
)

@Serializable data class DeviceDto(
    val id: Long,
    val name: String,
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("last_seen_at") val lastSeenAt: Double? = null,
    @SerialName("this_device") val thisDevice: Boolean = false,
)

@Serializable data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

@Serializable data class FileRef(
    val id: String,
    val filename: String = "",
    @SerialName("content_type") val contentType: String = "application/octet-stream",
    val size: Long? = null,
    @SerialName("duration_s") val durationS: Double? = null,
)

/** One timeline event. Flat on the wire: `{id, kind, at, …payload}`. */
@Serializable data class EventDto(
    val id: Long,
    val kind: String,
    val at: Double,
    val text: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("heard_as") val heardAs: String? = null,
    val files: List<FileRef> = emptyList(),
    val file: FileRef? = null,
    val reminder: Boolean = false,
    val level: String? = null,
)

@Serializable data class EventsPage(val events: List<EventDto> = emptyList(), val more: Boolean = false)

@Serializable data class AcceptedResponse(@SerialName("client_id") val clientId: String)

@Serializable data class ReminderDto(
    val id: Long,
    val text: String,
    @SerialName("due_at") val dueAt: Double,
    val kind: String = "reminder",
    val platform: String = "",
)

@Serializable data class RemindersResponse(val reminders: List<ReminderDto> = emptyList())

@Serializable data class SnoozeRequest(val minutes: Int)
