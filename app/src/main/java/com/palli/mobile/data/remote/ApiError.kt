package com.palli.mobile.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.io.IOException

/** Every way a call to PaLLi can fail, as the screens speak about it (spec §12). */
sealed class ApiError(message: String) : Exception(message) {
    data class Unauthorized(val detail: String) : ApiError(detail)      // 401: unpaired (or a wrong pair code)
    data class Forbidden(val detail: String) : ApiError(detail)         // 403: the network or host gate
    data class Gone(val detail: String) : ApiError(detail)              // 410: an expired or used pair code
    data class TooLarge(val detail: String) : ApiError(detail)          // 413
    data class RateLimited(val detail: String) : ApiError(detail)       // 429
    /** 422 and other 4xx. [status] is the HTTP status, 0 when this was not from a response: 404 is "no such thing". */
    data class Rejected(val detail: String, val status: Int = 0) : ApiError(detail)
    data class DoorClosed(val reason: String) : ApiError(reason)        // 503 {"detail":{"reason":…}}
    data class Unreachable(val error: Throwable) : ApiError(error.message ?: "unreachable")
    data class Server(val code: Int, val detail: String) : ApiError(detail)

    companion object {
        /** A 2xx whose body this app could not read: the detail of the [Server] error (code 200) it becomes. */
        const val UNREADABLE = "PaLLi answered in a way this app doesn't understand."
    }
}

suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ApiError) {
    Result.failure(e)
} catch (e: HttpException) {
    Result.failure(e.toApiError())
} catch (e: IOException) {
    Result.failure(ApiError.Unreachable(e))
} catch (e: SerializationException) {
    // Never the parser's message: it quotes the answer, and a pairing answer holds the token.
    Result.failure(ApiError.Server(200, ApiError.UNREADABLE))
}

fun HttpException.toApiError(): ApiError {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull().orEmpty()
    val (sentence, reason) = detailOf(body)
    return when (code()) {
        401 -> ApiError.Unauthorized(sentence)
        403 -> ApiError.Forbidden(sentence.ifBlank { "Forbidden" })
        410 -> ApiError.Gone(sentence)
        413 -> ApiError.TooLarge(sentence)
        429 -> ApiError.RateLimited(sentence)
        503 -> ApiError.DoorClosed(reason ?: "door_closed")
        in 400..499 -> ApiError.Rejected(sentence, code())
        else -> ApiError.Server(code(), sentence)
    }
}

/** FastAPI's `{"detail": …}` as one sentence, plus the 503 reason when there is one. */
internal fun detailOf(body: String): Pair<String, String?> {
    val obj = runCatching { Wire.json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return body.trim().take(200) to null
    return when (val detail = obj["detail"]) {
        is JsonPrimitive -> detail.content to null
        is JsonObject -> (detail["reason"] as? JsonPrimitive)?.contentOrNull.let { (it ?: "") to it }
        is JsonArray -> detail.mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull }
            .joinToString("; ") to null
        else -> ((obj["error"] as? JsonPrimitive)?.contentOrNull ?: body.trim().take(200)) to null
    }
}
