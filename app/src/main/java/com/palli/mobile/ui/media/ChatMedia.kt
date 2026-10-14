package com.palli.mobile.ui.media

import android.content.Context
import android.util.Log
import coil.ImageLoader
import com.palli.mobile.data.remote.FileRef
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the bubbles need to show, play and save her files. The primary constructor takes the save itself and the
 * session, so a test can stand in for MediaStore; the `@Inject` one is what Hilt uses.
 *
 * A save runs on the app's scope, not a bubble's, so scrolling the bubble away does not cut it short. What it ends in
 * is one plain line on [said], which the chat shows over the composer.
 */
@Singleton
class ChatMedia(
    val imageLoader: ImageLoader,
    val player: VoicePlayer,
    val files: FileCache,
    private val session: () -> Session?,
    private val saveFile: suspend (file: File, filename: String, mime: String?) -> Downloads.Saved,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        imageLoader: ImageLoader,
        player: VoicePlayer,
        files: FileCache,
        sessions: SessionStore,
        @ApplicationContext context: Context,
        @AppScope scope: CoroutineScope,
    ) : this(imageLoader, player, files, { sessions.current },
        { file, name, mime -> Downloads.save(context, file, name, mime) }, scope)

    private val _said = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val said: SharedFlow<String> = _said.asSharedFlow()
    private val saving = Collections.synchronizedSet(mutableSetOf<String>())

    /** The address of her file [id] on the paired server; null when unpaired. It never carries the token. */
    fun urlFor(id: String): String? = session()?.let { files.urlFor(it.baseUrl, id) }

    /** Saves her file into Downloads/PaLLi (a second tap while one is saving does nothing), then says where it went. */
    fun download(file: FileRef) {
        if (!saving.add(file.id)) return
        scope.launch {
            try {
                _said.tryEmit(save(file))
            } finally {
                saving.remove(file.id)
            }
        }
    }

    /** The line to say about saving [file]. Never throws but for cancellation. */
    suspend fun save(file: FileRef): String {
        val cached = try {
            files.get(file.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileCache.FileGone) {
            return GONE
        } catch (e: Exception) {
            Log.w(TAG, "a file could not be fetched: ${e.javaClass.simpleName}")
            return FETCH_FAILED
        }
        return try {
            SAVED_TO + saveFile(cached, file.filename.ifBlank { file.id }, file.contentType).where
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a file could not be saved: ${e.javaClass.simpleName}")
            SAVE_FAILED
        }
    }

    companion object {
        const val SAVED_TO = "Saved to "
        const val GONE = "That file is no longer on PaLLi's machine."
        const val FETCH_FAILED = "Couldn't fetch that file from PaLLi. Is Tailscale on?"
        const val SAVE_FAILED = "Couldn't save that file on this phone."
        private const val TAG = "ChatMedia"
    }
}
