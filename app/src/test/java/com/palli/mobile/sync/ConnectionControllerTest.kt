package com.palli.mobile.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {
    @Test fun `the connection runs only when paired, and then while open or told to stay`() {
        assertEquals(false, ConnectionController.wanted(paired = false, stayConnected = true, inForeground = true))
        assertEquals(true, ConnectionController.wanted(paired = true, stayConnected = false, inForeground = true))
        assertEquals(true, ConnectionController.wanted(paired = true, stayConnected = true, inForeground = false))
        assertEquals(false, ConnectionController.wanted(paired = true, stayConnected = false, inForeground = false))
    }

    // --- the decision, with the service's start and stop recorded instead of performed ---

    private val calls = mutableListOf<String>()
    private val paired = MutableStateFlow(true)
    private val stay = MutableStateFlow(true)
    private val foreground = MutableStateFlow(false)
    private var androidAllowsStart = true

    private fun TestScope.controller() = ConnectionController(
        paired = paired,
        pairedNow = { paired.value },
        stayConnected = stay,
        inForeground = foreground,
        scope = backgroundScope,
        startService = { calls += "start"; androidAllowsStart },
        stopService = { calls += "stop" },
    )

    @Test fun `a start Android refused in the background is tried again when the app is opened`() = runTest {
        androidAllowsStart = false                                  // the app was started in the background and may not start it
        controller().install(); runCurrent()
        assertEquals(listOf("start"), calls)

        androidAllowsStart = true
        foreground.value = true; runCurrent()                       // the user opens the app: the answer is still "run", but it is asked again
        assertEquals(listOf("start", "start"), calls)
    }

    @Test fun `a reassert that finds the app not wanted stops the service`() = runTest {
        stay.value = false                                          // told not to stay, and the app is closed
        controller().reassert()
        assertEquals(listOf("stop"), calls)

        foreground.value = true
        controller().reassert()
        assertEquals(listOf("stop", "start"), calls)
    }

    @Test fun `unpaired means stop, whether the news comes from install or from a reassert`() = runTest {
        val controller = controller()
        controller.install(); runCurrent()
        assertEquals(listOf("start"), calls)

        paired.value = false; runCurrent()
        assertEquals(listOf("start", "stop"), calls)
        controller.reassert()                                       // boot or the catch-up worker racing the unpair
        assertEquals(listOf("start", "stop", "stop"), calls)
    }

    @Test fun `closing the app stops the connection only when told not to stay`() = runTest {
        foreground.value = true
        val controller = controller()
        controller.install(); runCurrent()

        foreground.value = false; runCurrent()                      // stay is on: it keeps running
        assertEquals("start", calls.last())

        stay.value = false; runCurrent()                            // switched off while closed: it goes
        assertEquals("stop", calls.last())
    }
}
