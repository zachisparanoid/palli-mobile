package com.palli.mobile.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the notification question is remembered across a cold start, so a denial is not asked again`() = runTest {
        val file = tmp.newFile("palli.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(false, prefs.askedNotifications())

        prefs.markAskedNotifications()
        assertEquals(true, prefs.askedNotifications())
        first.cancel()                                             // the process ends

        val second = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ServerPrefs(PreferenceDataStoreFactory.create(scope = second) { file })
        assertEquals(true, again.askedNotifications())
        assertEquals(false, again.askedBattery())                   // and it is its own flag
        second.cancel()
    }
}
