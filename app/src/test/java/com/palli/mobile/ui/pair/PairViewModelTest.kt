package com.palli.mobile.ui.pair

import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Pair screen's logic, over a fake for the one thing it asks of PairingRepository: `pair`. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModelTest {
    private val base = "http://palli-box.example.ts.net:8000"
    private val link = "palli://pair?u=http%3A%2F%2Fpalli-box.example.ts.net%3A8000&c=K7QM-2XPA"
    private val session = Session(base, "pmd_secret", 7, "Pixel")

    private class Call(val baseUrl: String, val code: String, val deviceName: String)

    private val calls = mutableListOf<Call>()
    private var answer: Result<Session> = Result.success(session)
    private var answerWith: (() -> Throwable)? = null                // thrown from `pair` instead of answering
    private var hold: CompletableDeferred<Unit>? = null

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PairViewModel(
        pairWith = { baseUrl, code, name -> calls += Call(baseUrl, code, name); hold?.await(); answerWith?.let { throw it() }; answer },
        unpairedReason = MutableStateFlow(null),
        defaultDeviceName = "Pixel",
    )

    @Test fun `a scanned link fills the fields and waits for a yes`() {
        val vm = viewModel()
        vm.onScanned(link)

        assertTrue("nothing is paired before the owner says so", calls.isEmpty())
        val s = vm.state.value
        assertEquals(base, s.pendingAddress)
        assertEquals(base, s.address)
        assertEquals("K7QM2XPA", s.code)
        assertFalse(s.busy)
        assertNull(s.error)
    }

    @Test fun `confirming pairs once, with exactly what was scanned`() {
        val vm = viewModel()
        vm.onScanned(link)
        vm.confirmPair()
        vm.confirmPair()                                           // a second tap on a vanishing dialog

        assertEquals(1, calls.size)
        assertEquals(base, calls[0].baseUrl)
        assertEquals("K7QM2XPA", calls[0].code)
        assertEquals("Pixel", calls[0].deviceName)
        assertNull(vm.state.value.pendingAddress)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `cancelling never pairs and clears what the link filled in, so the Pair button cannot finish the job`() {
        val vm = viewModel()
        vm.onScanned(link)
        vm.cancelPair()
        vm.confirmPair()                                           // nothing is pending any more

        assertTrue(calls.isEmpty())
        val s = vm.state.value
        assertNull(s.pending)
        assertEquals("", s.address)
        assertEquals("", s.code)
        assertNull(s.error)

        vm.pair()                                                  // one tap on Pair afterwards: still nothing
        assertTrue(calls.isEmpty())
        assertEquals(PairErrors.ADDRESS_HINT, vm.state.value.error)
    }

    @Test fun `cancelling with no question up leaves what was typed by hand`() {
        val vm = viewModel()
        vm.onAddress(base)
        vm.onCode("K7QM-2XPA")
        vm.cancelPair()
        assertEquals(base, vm.state.value.address)
        assertEquals("K7QM-2XPA", vm.state.value.code)
    }

    @Test fun `confirming with nothing pending pairs nothing`() {
        val vm = viewModel()
        vm.onAddress(base)
        vm.onCode("K7QM-2XPA")
        vm.confirmPair()
        assertTrue(calls.isEmpty())
    }

    @Test fun `a link that is not PaLLi's pairs nothing and asks nothing`() {
        val vm = viewModel()
        vm.onScanned("https://example.com/not-us")

        assertTrue(calls.isEmpty())
        assertNull(vm.state.value.pendingAddress)
        assertEquals("That QR is not a PaLLi pairing code.", vm.state.value.error)
        assertEquals("", vm.state.value.address)
    }

    @Test fun `a second link replaces the question rather than answering the first`() {
        val vm = viewModel()
        vm.onScanned(link)
        vm.onScanned("palli://pair?u=http%3A%2F%2F100.64.0.7%3A8000&c=ABCD-EFGH")
        assertEquals("http://100.64.0.7:8000", vm.state.value.pendingAddress)

        vm.confirmPair()
        assertEquals(1, calls.size)
        assertEquals("http://100.64.0.7:8000", calls[0].baseUrl)
        assertEquals("ABCDEFGH", calls[0].code)
    }

    @Test fun `yes pairs exactly what the question showed, whatever the form says by then`() {
        val vm = viewModel()
        vm.onScanned(link)
        vm.onAddress("http://100.64.0.7:8000")                     // not reachable from the dialog, but must not matter
        vm.onCode("ABCD-EFGH")
        assertEquals(base, vm.state.value.pendingAddress)

        vm.confirmPair()
        assertEquals(1, calls.size)
        assertEquals(base, calls[0].baseUrl)
        assertEquals("K7QM2XPA", calls[0].code)
    }

    @Test fun `a link that is not in canonical form is asked about, and paired with, in the canonical form`() {
        val vm = viewModel()
        vm.onScanned("Palli://pair?u=HTTP%3A%2F%2FPalli-Box.Example.ts.net%3A8000%2Fpalli%2F&c=k7qm-2xpa")

        val shown = vm.state.value.pendingAddress
        assertEquals("http://palli-box.example.ts.net:8000/palli", shown)
        vm.confirmPair()
        assertEquals(1, calls.size)
        assertEquals(shown, calls[0].baseUrl)
        assertEquals("K7QM2XPA", calls[0].code)
    }

    @Test fun `typing the address and code pairs on the button, with no question`() {
        val vm = viewModel()
        vm.onAddress("palli-box.example.ts.net:8000")
        vm.onCode("k7qm 2xpa")
        vm.onName("  My phone ")
        vm.pair()

        assertEquals(1, calls.size)
        assertEquals(base, calls[0].baseUrl)
        assertEquals("K7QM2XPA", calls[0].code)
        assertEquals("My phone", calls[0].deviceName)
        assertNull(vm.state.value.pendingAddress)
    }

    @Test fun `a bad address or code is told what is wrong and nothing is sent`() {
        val vm = viewModel()
        vm.onCode("K7QM-2XPA")
        vm.onAddress("http:")
        vm.pair()
        assertEquals("Enter PaLLi's address, like http://your-host.your-tailnet.ts.net:8000.", vm.state.value.error)
        assertEquals(PairErrors.ADDRESS_HINT, vm.state.value.error)

        vm.onAddress(base)
        vm.onCode("K7QM")
        vm.pair()
        assertEquals("The code is eight letters and digits, like K7QM-2XPA.", vm.state.value.error)
        assertTrue(calls.isEmpty())
    }

    @Test fun `a refusal is shown in PaLLi's words and the form is free again`() {
        answer = Result.failure(ApiError.Gone("used"))
        val vm = viewModel()
        vm.onScanned(link)
        vm.confirmPair()

        assertTrue(vm.state.value.error!!.startsWith("That code expired"))
        assertFalse(vm.state.value.busy)
    }

    @Test fun `an address Retrofit refuses is an error on the form, not a crash`() {
        // What Retrofit.Builder().baseUrl(...) throws for a port out of range or a base URL with a query.
        answerWith = { IllegalArgumentException("Invalid URL port: \"99999\"") }
        val vm = viewModel()
        vm.onAddress(base)
        vm.onCode("K7QM-2XPA")
        vm.pair()

        assertEquals(1, calls.size)
        assertEquals(PairErrors.ADDRESS_HINT, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `any other exception thrown while pairing is shown too, and the form is free again`() {
        answerWith = { IllegalStateException("boom") }
        val vm = viewModel()
        vm.onScanned(link)
        vm.confirmPair()

        assertEquals("Pairing failed: boom", vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `while a pairing is out, nothing else starts`() {
        hold = CompletableDeferred()
        val vm = viewModel()
        vm.onScanned(link)
        vm.confirmPair()
        assertTrue(vm.state.value.busy)

        vm.pair()                                                  // a second tap
        vm.onScanned(link)                                         // a link arriving mid-pairing
        assertEquals(1, calls.size)
        assertNull(vm.state.value.pendingAddress)

        hold!!.complete(Unit)
        assertFalse(vm.state.value.busy)
        assertEquals(1, calls.size)
    }
}
