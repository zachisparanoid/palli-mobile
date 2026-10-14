package com.palli.mobile.di

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app's request logging: method, URL and status in a debug build, nothing in a release, and never the token. */
class HttpLoggingTest {
    private val server = MockWebServer()
    private val lines = mutableListOf<String>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun `debug logs the request line, release nothing`() {
        assertEquals(HttpLoggingInterceptor.Level.BASIC, AppModule.httpLogging(debug = true).level)
        assertEquals(HttpLoggingInterceptor.Level.NONE, AppModule.httpLogging(debug = false).level)
    }

    @Test fun `even logging every header, the token is redacted`() = runTest {
        val logging = AppModule.httpLogging(debug = true) { lines += it }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
        server.enqueue(MockResponse().setBody("{}"))
        OkHttpClient.Builder().addInterceptor(logging).build()
            .newCall(Request.Builder().url(server.url("/api/mobile/me")).header("Authorization", "Bearer pmd_test").build())
            .execute().close()

        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("Authorization: ") })
        assertFalse(lines.joinToString("\n"), lines.any { it.contains("pmd_test") })
    }
}
