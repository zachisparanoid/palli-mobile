package com.palli.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real worker over a real processor (MockWebServer) and a real controller whose service calls are recorded. */
@RunWith(RobolectricTestRunner::class)
class CatchUpWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val serviceCalls = mutableListOf<String>()
    private var insertFailure: Throwable? = null
    private var sinkFailure: Throwable? = null

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun worker(): CatchUpWorker {
        val processor = CatchUpProcessor(
            currentSession = { Session(server.url("/").toString().trimEnd('/'), "pmd_t", 1, "Phone") },
            apis = ApiFactory(OkHttpClient()),
            insertNew = { events -> insertFailure?.let { throw it }; events },
            maxId = { 0L },
            sink = object : EventSink {
                override suspend fun onNewEvents(events: List<EventDto>) { sinkFailure?.let { throw it } }
            },
            unpairer = object : Unpairer { override suspend fun unpaired(reason: String?) = Unit },
        )
        val controller = ConnectionController(
            paired = MutableStateFlow(true), pairedNow = { true }, stayConnected = MutableStateFlow(true),
            inForeground = MutableStateFlow(false), scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            startService = { serviceCalls += "start"; true }, stopService = { serviceCalls += "stop" },
        )
        return TestListenableWorkerBuilder<CatchUpWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    CatchUpWorker(appContext, workerParameters, processor, controller)
            }).build()
    }

    private fun page() = MockResponse().setBody("""{"events":[{"id":1,"kind":"message","at":1.0,"text":"t1"}],"more":false}""")

    @Test fun `a catch-up that went well reasserts the connection`() = runTest {
        server.enqueue(page())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(listOf("start"), serviceCalls)
    }

    @Test fun `PaLLi being unreachable asks to retry, and still reasserts the connection`() = runTest {
        server.shutdown()
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(listOf("start"), serviceCalls)
    }

    @Test fun `a notifier that throws still lets the catch-up finish and reassert`() = runTest {
        sinkFailure = IllegalStateException("notification channel gone")
        server.enqueue(page())
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(listOf("start"), serviceCalls)
    }

    @Test fun `a catch-up that blows up reasserts the connection before the failure goes on`() = runTest {
        insertFailure = IllegalStateException("disk full")
        server.enqueue(page())
        val failure = runCatching { worker().doWork() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("start"), serviceCalls)
    }
}
