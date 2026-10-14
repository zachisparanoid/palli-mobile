package com.palli.mobile.domain

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.palli.mobile.data.local.EventEntity
import com.palli.mobile.data.local.PalliDatabase
import com.palli.mobile.data.local.PendingSendEntity
import com.palli.mobile.data.local.SendStates
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import com.palli.mobile.sync.SendWorker
import com.palli.mobile.ui.media.FileCache
import com.palli.mobile.ui.media.MediaCaches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real PairingRepository over a real database, session store and (test) WorkManager. */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PairingRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var db: PalliDatabase
    private lateinit var prefs: ServerPrefs
    private lateinit var sessions: SessionStore
    private lateinit var outgoing: OutgoingFiles
    private lateinit var work: WorkManager
    private lateinit var client: OkHttpClient
    private lateinit var images: ImageLoader
    private lateinit var files: FileCache
    private lateinit var repo: PairingRepository
    private val herFolder get() = File(context.cacheDir, "palli-files")

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        work = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, PalliDatabase::class.java).allowMainThreadQueries().build()
        prefs = ServerPrefs(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("p.preferences_pb")
        })
        sessions = SessionStore(prefs, PlainCipher)
        outgoing = OutgoingFiles(context)
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        files = FileCache(context, client, sessions)
        images = ImageLoader.Builder(context).okHttpClient(client)
            .memoryCache(MemoryCache.Builder(context).maxSizeBytes(1_000_000).build())
            .diskCache(DiskCache.Builder().directory(tmp.newFolder("coil")).maxSizeBytes(1_000_000).build())
            .build()
        repo = repoClearingWith(MediaCaches(files, images))                 // the real caches, unless a test says otherwise

        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_test", 1, "Phone"))
        db.events().insertAll(listOf(EventEntity.from(EventDto(id = 1, kind = EventKinds.MESSAGE, at = 1.0, text = "hi"))))
        db.pendingSends().upsert(PendingSendEntity("c1", "hello", "[]", SendStates.QUEUED, null, 1, 0))
        File(outgoing.dirFor("c1"), "cat.jpg").writeBytes(byteArrayOf(1, 2, 3))
        SendWorker.enqueue(work, "c1")
        // Her files as they sit on the phone: a fetched file, and a picture in each of Coil's caches.
        herFolder.mkdirs()
        File(herFolder, "f-voice").writeText("OggS-voice")
        images.memoryCache!!.set(MemoryCache.Key("her-photo"), MemoryCache.Value(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
        val editor = images.diskCache!!.openEditor("her-photo")!!
        images.diskCache!!.fileSystem.sink(editor.data).buffer().use { it.writeUtf8("pixels") }
        editor.commit()
        assertNotNull(images.memoryCache!!.get(MemoryCache.Key("her-photo")))      // the setup took
        assertNotNull(images.diskCache!!.openSnapshot("her-photo"))
    }

    @After fun tearDown() { db.close(); server.shutdown(); herFolder.deleteRecursively() }

    private fun repoClearingWith(cached: CachedFiles) =
        PairingRepository(ApiFactory(client), sessions, TimelineRepository(db, outgoing), work, cached)

    private fun sendWorkStates(): List<WorkInfo.State> = work.getWorkInfosByTag(SendWorker.TAG).get().map { it.state }

    private suspend fun assertWiped(reason: String?) {
        assertEquals(emptyList<EventEntity>(), db.events().observeLatest(10).first())
        assertEquals(emptyList<PendingSendEntity>(), db.pendingSends().observeAll().first())
        assertFalse(File(context.filesDir, "outgoing").exists())
        assertNull(sessions.current)
        assertEquals(reason, sessions.unpairedReason.value)
        assertNull(prefs.read().tokenBlob)                    // the token is gone from disk, not just from memory
        assertEquals(listOf(WorkInfo.State.CANCELLED), sendWorkStates())
        assertHerFilesGone()
    }

    /** What she sent must not outlive the pairing: not the files fetched for playing and saving, not a cached picture. */
    private fun assertHerFilesGone() {
        assertFalse(herFolder.exists())
        assertNull(images.memoryCache!!.get(MemoryCache.Key("her-photo")))
        assertNull(images.diskCache!!.openSnapshot("her-photo"))
    }

    @Test fun `unpaired forgets the timeline, the queue, the files and the token, and says why`() = runTest {
        assertEquals(listOf(WorkInfo.State.ENQUEUED), sendWorkStates())
        repo.unpaired("This phone was unpaired.")
        assertWiped("This phone was unpaired.")
    }

    @Test fun `the wipe finishes even when its caller is cancelled part way`() = runTest {
        // This is the 401 path: the caller is a SendWorker, and stopping the queue cancels that very worker.
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { repo.unpaired("This phone was unpaired.") }
        caller.cancel()
        caller.join()
        assertWiped("This phone was unpaired.")
    }

    @Test fun `unpairing by choice stops the queue before telling her, then wipes with no reason`() = runTest {
        var queueAtRevoke: List<WorkInfo.State>? = null
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                queueAtRevoke = sendWorkStates()
                return MockResponse().setBody("""{"ok":true}""")
            }
        }
        repo.unpairThisPhone()

        assertEquals(listOf(WorkInfo.State.CANCELLED), queueAtRevoke)
        assertEquals(1, requests.size)
        assertEquals("DELETE", requests[0].method)
        assertEquals("/api/mobile/devices/1", requests[0].path)
        assertEquals("Bearer pmd_test", requests[0].getHeader("Authorization"))
        assertWiped(null)
    }

    @Test fun `she being unreachable does not stop him unpairing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        repo.unpairThisPhone()
        assertTrue(server.requestCount == 1)
        assertWiped(null)
    }

    @Test fun `a pairing that ends under her (4401, a 401, a revoked device) takes her cached files and pictures with it`() = runTest {
        assertTrue(herFolder.exists())
        repo.unpaired("This phone was unpaired.")
        assertHerFilesGone()
    }

    @Test fun `a pairing he ends takes her cached files and pictures with it`() = runTest {
        assertTrue(herFolder.exists())
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        repo.unpairThisPhone()
        assertHerFilesGone()
    }

    @Test fun `files that cannot be cleared do not keep the token or the queue`() = runTest {
        val tried = mutableListOf<String>()
        repoClearingWith { tried += "cleared"; throw IOException("disk") }.unpaired("This phone was unpaired.")

        assertEquals(listOf("cleared"), tried)               // it was tried
        assertEquals(emptyList<EventEntity>(), db.events().observeLatest(10).first())
        assertNull(sessions.current)
        assertNull(prefs.read().tokenBlob)
        assertEquals(listOf(WorkInfo.State.CANCELLED), sendWorkStates())
    }

    @Test fun `unpairing by choice runs whole when the screen goes away while she is being told`() = runTest {
        val revoking = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                revoking.countDown()
                letGo.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"ok":true}""")
            }
        }
        // The screen's scope, as a view model's is: it is cancelled the moment the screen is left.
        val screen = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val unpairing = screen.launch { repo.unpairThisPhone() }
        assertTrue("the revoke never started", revoking.await(10, TimeUnit.SECONDS))

        screen.cancel()                                      // back is pressed mid-revoke
        letGo.countDown()
        unpairing.join()

        assertWiped(null)                                    // still whole: the queue is stopped and the session gone
    }
}
