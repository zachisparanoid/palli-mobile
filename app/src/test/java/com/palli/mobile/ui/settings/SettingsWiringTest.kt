package com.palli.mobile.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import coil.ImageLoader
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.domain.OutgoingFiles
import com.palli.mobile.domain.PairingRepository
import com.palli.mobile.domain.TimelineRepository
import com.palli.mobile.sync.SendWorker
import com.palli.mobile.ui.media.FileCache
import com.palli.mobile.ui.media.MediaCaches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The settings' view model built the way Hilt builds it (its `@Inject` constructor), over the real session store, the real
 * preferences and the real PairingRepository, with only the network a MockWebServer: what the switch and the Unpair
 * button are wired to. The seams in SettingsViewModelTest cannot say that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsWiringTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val store = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: PalliDatabase
    private lateinit var prefs: ServerPrefs
    private lateinit var sessions: SessionStore
    private lateinit var work: WorkManager
    private lateinit var vm: SettingsViewModel

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)                   // real threads, as on a phone
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        work = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = store) { tmp.newFile("w.preferences_pb") })
        sessions = SessionStore(prefs, PlainCipher)
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        val pairing = PairingRepository(ApiFactory(client), sessions, TimelineRepository(db, OutgoingFiles(context)), work,
            MediaCaches(FileCache(context, client, sessions), ImageLoader.Builder(context).build()))
        awaiting { sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_test", 1, "Phone")) }
        SendWorker.enqueue(work)
        vm = SettingsViewModel(sessions, prefs, pairing)         // the @Inject constructor
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close(); server.shutdown(); store.cancel()
    }

    @Test fun `it shows the session the session store holds`() {
        assertEquals("Phone", vm.session.value?.deviceName)
        assertEquals(server.url("/").toString().trimEnd('/'), vm.session.value?.baseUrl)
    }

    @Test fun `the switch is wired to the stored stay-connected choice, both ways`() {
        assertEquals(true, awaiting { vm.stayConnected.first { it != null } })
        repeat(4) { n ->
            val on = n % 2 == 1
            vm.setStayConnected(on)
            assertEquals(on, awaiting { prefs.stayConnected.first { it == on } })
            assertEquals(on, awaiting { vm.stayConnected.first { it == on } })
        }
    }

    @Test fun `unpair is wired to the real unpairing - she is told, the session and the queue go`() {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(listOf(WorkInfo.State.ENQUEUED), work.getWorkInfosByTag(SendWorker.TAG).get().map { it.state })

        vm.unpair()
        awaiting { sessions.session.first { it == null } }

        val revoke = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(revoke)
        assertEquals("DELETE", revoke!!.method)
        assertEquals("/api/mobile/devices/1", revoke.path)
        assertNull(awaiting { prefs.read().tokenBlob })
        assertEquals(listOf(WorkInfo.State.CANCELLED), work.getWorkInfosByTag(SendWorker.TAG).get().map { it.state })
    }

    /** Waits on real threads for something that happens on real threads, up to ten seconds. */
    private fun <T> awaiting(block: suspend () -> T): T {
        val done = CompletableFuture<T>()
        CoroutineScope(Dispatchers.Default).launch {
            try { done.complete(withTimeout(10_000) { block() }) } catch (e: Throwable) { done.completeExceptionally(e) }
        }
        return done.get(15, TimeUnit.SECONDS)
    }
}
