package com.palli.mobile.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @Test fun `a saved session survives a restart and is cleared with its reason`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("palli.preferences_pb")
        }
        val prefs = ServerPrefs(store)
        val session = Session("http://100.87.134.112:8000", "pmd_secret", 7, "Pixel 9")
        SessionStore(prefs, FakeCipher()).save(session)

        val again = SessionStore(prefs, FakeCipher())
        assertEquals(session, again.load())
        assertEquals(session, again.current)

        again.clear("This phone was unpaired.")
        assertNull(again.current)
        assertEquals("This phone was unpaired.", again.unpairedReason.value)
        assertNull(SessionStore(prefs, FakeCipher()).load())
    }

    @Test fun `a token that will not unseal is no session`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("broken.preferences_pb")
        }
        val prefs = ServerPrefs(store)
        prefs.write("http://h:8000", 1, "Phone", "garbage")
        assertNull(SessionStore(prefs, FakeCipher()).load())
    }
}
