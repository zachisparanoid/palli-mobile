package com.palli.mobile.sync

import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CatchUpProcessorTest {
    private val server = MockWebServer()
    private val stored = mutableListOf<Long>()
    private val told = mutableListOf<Long>()
    private val unpaired = mutableListOf<String?>()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private var sinkFailure: Throwable? = null

    private fun processor() = CatchUpProcessor(
        currentSession = { Session(server.url("/").toString().trimEnd('/'), "pmd_t", 1, "Phone") },
        apis = ApiFactory(OkHttpClient()),
        insertNew = { events -> events.filter { it.id !in stored }.also { fresh -> stored += fresh.map { it.id } } },
        maxId = { stored.maxOrNull() ?: 0L },
        sink = object : EventSink { override suspend fun onNewEvents(events: List<EventDto>) { told += events.map { it.id }; sinkFailure?.let { throw it } } },
        unpairer = object : Unpairer { override suspend fun unpaired(reason: String?) { unpaired += reason } },
    )

    private fun page(ids: List<Int>, more: Boolean) = MockResponse().setBody(
        """{"events":[${ids.joinToString(",") { """{"id":$it,"kind":"message","at":1.0,"text":"t$it"}""" }}],"more":$more}""")

    @Test fun `it pages until there is no more and tells only what was new`() = runTest {
        stored += 1L
        server.enqueue(page(listOf(2, 3), more = true))
        server.enqueue(page(listOf(4), more = false))
        assertEquals(true, processor().run())
        assertEquals(listOf(2L, 3L, 4L), told)
        assertEquals("/api/mobile/events?after=1&limit=200", server.takeRequest().path)
        assertEquals("/api/mobile/events?after=3&limit=200", server.takeRequest().path)
    }

    @Test fun `unpaired while in the background forgets the pairing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"This phone is not paired."}"""))
        assertEquals(true, processor().run())
        assertEquals(listOf<String?>("This phone was unpaired."), unpaired)
    }

    @Test fun `an unreachable PaLLi asks to be retried later`() = runTest {
        server.shutdown()
        assertEquals(false, processor().run())
    }

    @Test fun `a notifier that throws does not abandon the catch-up, whose events are already stored`() = runTest {
        sinkFailure = IllegalStateException("notification channel gone")
        server.enqueue(page(listOf(1, 2), more = true))
        server.enqueue(page(listOf(3), more = false))
        assertEquals(true, processor().run())
        assertEquals(listOf(1L, 2L, 3L), stored)                   // stored before the sink was told
        assertEquals(listOf(1L, 2L, 3L), told)                     // and the second page was still fetched and told
        assertEquals(2, server.requestCount)
    }
}
