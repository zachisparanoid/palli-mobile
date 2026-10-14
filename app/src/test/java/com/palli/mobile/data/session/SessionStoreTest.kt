package com.palli.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Reversal stands in for AES-GCM; the Keystore half is proven on a device (Task 12). */
    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String) = "sealed:" + plain.reversed()
        override fun decrypt(blob: String): String {
            require(blob.startsWith("sealed:")) { "not ours" }
            return blob.removePrefix("sealed:").reversed()
        }
    }

    private val session = Session("http://100.64.0.7:8000", "pmd_secret", 7, "Pixel 9")

    /** One run of the app over [file]: the store it would open (the production one), and its end, which lets go of the file. */
    private inner class Run(file: File) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(ServerPrefs.store(scope) { file })
        fun sessions() = SessionStore(prefs, FakeCipher())
        suspend fun end() = scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun `a saved session survives a restart and is cleared with its reason`() = runTest {
        val file = tmp.newFile("palli.preferences_pb")
        val run = Run(file)
        run.sessions().save(session)

        val again = run.sessions()
        assertEquals(session, again.load())
        assertEquals(session, again.current)

        again.clear("This phone was unpaired.")
        assertNull(again.current)
        assertEquals("This phone was unpaired.", again.unpairedReason.value)
        assertNull(run.sessions().load())
        run.end()
    }

    @Test fun `why the phone was unpaired survives the process, and a new pairing clears it`() = runTest {
        val file = tmp.newFile("palli.preferences_pb")
        val first = Run(file)
        first.sessions().save(session)
        first.sessions().clear("This phone was unpaired.")         // revoked while the app was in the background
        first.end()                                                // and then the process was killed

        val second = Run(file)
        val opened = second.sessions()
        assertNull(opened.load())
        assertEquals("This phone was unpaired.", opened.unpairedReason.value)
        opened.save(session)                                       // paired again
        assertNull(opened.unpairedReason.value)
        second.end()

        val third = Run(file)
        val again = third.sessions()
        assertEquals(session, again.load())
        assertNull(again.unpairedReason.value)
        again.clear(null)                                          // he chose to unpair: no reason to give
        third.end()

        val fourth = Run(file)
        val chosen = fourth.sessions()
        assertNull(chosen.load())
        assertNull(chosen.unpairedReason.value)
        fourth.end()
    }

    @Test fun `a token that will not unseal is no session, and says the key was lost, after a restart too`() = runTest {
        val file = tmp.newFile("broken.preferences_pb")
        val first = Run(file)
        first.prefs.write("http://h:8000", 1, "Phone", "garbage")
        val sessions = first.sessions()
        assertNull(sessions.load())
        assertEquals(SessionStore.KEY_LOST, sessions.unpairedReason.value)
        first.end()

        val second = Run(file)
        val again = second.sessions()
        assertNull(again.load())
        assertEquals(SessionStore.KEY_LOST, again.unpairedReason.value)
        again.save(session)                                        // a new pairing seals a new token, and the reason goes
        assertNull(again.unpairedReason.value)
        assertEquals(session, second.sessions().load())
        second.end()
    }

    @Test fun `a damaged settings file opens unpaired, does not crash, and says why, after a restart too`() = runTest {
        // Not a preferences proto: field 1, length 127, and then the file ends.
        val file = tmp.newFile("palli.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x0A)) }
        val first = Run(file)
        val sessions = first.sessions()
        assertNull(sessions.load())
        assertEquals(ServerPrefs.DAMAGED, sessions.unpairedReason.value)
        first.end()

        val second = Run(file)
        val again = second.sessions()
        assertNull(again.load())
        assertEquals(ServerPrefs.DAMAGED, again.unpairedReason.value)
        again.save(session)                                        // and the phone pairs again as usual
        assertEquals(session, second.sessions().load())
        assertNull(again.unpairedReason.value)
        second.end()
    }

    @Test fun `a lost key that cannot even be written down still opens unpaired, and never throws`() = runTest {
        val file = tmp.newFile("full.preferences_pb")
        val first = Run(file)
        first.prefs.write("http://h:8000", 1, "Phone", "garbage")
        first.end()

        // The next start, on a full disk: reading works, every write fails.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val real = ServerPrefs.store(scope) { file }
        val fullDisk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = real.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("No space left on device")
        }
        val sessions = SessionStore(ServerPrefs(fullDisk), FakeCipher())

        assertNull(sessions.load())
        assertEquals(SessionStore.KEY_LOST, sessions.unpairedReason.value)    // said for this run, if not kept
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
