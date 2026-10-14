package com.palli.mobile.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the server sends down /api/mobile/stream. */
sealed interface ServerFrame {
    data class Event(val event: EventDto) : ServerFrame
    data class Typing(val on: Boolean) : ServerFrame
    data object Ping : ServerFrame
    data class Unknown(val type: String) : ServerFrame
}

object Frames {
    const val TYPING = """{"type":"typing"}"""
    const val PONG = """{"type":"pong"}"""

    fun ack(through: Long): String = """{"type":"ack","through":$through}"""

    /**
     * Null for anything that is not a JSON object with a type, and for a frame whose fields do not fit the
     * contract (the connection loop calls this on every frame, so it must not throw); Unknown for a type this app predates.
     */
    fun decode(text: String): ServerFrame? {
        val obj: JsonObject = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return try {
            when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                null -> null
                "event" -> obj["event"]?.let { ServerFrame.Event(Wire.json.decodeFromJsonElement(EventDto.serializer(), it)) }
                "typing" -> ServerFrame.Typing(obj["on"]?.jsonPrimitive?.booleanOrNull ?: false)
                "ping" -> ServerFrame.Ping
                else -> ServerFrame.Unknown(type)
            }
        } catch (e: IllegalArgumentException) {
            null // SerializationException is a subclass: a missing or mistyped field, or a null where an object belongs
        }
    }
}
