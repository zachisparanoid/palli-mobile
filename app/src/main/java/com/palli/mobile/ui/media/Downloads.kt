package com.palli.mobile.ui.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A file she made, saved where he can find it. The bytes come from [FileCache] (authenticated OkHttp); this only copies
 * them out. Not `DownloadManager`: that would need the token as a request header, and it keeps request headers in plain
 * text in the system's downloads database, where a token only this phone holds must never go.
 *
 * Android 10 and later: the public `Download/PaLLi/` folder, through MediaStore, which needs no permission. Before
 * that, the public folder needs a storage permission, so it is the app's own Downloads folder, which needs none.
 */
object Downloads {
    const val FOLDER = "PaLLi"

    /** Where it went: [uri] to open it by, and [where] in words for the owner. */
    data class Saved(val uri: Uri, val where: String)

    suspend fun save(context: Context, file: File, filename: String, mime: String? = null): Saved =
        withContext(Dispatchers.IO) {
            val name = safeName(filename)
            if (Build.VERSION.SDK_INT >= 29) toMediaStore(context, file, name, mime) else toAppFolder(context, file, name)
        }

    /**
     * A name that is only a name: no folders (its last segment is kept), no characters a storage volume refuses, no
     * control characters, no leading dot (no hidden file, no "..") and not overlong. Blank becomes "file".
     */
    fun safeName(filename: String): String {
        val cleaned = filename.substringAfterLast('/')
            .filter { it >= ' ' && it != '\u007f' }
            .replace(Regex("""[\\:*?"<>|]"""), "_")
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        if (cleaned.isEmpty()) return "file"
        if (cleaned.length <= MAX_NAME) return cleaned
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
        return if (ext == null) cleaned.take(MAX_NAME) else cleaned.take(MAX_NAME - ext.length - 1) + "." + ext
    }

    @RequiresApi(29)
    internal fun toMediaStore(context: Context, file: File, name: String, mime: String?): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // A real type keeps the store from guessing; "any bytes" is no type, and the name's extension says more.
            if (!mime.isNullOrBlank() && mime != "application/octet-stream") put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)        // hidden from other apps until it is whole
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The Downloads folder would not take the file.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("The Downloads folder would not open.")
            out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }    // no half-written file left behind
            throw e
        }
        // The folder may already hold that name, and MediaStore then picks another: say the one it chose.
        val finalName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: name
        return Saved(uri, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$finalName")
    }

    internal fun toAppFolder(context: Context, file: File, name: String): Saved {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("The phone's storage is not available.")
        dir.mkdirs()
        val target = freeName(dir, name)
        file.copyTo(target)
        @Suppress("DEPRECATION")                              // only for saying where it went, before Android 10
        val root = Environment.getExternalStorageDirectory()?.path
        val where = if (root != null && target.path.startsWith("$root/")) target.path.removePrefix("$root/") else target.path
        return Saved(Uri.fromFile(target), where)
    }

    /** `name`, or `name (1)`, `name (2)`… keeping the extension, so an earlier save is never overwritten. */
    private fun freeName(dir: File, name: String): File {
        var candidate = File(dir, name)
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$stem (${n++})$ext")
        return candidate
    }

    private const val MAX_NAME = 120
}
