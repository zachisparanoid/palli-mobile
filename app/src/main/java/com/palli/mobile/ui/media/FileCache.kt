package com.palli.mobile.ui.media

import android.content.Context
import com.palli.mobile.data.session.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Her files, fetched by id on the app's client, so the token rides only in the `Authorization` header that
 * AuthInterceptor adds for the paired server: never in a URL, a query string or a file name. Coil fetches images
 * itself on the same client; this is for voice notes and for files being saved.
 */
@Singleton
class FileCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val sessions: SessionStore,
) {
    class FileGone : Exception("That file is no longer on PaLLi's machine.")

    /** `<base>/api/mobile/files/<id>`, the id as one path segment (escaped), under any path prefix the base has. */
    fun urlFor(baseUrl: String, id: String): String {
        val files = "${baseUrl.trimEnd('/')}/api/mobile/files/"
        return files.toHttpUrlOrNull()?.newBuilder()?.addPathSegment(id)?.build()?.toString() ?: (files + id)
    }

    /** Where her fetched files are kept: one folder, so all of them can go at once. */
    private val folder get() = File(context.cacheDir, "palli-files")

    /** Forgets every file fetched so far (they are fetched again when wanted). Throws if the folder could not be removed. */
    fun clear() {
        val dir = folder
        if (dir.exists() && !dir.deleteRecursively() && dir.exists()) throw IOException("Her cached files could not be removed.")
    }

    suspend fun get(id: String): File = withContext(Dispatchers.IO) {
        val dir = folder.apply { mkdirs() }
        val target = File(dir, cacheName(id))
        if (target.isFile && target.length() > 0) return@withContext target
        val session = sessions.current ?: throw IOException("This phone is not paired.")
        client.newCall(Request.Builder().url(urlFor(session.baseUrl, id)).build()).execute().use { response ->
            if (response.code == 404) throw FileGone()
            if (!response.isSuccessful) throw IOException("PaLLi answered ${response.code}")
            val body = response.body ?: throw IOException("PaLLi sent no file")
            // Each fetch writes its own part file, so two taps at once cannot interleave; the rename is the commit.
            val part = File.createTempFile("fetch-", ".part", dir)
            try {
                body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(target)) throw IOException("The file could not be cached.")
            } finally {
                part.delete()
            }
        }
        target
    }

    /** A name that stays inside the cache folder whatever the id holds: no separators, no dots, nothing to climb with. */
    private fun cacheName(id: String): String =
        "f-" + id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + "-" + Integer.toHexString(id.hashCode())
}
