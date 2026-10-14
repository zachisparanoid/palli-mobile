package com.palli.mobile.domain

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A send's files live in the app's own storage until PaLLi has the message — a picker's URI grant can expire first. */
@Singleton
class OutgoingFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val root: File get() = File(context.filesDir, "outgoing")

    /** A picked file longer than the copy may be: what the picker said about its size was missing or wrong. */
    class TooLarge : IOException("The picked file is over the size PaLLi takes in one part.")

    fun dirFor(clientId: String): File = File(root, clientId).apply { mkdirs() }

    /**
     * Every copy gets its own file name, so two picks with the same display name (or none) never overwrite each other.
     * The name PaLLi sees is the attachment's own `name`, not this file's.
     *
     * The copy stops as soon as it passes [maxBytes] and throws [TooLarge]; a copy that fails in any way leaves no
     * partial file behind.
     */
    fun copyIn(clientId: String, uri: Uri, name: String, maxBytes: Long = SendLimits.MAX_PART_BYTES): File {
        val target = File(dirFor(clientId), "${UUID.randomUUID().toString().take(8)}-${safe(name)}")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The picked file could not be opened." }
                target.outputStream().use { copyAtMost(input, it, maxBytes) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return target
    }

    fun forget(clientId: String) { File(root, clientId).deleteRecursively() }
    fun forgetAll() { root.deleteRecursively() }

    private fun copyAtMost(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) throw TooLarge()
            output.write(buffer, 0, read)
        }
    }

    private fun safe(name: String): String =
        name.substringAfterLast('/').ifBlank { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
