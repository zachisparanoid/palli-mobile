package com.palli.mobile.ui.media

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var cache: FileCache

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("f.preferences_pb")
        }
        val sessions = SessionStore(ServerPrefs(store), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_files", 1, "Phone"))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        cache = FileCache(ApplicationProvider.getApplicationContext(), client, sessions)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a file is fetched once with the token, then read from the cache`() = runTest {
        server.enqueue(MockResponse().setBody("OggS-voice"))
        val first = cache.get("f_abc")
        val second = cache.get("f_abc")
        assertEquals("OggS-voice", second.readText())
        assertEquals(first.path, second.path)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/api/mobile/files/f_abc", request.path)
        assertEquals("Bearer pmd_files", request.getHeader("Authorization"))
    }

    @Test fun `a file PaLLi no longer has says so`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val error = runCatching { cache.get("f_gone") }.exceptionOrNull()
        assertTrue(error is FileCache.FileGone)
    }

    @Test fun `the token rides only in the Authorization header - not in the address, a query, another header or the cached file`() = runTest {
        server.enqueue(MockResponse().setBody("%PDF"))
        val file = cache.get("f_doc")

        val request = server.takeRequest()
        assertNull("no query string at all", request.requestUrl!!.query)
        assertFalse(request.requestLine.contains("pmd_files"))
        assertFalse(request.requestUrl.toString().contains("pmd_files"))
        val carrying = request.headers.names().filter { request.headers.values(it).any { v -> v.contains("pmd_files") } }
        assertEquals(listOf("Authorization"), carrying.toList())
        assertFalse(file.path.contains("pmd_files"))
        assertFalse(cache.urlFor(server.url("/").toString(), "f_doc").contains("pmd_files"))
    }

    @Test fun `a failure says what PaLLi answered, never the token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val error = runCatching { cache.get("f_boom") }.exceptionOrNull()!!
        assertFalse(error.toString().contains("pmd_files"))
        assertTrue(error.message!!.contains("500"))
    }

    @Test fun `an id is one escaped path segment under any path prefix, and its cached copy stays in the cache`() = runTest {
        assertEquals("http://palli-box.example.ts.net:8000/palli/api/mobile/files/f_1",
            cache.urlFor("http://palli-box.example.ts.net:8000/palli/", "f_1"))
        assertEquals("http://100.64.0.7:8000/api/mobile/files/..%2F..%2Fsecret",
            cache.urlFor("http://100.64.0.7:8000", "../../secret"))

        server.enqueue(MockResponse().setBody("x"))
        val file = cache.get("../../shared_prefs/evil")
        val folder = java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "palli-files")
        assertEquals(folder.canonicalPath, file.canonicalFile.parentFile!!.canonicalPath)
    }
}
