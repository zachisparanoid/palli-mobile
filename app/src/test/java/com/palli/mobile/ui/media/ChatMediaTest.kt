package com.palli.mobile.ui.media

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import com.palli.mobile.data.remote.AuthInterceptor
import com.palli.mobile.data.remote.FileRef
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.data.session.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** Saving her files: fetched with the token by FileCache, handed to the save, and one plain line said about it. */
@RunWith(RobolectricTestRunner::class)
class ChatMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var sessions: SessionStore
    private lateinit var media: ChatMedia
    private val handed = mutableListOf<Triple<String, String, String?>>()
    private var saveFailure: Exception? = null

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUp() = runTest {
        server.start()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            tmp.newFile("m.preferences_pb")
        }
        sessions = SessionStore(ServerPrefs(store), PlainCipher)
        sessions.save(Session(server.url("/").toString().trimEnd('/'), "pmd_media", 1, "Phone"))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { sessions.current }).build()
        media = ChatMedia(
            ImageLoader.Builder(context).build(), VoicePlayer(context), FileCache(context, client, sessions),
            session = { sessions.current },
            saveFile = { file, name, mime ->
                saveFailure?.let { throw it }
                handed += Triple(file.readText(), name, mime)
                Downloads.Saved(Uri.parse("content://media/external/downloads/7"), "Download/PaLLi/${Downloads.safeName(name)}")
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a saved file says where it went, and the save got her bytes, her file's name and its type`() = runTest {
        server.enqueue(MockResponse().setBody("%PDF-1.7"))
        val line = media.save(FileRef("f_bill", "Bill.pdf", "application/pdf"))
        assertEquals("Saved to Download/PaLLi/Bill.pdf", line)
        assertEquals(listOf(Triple("%PDF-1.7", "Bill.pdf", "application/pdf")), handed)
        assertEquals("Bearer pmd_media", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `a file with no name is saved under its id`() = runTest {
        server.enqueue(MockResponse().setBody("x"))
        media.save(FileRef("f_anon", "", "application/octet-stream"))
        assertEquals("f_anon", handed.single().second)
    }

    @Test fun `a file PaLLi no longer has says so plainly, and nothing is saved`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ChatMedia.GONE, media.save(FileRef("f_gone", "old.pdf")))
        assertEquals(emptyList<Any>(), handed)
    }

    @Test fun `a fetch that fails and a save that fails each say the plain thing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ChatMedia.FETCH_FAILED, media.save(FileRef("f_a", "a.pdf")))
        server.enqueue(MockResponse().setBody("x"))
        saveFailure = IOException("disk full")
        assertEquals(ChatMedia.SAVE_FAILED, media.save(FileRef("f_b", "b.pdf")))
    }

    @Test fun `download says its line on said, and the line never carries the token`() = runTest {
        server.enqueue(MockResponse().setBody("x"))
        val heard = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listening = backgroundScope.launch(Dispatchers.Unconfined) { media.said.collect { heard += it } }
        media.download(FileRef("f_c", "c.txt", "text/plain"))
        server.takeRequest()
        kotlinx.coroutines.withContext(Dispatchers.Default) { while (heard.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("Saved to Download/PaLLi/c.txt"), heard)
        assertFalse(heard.single().contains("pmd_media"))
        listening.cancel()
    }

    @Test fun `her file's address never carries the token, and there is none once unpaired`() = runTest {
        assertFalse(media.urlFor("f_x")!!.contains("pmd_media"))
        sessions.clear()
        assertNull(media.urlFor("f_x"))
    }
}
