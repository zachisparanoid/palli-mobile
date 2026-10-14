package com.palli.mobile.ui.media

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/** Her files kept on this phone for showing and playing again: all of it goes, the files and both of Coil's caches. */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaCachesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var files: FileCache
    private lateinit var images: ImageLoader
    private lateinit var caches: MediaCaches
    private val folder get() = File(context.cacheDir, "palli-files")

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("m.preferences_pb")
        }
        val sessions = SessionStore(ServerPrefs(store), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_media", 1, "Phone"))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        files = FileCache(context, client, sessions)
        images = ImageLoader.Builder(context).okHttpClient(client)
            .memoryCache(MemoryCache.Builder(context).maxSizeBytes(1_000_000).build())
            .diskCache(DiskCache.Builder().directory(tmp.newFolder("coil")).maxSizeBytes(1_000_000).build())
            .build()
        caches = MediaCaches(files, images)
    }

    @After fun tearDown() {
        server.shutdown()
        folder.deleteRecursively()
    }

    private fun cacheOneImage() {
        images.memoryCache!!.set(MemoryCache.Key("her-photo"), MemoryCache.Value(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)))
        val editor = images.diskCache!!.openEditor("her-photo")!!
        images.diskCache!!.fileSystem.sink(editor.data).buffer().use { it.writeUtf8("pixels") }
        editor.commit()
        assertNotNull(images.memoryCache!!.get(MemoryCache.Key("her-photo")))      // the setup took
        assertNotNull(images.diskCache!!.openSnapshot("her-photo"))
    }

    @Test fun `clearing forgets her fetched files and both of Coil's caches`() = runTest {
        server.enqueue(MockResponse().setBody("OggS-voice"))
        files.get("f_voice")
        assertTrue(folder.listFiles()!!.isNotEmpty())
        cacheOneImage()

        caches.clear()

        assertFalse("her files must not outlive the pairing", folder.exists())
        assertNull(images.memoryCache!!.get(MemoryCache.Key("her-photo")))
        assertEquals(emptySet<MemoryCache.Key>(), images.memoryCache!!.keys)
        assertNull(images.diskCache!!.openSnapshot("her-photo"))
    }

    @Test fun `a cache that was never filled clears without complaint, and the next pairing fetches afresh`() = runTest {
        caches.clear()
        caches.clear()
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        assertEquals("one", files.get("f_1").readText())
        caches.clear()
        assertEquals("two", files.get("f_1").readText())                 // not the old copy
        assertEquals(2, server.requestCount)
    }

    @Test fun `one cache failing to clear does not stop the others, and the failure is not hidden`() = runTest {
        cacheOneImage()
        val broken = MediaCaches(clearFiles = { throw java.io.IOException("disk") }, images = images)

        val failure = runCatching { broken.clear() }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertNull(images.memoryCache!!.get(MemoryCache.Key("her-photo")))
        assertNull(images.diskCache!!.openSnapshot("her-photo"))
    }
}
