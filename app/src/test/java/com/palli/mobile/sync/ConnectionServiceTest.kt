package com.palli.mobile.sync

import android.app.Service
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The real service. Hilt builds the app's real graph under Robolectric (PalliApp is the application) and injects the
 * service in onCreate, so the test swaps in a StreamSession of its own, with a fake socket, once the service exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionServiceTest {
    private val opened = mutableListOf<String>()

    private val sockets = object : SocketFactory {
        override fun open(url: String, token: String, listener: SocketListener): SocketHandle {
            opened += url
            return object : SocketHandle {
                override fun send(text: String) = true
                override fun close(code: Int, reason: String) = Unit
            }
        }
    }

    @Test fun `starting the service again brings back a loop that ended for want of a session`() = runTest {
        var session: Session? = null
        val stream = StreamSession(
            scope = backgroundScope,
            currentSession = { session },
            sockets = sockets,
            insertNew = { it },
            maxId = { 0L },
            sink = object : EventSink { override suspend fun onNewEvents(events: List<EventDto>) = Unit },
            unpairer = object : Unpairer { override suspend fun unpaired(reason: String?) = Unit },
        )
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        val service = controller.get().also { it.stream = stream }

        stream.start(); runCurrent()                                        // no session: the loop ends at once
        assertEquals(emptyList<String>(), opened)
        assertEquals(ConnState.IDLE, stream.state.value)

        session = Session("http://10.0.2.2:8000", "pmd_t", 1, "Phone")      // paired since, and Android starts the service again
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2)); runCurrent()
        assertEquals(listOf("http://10.0.2.2:8000/api/mobile/stream?after=0"), opened)
        assertEquals(ConnState.CONNECTING, stream.state.value)

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 3)); runCurrent()
        assertEquals(1, opened.size)                                        // idempotent while the loop runs

        controller.destroy()
        assertEquals(ConnState.IDLE, stream.state.value)                    // the service going away stops the connection at once
    }
}
