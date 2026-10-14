package com.palli.mobile.domain

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A send's files live in the app's own storage until PaLLi has the message — a picker's URI grant can expire first. */
@Singleton
class OutgoingFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val root: File get() = File(context.filesDir, "outgoing")

    fun dirFor(clientId: String): File = File(root, clientId).apply { mkdirs() }

    fun copyIn(clientId: String, uri: Uri, name: String): File {
        val target = File(dirFor(clientId), safe(name))
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The picked file could not be opened." }
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    fun forget(clientId: String) { File(root, clientId).deleteRecursively() }
    fun forgetAll() { root.deleteRecursively() }

    private fun safe(name: String): String =
        name.substringAfterLast('/').ifBlank { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
