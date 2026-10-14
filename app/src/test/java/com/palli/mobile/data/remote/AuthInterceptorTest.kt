package com.palli.mobile.data.remote

import com.palli.mobile.data.session.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private val palli = MockWebServer()
    private val elsewhere = MockWebServer()

    @Before fun start() { palli.start(); elsewhere.start() }
    @After fun stop() { palli.shutdown(); elsewhere.shutdown() }

    private fun client(session: Session?) = OkHttpClient.Builder().addInterceptor(AuthInterceptor { session }).build()

    @Test fun `the token goes only to the paired server`() {
        val base = palli.url("/").toString().trimEnd('/')
        val http = client(Session(base, "pmd_secret", 1, "Phone"))
        palli.enqueue(MockResponse()); elsewhere.enqueue(MockResponse())

        http.newCall(Request.Builder().url("$base/api/mobile/me").build()).execute().close()
        http.newCall(Request.Builder().url(elsewhere.url("/api/mobile/me")).build()).execute().close()

        assertEquals("Bearer pmd_secret", palli.takeRequest().getHeader("Authorization"))
        assertNull(elsewhere.takeRequest().getHeader("Authorization"))
    }

    @Test fun `no session, no header`() {
        palli.enqueue(MockResponse())
        client(null).newCall(Request.Builder().url(palli.url("/api/mobile/pair")).build()).execute().close()
        assertNull(palli.takeRequest().getHeader("Authorization"))
    }

    @Test fun `a server on a port that merely starts with ours is not ours`() {
        val session = Session("http://127.0.0.1:80", "pmd_secret", 1, "Phone")
        assertEquals(false, AuthInterceptor.belongsTo("http://127.0.0.1:8000/api/mobile/me", session.baseUrl))
        assertEquals(true, AuthInterceptor.belongsTo("http://127.0.0.1:80/api/mobile/me", session.baseUrl))
        assertEquals(false, AuthInterceptor.belongsTo("https://127.0.0.1:80/api/mobile/me", session.baseUrl))
    }
}
