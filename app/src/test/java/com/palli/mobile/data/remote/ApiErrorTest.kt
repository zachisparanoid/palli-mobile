package com.palli.mobile.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiErrorTest {
    private val server = MockWebServer()
    private lateinit var api: PalliApi

    @Before fun start() {
        server.start()
        api = ApiFactory(OkHttpClient()).forBase(server.url("/").toString().trimEnd('/'))
    }

    @After fun stop() = server.shutdown()

    private suspend fun meFails(code: Int, body: String): Throwable {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        return apiCall { api.me() }.exceptionOrNull()!!
    }

    @Test fun `each refusal becomes the error the screens speak`() = runTest {
        assertEquals(ApiError.Unauthorized("This phone is not paired."), meFails(401, """{"detail":"This phone is not paired."}"""))
        assertEquals(ApiError.Gone("expired"), meFails(410, """{"detail":"expired"}"""))
        assertEquals(ApiError.TooLarge("over 20 MB"), meFails(413, """{"detail":"over 20 MB"}"""))
        assertTrue(meFails(422, """{"detail":[{"msg":"bad"}]}""") is ApiError.Rejected)
        assertTrue(meFails(429, """{"error":"Rate limit exceeded"}""") is ApiError.RateLimited)
        assertTrue(meFails(403, "Forbidden") is ApiError.Forbidden)
        assertEquals(ApiError.DoorClosed("no_brain"), meFails(503, """{"detail":{"reason":"no_brain"}}"""))
        assertTrue(meFails(500, "boom") is ApiError.Server)
    }

    @Test fun `an odd error body never throws out of apiCall`() = runTest {
        // A gateway's envelope: the "error" is an object, not a sentence.
        assertTrue(meFails(500, """{"error":{}}""") is ApiError.Server)
        assertTrue(meFails(502, """{"error":{"code":502,"message":"bad gateway"}}""") is ApiError.Server)
        // A 503 whose reason is not a string still says the door is closed.
        assertEquals(ApiError.DoorClosed("door_closed"), meFails(503, """{"detail":{"reason":{}}}"""))
        // A validation list whose "msg" is not a string is skipped, not fatal.
        assertTrue(meFails(422, """{"detail":[{"msg":{}},{"msg":"bad"}]}""") is ApiError.Rejected)
    }

    @Test fun `a server that is not there is Unreachable`() = runTest {
        server.shutdown()
        assertTrue(apiCall { api.me() }.exceptionOrNull() is ApiError.Unreachable)
    }

    @Test fun `success passes through`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"device":{"id":1,"name":"Phone"},"door_state":"open","owner_display_name":"","api_version":1}"""))
        assertEquals("open", apiCall { api.me() }.getOrThrow().doorState)
    }
}
