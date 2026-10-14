package com.palli.mobile.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStoreException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The token's seal, over a stand-in for Android Keystore (Robolectric has none): a software AES key in a box that can be
 * made to fail the way a Keystore entry does. Robolectric, for android.util.Base64. What only a device can show: the
 * real AndroidKeyStore entry going unrecoverable (a lock-screen change on some phones, a Keystore reset), and its
 * deleteEntry and regeneration in the secure hardware.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreTokenCipherTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One key under one alias, as the Keystore holds it. [breakWith] makes reading it throw until it is deleted. */
    private class FakeVault(private val slowMs: Long = 0) : KeyVault {
        @Volatile var key: SecretKey? = null
        @Volatile var breakWith: Exception? = null
        val made = AtomicInteger()
        val deleted = AtomicInteger()

        override fun stored(): SecretKey? { breakWith?.let { throw it }; return key }
        override fun delete() { deleted.incrementAndGet(); breakWith = null; key = null }
        override fun generate(): SecretKey {
            made.incrementAndGet()
            Thread.sleep(slowMs)                                   // the Keystore takes its time; long enough for a race
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
    }

    @Test fun `a token sealed is unsealed, and the key is made once and kept`() {
        val vault = FakeVault()
        val cipher = KeystoreTokenCipher(vault)
        val blob = cipher.encrypt("pmd_test")
        assertTrue(!blob.contains("pmd_test"))
        assertEquals("pmd_test", cipher.decrypt(blob))
        assertEquals("pmd_test", KeystoreTokenCipher(vault).decrypt(blob))      // after a restart, the same key
        assertEquals(1, vault.made.get())
    }

    @Test fun `two first callers at once make one key between them, so neither seals under a key that is then replaced`() {
        val vault = FakeVault(slowMs = 100)
        val cipher = KeystoreTokenCipher(vault)
        val callers = 8
        val start = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)
        val blobs = (1..callers).map { n -> pool.submit<String> { start.await(); cipher.encrypt("pmd_$n") } }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, vault.made.get())
        blobs.forEachIndexed { i, blob -> assertEquals("pmd_${i + 1}", cipher.decrypt(blob)) }
    }

    @Test fun `a key that cannot be read when unsealing is left where it is, and reads again once the error passes`() {
        for (failure in listOf(UnrecoverableKeyException("busy"), UnrecoverableEntryException("busy"), KeyStoreException("binder"))) {
            val vault = FakeVault()
            val cipher = KeystoreTokenCipher(vault)
            val blob = cipher.encrypt("pmd_test")
            val key = vault.key
            vault.breakWith = failure                              // Android 12+ can say this for a passing system error

            assertTrue(failure.javaClass.simpleName, runCatching { cipher.decrypt(blob) }.isFailure)
            assertEquals(0, vault.deleted.get())                   // nothing is deleted on the way in
            assertEquals(1, vault.made.get())
            assertTrue(vault.key === key)

            vault.breakWith = null                                 // the next start
            assertEquals("pmd_test", cipher.decrypt(blob))
        }
    }

    @Test fun `a key that cannot be read when sealing, a new pairing, is deleted and made again, so the phone pairs`() {
        for (failure in listOf(UnrecoverableKeyException("gone"), UnrecoverableEntryException("gone"), KeyStoreException("gone"))) {
            val vault = FakeVault()
            val cipher = KeystoreTokenCipher(vault)
            cipher.encrypt("pmd_old")
            vault.breakWith = failure

            val sealed = cipher.encrypt("pmd_new")                 // pairing again overwrites the old blob anyway
            assertEquals(1, vault.deleted.get())
            assertEquals(2, vault.made.get())
            assertEquals("pmd_new", cipher.decrypt(sealed))
        }
    }

    @Test fun `with no key at all, unsealing makes none`() {
        val vault = FakeVault()
        assertTrue(runCatching { KeystoreTokenCipher(vault).decrypt("AAAAAAAAAAAAAAAA:AAAA") }.isFailure)
        assertEquals(0, vault.made.get())
    }

    @Test fun `a lost key opens to Pair saying so, and a new pairing works`() = runTest {
        val vault = FakeVault()
        val cipher = KeystoreTokenCipher(vault)
        val prefs = ServerPrefs(ServerPrefs.store(CoroutineScope(Dispatchers.IO + SupervisorJob())) { tmp.newFile("k.preferences_pb") })
        val session = Session("http://palli-box.example.ts.net:8000", "pmd_test", 7, "Pixel 9")
        SessionStore(prefs, cipher).save(session)
        vault.breakWith = UnrecoverableKeyException("gone")

        val sessions = SessionStore(prefs, cipher)
        assertNull(sessions.load())
        assertEquals(SessionStore.KEY_LOST, sessions.unpairedReason.value)

        sessions.save(session)
        assertEquals(session, SessionStore(prefs, cipher).load())
    }
}
