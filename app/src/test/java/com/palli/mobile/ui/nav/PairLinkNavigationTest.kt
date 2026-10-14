package com.palli.mobile.ui.nav

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.MainActivity
import com.palli.mobile.PalliApp
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The real MainActivity, nav host and Pair screen on Robolectric (PalliApp is a real Hilt app here, as in
 * ConnectionServiceTest): what a `palli://pair` link does when the phone is unpaired, and what it must not do when paired.
 */
@RunWith(RobolectricTestRunner::class)
class PairLinkNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<PalliApp>()
    private val address = "http://palli-box.example.ts.net:8000"
    private val link = "palli://pair?u=http%3A%2F%2Fpalli-box.example.ts.net%3A8000&c=K7QM-2XPA"
    private val question = "Pair with $address?"
    /** What only the chat screen shows: the composer's hint. */
    private val chatHint = "Message PaLLi"
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun tearDown() { scenario?.close() }

    private fun launch(data: String? = null) {
        val intent = Intent(app, MainActivity::class.java).apply { data?.let { setData(Uri.parse(it)) } }
        scenario = ActivityScenario.launch(intent)
    }

    /** What the system does when a link reaches the running activity (it is singleTop). */
    private fun deliver(data: String) {
        val intent = Intent(app, MainActivity::class.java).setData(Uri.parse(data))
        scenario!!.onActivity {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(it, intent)
        }
    }

    /** Reversal stands in for AES-GCM, as in SessionStoreTest: Robolectric has no Android Keystore. */
    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String) = "sealed:" + plain.reversed()
        override fun decrypt(blob: String) = blob.removePrefix("sealed:").reversed()
    }

    /**
     * Paired from the start, as a restored session would be, through the store's own save. The app's startup first asks
     * the store to load once more if it finds no session (ConnectionController), so wait for that first decision to be
     * made (it stops the service, as nobody is paired yet) before saving, or that late load would read "nobody" and win.
     */
    private fun pairThisPhone(): Session {
        runBlocking { withTimeout(10_000) { app.sessions.loaded.first { it } } }
        val stopped = shadowOf(app)
        val deadline = System.currentTimeMillis() + 10_000
        while (stopped.nextStoppedService == null) {
            check(System.currentTimeMillis() < deadline) { "the app's startup never made its first connection decision" }
            Thread.sleep(10)
        }
        SessionStore::class.java.getDeclaredField("cipher").apply { isAccessible = true }.set(app.sessions, FakeCipher())
        val session = Session("http://100.64.0.7:8000", "pmd_test", 1, "Pixel")
        runBlocking { app.sessions.save(session) }
        return session
    }

    private fun unpairThisPhone() = runBlocking { app.sessions.clear("This phone was unpaired.") }

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertAbsent(text: String) = assertEquals(0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    @Test fun `a fresh install opens on Pair`() {
        launch()
        waitFor("Pair with PaLLi")
        compose.onNodeWithText("Scan the QR code").assertIsDisplayed()
        assertAbsent(question)
    }

    @Test fun `a link opened from elsewhere asks first, and cancel clears what it filled in and sends nothing`() {
        launch(link)
        waitFor(question)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(question).fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("Pair with PaLLi").assertIsDisplayed()
        assertAbsent(address)                                          // the address and code the link filled in are gone
        assertAbsent("K7QM2XPA")
        assertAbsent("Can't reach PaLLi")                              // and nothing was tried
    }

    @Test fun `saying yes sends the pairing to the address the question named`() {
        // A closed loopback port: the attempt fails at once, and the failure names the address it went to.
        launch("palli://pair?u=http%3A%2F%2F127.0.0.1%3A1&c=K7QM-2XPA")
        waitFor("Pair with http://127.0.0.1:1?")
        assertAbsent("Can't reach PaLLi")                              // asking does not send

        compose.onNode(hasText("Pair") and hasAnyAncestor(isDialog())).performClick()
        waitFor("Can't reach PaLLi at http://127.0.0.1:1. Is Tailscale on, and is she running?")
    }

    @Test fun `a link that reaches the running Pair screen asks too`() {
        launch()
        waitFor("Pair with PaLLi")
        deliver(link)
        waitFor(question)
    }

    @Test fun `a rotation does not offer the same link again`() {
        launch(link)
        waitFor(question)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(question).fetchSemanticsNodes().isEmpty() }

        scenario!!.recreate()
        waitFor("Pair with PaLLi")
        compose.waitForIdle()
        assertAbsent(question)
    }

    @Test fun `a link on a cold start while paired is ignored, and does not wait to ambush a later unpair`() {
        val session = pairThisPhone()
        launch(link)
        waitFor(chatHint)
        compose.waitForIdle()
        assertAbsent("Pair with PaLLi")
        assertAbsent(question)
        assertEquals("still paired, as before", session, app.sessions.current)

        unpairThisPhone()
        waitFor("Pair with PaLLi")
        compose.waitForIdle()
        assertAbsent(question)
    }

    @Test fun `a link that reaches the running chat is ignored, and does not wait to ambush a later unpair`() {
        val session = pairThisPhone()
        launch()
        waitFor(chatHint)
        deliver(link)
        compose.waitForIdle()
        assertAbsent("Pair with PaLLi")
        assertAbsent(question)
        assertEquals(session, app.sessions.current)

        unpairThisPhone()
        waitFor("Pair with PaLLi")
        compose.waitForIdle()
        assertAbsent(question)
        compose.onNodeWithText("This phone was unpaired.").assertIsDisplayed()
    }

    @Test fun `losing the session anywhere comes back to Pair`() {
        pairThisPhone()
        launch()
        waitFor(chatHint)
        unpairThisPhone()
        waitFor("Pair with PaLLi")
        assertNull(app.sessions.current)
        assertAbsent(chatHint)
    }
}
