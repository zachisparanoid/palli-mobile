package com.palli.mobile.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The settings' logic, over stand-ins for the stored choice and for unpairing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val session = MutableStateFlow<Session?>(Session("http://palli-box.example.ts.net:8000", "pmd_test", 1, "Pixel"))
    private val stored = MutableStateFlow(true)
    private val written = mutableListOf<Boolean>()
    private var writeGate: CompletableDeferred<Unit>? = null
    private var writeFailure: Throwable? = null
    private var unpairs = 0
    private var unpairFailure: Throwable? = null

    private fun viewModel() = SettingsViewModel(
        session = session,
        stayConnectedSetting = stored,
        writeStayConnected = { on -> writeGate?.await(); writeFailure?.let { throw it }; written += on; stored.value = on },
        unpairThisPhone = { unpairs++; unpairFailure?.let { throw it } },
    )

    // ---- staying connected ----

    @Test fun `the switch shows what is stored, and nothing until it has been read`() {
        val vm = viewModel()
        assertEquals(true, vm.stayConnected.value)
        stored.value = false
        assertEquals(false, vm.stayConnected.value)

        val unread = SettingsViewModel(session, emptyFlow(), { }, { })
        assertNull(unread.stayConnected.value)
    }

    @Test fun `moving the switch writes the choice down`() {
        val vm = viewModel()
        vm.setStayConnected(false)
        vm.setStayConnected(true)
        assertEquals(listOf(false, true), written)
    }

    @Test fun `a choice is written even if the screen is left before the write finishes`() {
        writeGate = CompletableDeferred()
        val vm = viewModel()
        vm.setStayConnected(false)
        vm.viewModelScope.cancel()                           // back is pressed: the view model's scope goes
        writeGate!!.complete(Unit)
        assertEquals(listOf(false), written)
    }

    @Test fun `a choice that could not be written is told, and the switch stays where it was stored`() {
        writeFailure = IOException("disk")
        val vm = viewModel()
        vm.setStayConnected(false)
        assertEquals(SettingsViewModel.SAVE_FAILED, vm.notice.value)
        assertEquals(true, vm.stayConnected.value)
    }

    @Test fun `the switch moves the real stored choice, even for a reader that starts as the write does`() {
        // DataStore and ServerPrefs for real (through the lambdas given here; the @Inject wiring is SettingsWiringTest's), on
        // real threads as on a phone. The switch is read straight away, while its write may still be in flight, and so is the
        // stored choice: both must come to show it.
        Dispatchers.setMain(Dispatchers.Default)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") })
        val vm = SettingsViewModel(session, prefs.stayConnected, prefs::setStayConnected, { })
        try {
            awaiting { vm.stayConnected.first { it != null } }
            repeat(6) { n ->
                val on = n % 2 == 1
                vm.setStayConnected(on)
                awaiting { prefs.stayConnected.first { it == on } }
                awaiting { vm.stayConnected.first { it == on } }
            }
        } finally {
            scope.cancel()
        }
    }

    /** Waits on real threads for something that happens on real threads (DataStore's), up to ten seconds. */
    private fun <T> awaiting(block: suspend () -> T): T {
        val done = CompletableFuture<T>()
        CoroutineScope(Dispatchers.Default).launch {
            try { done.complete(withTimeout(10_000) { block() }) } catch (e: Throwable) { done.completeExceptionally(e) }
        }
        return done.get(15, TimeUnit.SECONDS)
    }

    // ---- unpairing ----

    @Test fun `unpairing goes once, however often it is tapped`() {
        val vm = viewModel()
        assertFalse(vm.unpairing.value)
        vm.unpair()
        vm.unpair()
        assertEquals(1, unpairs)
        assertTrue(vm.unpairing.value)                             // until the pairing is gone and the screen leaves
        assertNull(vm.notice.value)
    }

    @Test fun `an unpairing that failed is told, and can be tried again`() {
        unpairFailure = IOException("disk")
        val vm = viewModel()
        vm.unpair()
        assertEquals(SettingsViewModel.UNPAIR_FAILED, vm.notice.value)
        assertFalse(vm.unpairing.value)

        unpairFailure = null
        vm.unpair()
        assertEquals(2, unpairs)
    }

    @Test fun `a notice is forgotten once shown, unless a newer one has replaced it`() {
        unpairFailure = IOException("disk")
        val vm = viewModel()
        vm.unpair()
        vm.noticeShown("something else")
        assertEquals(SettingsViewModel.UNPAIR_FAILED, vm.notice.value)
        vm.noticeShown(SettingsViewModel.UNPAIR_FAILED)
        assertNull(vm.notice.value)
    }
}
