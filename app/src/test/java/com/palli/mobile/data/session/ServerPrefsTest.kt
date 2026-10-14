package com.palli.mobile.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Ends a DataStore's scope and waits until it has let go of its file: a second DataStore on the same file is refused before that. */
    private suspend fun CoroutineScope.end() { coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun `the notification question is remembered across a cold start, so a denial is not asked again`() = runTest {
        val file = tmp.newFile("palli.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(false, prefs.askedNotifications())

        prefs.markAskedNotifications()
        assertEquals(true, prefs.askedNotifications())
        first.end()                                             // the process ends

        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(true, again.askedNotifications())
        assertEquals(false, again.askedBattery())                   // and it is its own flag
        second.cancel()
    }

    @Test fun `the last api version heard survives a cold start, and goes with the pairing`() = runTest {
        val file = tmp.newFile("v.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(null, prefs.lastApiVersion())
        prefs.setLastApiVersion(2)
        first.end()

        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(2, again.lastApiVersion())
        again.clearPairing()
        assertEquals(null, again.lastApiVersion())
        second.cancel()
    }

    @Test fun `the battery question is remembered across a cold start, and unpairing does not bring it back`() = runTest {
        val file = tmp.newFile("b.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(false, prefs.askedBattery())

        prefs.markAskedBattery()
        assertEquals(true, prefs.askedBattery())
        first.end()                                             // the process ends

        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(true, again.askedBattery())
        assertEquals(false, again.askedNotifications())            // and it is its own flag
        again.clearPairing()                                       // a "no" belongs to the phone, not to one pairing
        assertEquals(true, again.askedBattery())
        second.cancel()
    }

    @Test fun `staying connected is on until it is turned off, and stays off across a cold start`() = runTest {
        val file = tmp.newFile("s.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(true, prefs.stayConnected.first())

        prefs.setStayConnected(false)
        assertEquals(false, prefs.stayConnected.first())
        first.end()

        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(false, again.stayConnected.first())
        again.clearPairing()                                       // it is the phone's choice, not the pairing's
        assertEquals(false, again.stayConnected.first())
        second.cancel()
    }

    @Test fun `a write whose caller is cancelled still lands, and the caller is still told it was cancelled`() = runTest {
        // The write is uncancellable (the copy held here must follow the file), but a screen that has been left must not go
        // on to do what it was going to do next: the chat launching a permission question from a launcher it no longer has.
        val file = tmp.newFile("c.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        var wentOn = false
        val caller = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            prefs.markAskedNotifications()
            wentOn = true
        }
        caller.cancel()                                            // the screen is left while the write is out
        caller.join()

        assertEquals("the cancelled caller must not carry on", false, wentOn)
        assertEquals("but the write landed", true, prefs.askedNotifications())
        first.end()
        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        assertEquals("and is on disk", true, ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file }).askedNotifications())
        second.cancel()
    }
}
