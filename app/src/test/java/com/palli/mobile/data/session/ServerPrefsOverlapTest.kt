package com.palli.mobile.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A plain JVM, real threads, a real DataStore file: no Robolectric, no test dispatchers.
 *
 * DataStore 1.1.7 (the version this app is pinned to) can lose an update for a collector that starts while a write is in
 * flight: the new collector is handed the old value and then never the new one, while a collector that was already
 * running, and any collector started after the write, see it. On a bare DataStore this showed as roughly one overlap in a
 * hundred. ServerPrefs therefore does not collect DataStore after its first read; see its class comment. These tests are
 * that guarantee: a reader that starts at the moment of a write always gets to see the write.
 */
class ServerPrefsOverlapTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs [block] on real threads and waits for it, so a hang is a failure here and not a stuck build. */
    private fun <T> onThreads(seconds: Long = 30, block: suspend CoroutineScope.() -> T): T {
        val done = CompletableFuture<T>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch { try { done.complete(block()) } catch (e: Throwable) { done.completeExceptionally(e) } }
        try { return done.get(seconds, TimeUnit.SECONDS) } finally { scope.cancel() }
    }

    private fun prefs(round: Int, scope: CoroutineScope) =
        ServerPrefs(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("overlap-$round.preferences_pb") })

    @Test fun `a reader that starts as a write does still sees the write - the stay-connected switch and the controller watching it`() {
        val hung = ConcurrentLinkedQueue<String>()
        repeat(ROUNDS) { round ->
            val store = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val prefs = prefs(round, store)
            val watched = ConcurrentLinkedQueue<Boolean>()
            onThreads {
                launch { prefs.stayConnected.collect { watched += it } }            // what ConnectionController keeps running
                prefs.stayConnected.first()
                repeat(ITERATIONS) { n ->
                    val on = n % 2 == 1                                              // the stored default is true, so the first write is a change
                    try {
                        withTimeout(WAIT_MS) {
                            val write = async(Dispatchers.IO) { prefs.setStayConnected(on) }
                            // several readers, each starting a little later than the last, so one lands in the write's window
                            val readers = List(READERS) { r ->
                                async(Dispatchers.IO) { repeat(r * 2) { yield() }; prefs.stayConnected.first { it == on } }
                            }
                            awaitAll(write, *readers.toTypedArray())
                        }
                    } catch (e: Throwable) {
                        hung += "round $round write $n (to $on): the reader never saw it"
                    }
                }
                val last = (ITERATIONS - 1) % 2 == 1
                withTimeout(WAIT_MS) { while (watched.lastOrNull() != last) kotlinx.coroutines.delay(5) }   // the running one saw it too
            }
            store.cancel()
        }
        assertTrue("${hung.size} readers waited on a write that had landed: ${hung.take(5)}", hung.isEmpty())
    }

    @Test fun `a flag written and then read is read as written, however the two overlap`() {
        val stale = ConcurrentLinkedQueue<String>()
        repeat(ROUNDS) { round ->
            val store = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val prefs = prefs(round, store)
            onThreads {
                val writes = async(Dispatchers.IO) { prefs.markAskedBattery() }
                val reads = async(Dispatchers.IO) { prefs.askedBattery() }          // before or after the write: either is true to the order
                awaitAll(writes, reads)
                if (!prefs.askedBattery()) stale += "round $round: written, then read as not written"   // after it can only be true
            }
            store.cancel()
        }
        assertEquals(emptyList<String>(), stale.toList())
    }

    private companion object {
        const val ROUNDS = 5
        const val ITERATIONS = 10
        const val READERS = 4
        const val WAIT_MS = 5_000L
    }
}
