package com.palli.mobile.data.remote

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable data class OkResponse(val ok: Boolean = true)

/** Paths are relative, so a server behind a path prefix (https://host/palli) works. */
interface PalliApi {
    @POST("api/mobile/pair") suspend fun pair(@Body body: PairRequest): PairResponse
    @GET("api/mobile/me") suspend fun me(): MeResponse

    @Multipart @POST("api/mobile/messages")
    suspend fun send(@Part parts: List<MultipartBody.Part>): AcceptedResponse

    @GET("api/mobile/events") suspend fun eventsAfter(@Query("after") after: Long, @Query("limit") limit: Int = 200): EventsPage
    @GET("api/mobile/events") suspend fun eventsBefore(@Query("before") before: Long, @Query("limit") limit: Int = 50): EventsPage

    @GET("api/mobile/reminders") suspend fun reminders(): RemindersResponse
    @POST("api/mobile/reminders/{id}/snooze") suspend fun snooze(@Path("id") id: Long, @Body body: SnoozeRequest): OkResponse
    @POST("api/mobile/reminders/{id}/cancel") suspend fun cancelReminder(@Path("id") id: Long): OkResponse

    @GET("api/mobile/devices") suspend fun devices(): DevicesResponse
    @DELETE("api/mobile/devices/{id}") suspend fun revoke(@Path("id") id: Long): OkResponse
}
