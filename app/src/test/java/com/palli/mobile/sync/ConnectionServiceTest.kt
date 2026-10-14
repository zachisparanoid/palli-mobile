package com.palli.mobile.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.session.Session
import com.palli.mobile.domain.Unpairer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * The real service. Hilt builds the app's real graph under Robolectric (PalliApp is the application) and injects the
 * service in onCreate, so the test swaps in a StreamSession of its own, with a fake socket, once the service exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConnectionServiceTest {
    /** Android refusing the foreground at the very moment the service asks for it. */
    @Implements(Service::class)
    class RefusingServiceShadow : ShadowService() {
        @Implementation
        protected override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            throw IllegalStateException("not allowed to start a foreground service right now")
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val callbacks get() = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks
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

    private fun newStream(session: () -> Session?, scope: kotlinx.coroutines.CoroutineScope) = StreamSession(
        scope = scope,
        currentSession = session,
        sockets = sockets,
        insertNew = { it },
        maxId = { 0L },
        sink = object : EventSink { override suspend fun onNewEvents(events: List<EventDto>) = Unit },
        unpairer = object : Unpairer { override suspend fun unpaired(reason: String?) = Unit },
    )

    @Test fun `starting the service again brings back a loop that ended for want of a session`() = runTest {
        var session: Session? = null
        val stream = newStream({ session }, backgroundScope)
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        val service = controller.get().also { it.stream = stream }
        assertEquals(1, callbacks.size)                                     // it listens for the network to come back
        assertEquals("Connecting to PaLLi", shadowOf(service).lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE))

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

    @Test @Config(shadows = [RefusingServiceShadow::class])
    fun `a service refused the foreground leaves quietly and does nothing when started`() = runTest {
        val session = Session("http://10.0.2.2:8000", "pmd_t", 1, "Phone")
        val stream = newStream({ session }, backgroundScope)
        val controller = Robolectric.buildService(ConnectionService::class.java).create()   // must not throw
        val service = controller.get().also { it.stream = stream }

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, callbacks.size)                                     // no network callback was registered

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1)); runCurrent()
        assertEquals(emptyList<String>(), opened)                           // and starting it again opens nothing
        assertEquals(ConnState.IDLE, stream.state.value)

        controller.destroy()                                                // going away is quiet too
        assertEquals(emptyList<String>(), opened)
    }
}
