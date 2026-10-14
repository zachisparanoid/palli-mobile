package com.palli.mobile.ui.media

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** MediaStore's Downloads collection, as far as a save uses it: rows of values, each with a file behind it. */
    class FakeMediaStore : ContentProvider() {
        val rows = mutableMapOf<Long, ContentValues>()
        val files = mutableMapOf<Long, File>()
        /** Whether each row was still pending while its bytes were written. */
        val pendingWhileWritten = mutableMapOf<Long, Int?>()
        var taken: String? = null

        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = rows.size + 1L
            rows[id] = ContentValues(values)
            taken?.let { rows[id]!!.put(MediaStore.MediaColumns.DISPLAY_NAME, it) }   // the store renames a clash
            files[id] = File.createTempFile("store", ".bin")
            return ContentUris.withAppendedId(uri, id)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = ContentUris.parseId(uri)
            pendingWhileWritten[id] = rows[id]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            return ParcelFileDescriptor.open(files[id]!!, ParcelFileDescriptor.parseMode(mode))
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            rows[ContentUris.parseId(uri)]!!.putAll(values); return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf(rows[ContentUris.parseId(uri)]!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)))
            }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
        override fun getType(uri: Uri): String? = null
    }

    private fun herFile(bytes: String) = tmp.newFile().apply { writeText(bytes) }

    // ---- the name ----

    @Test fun `a name is only a name - no folders, no leading dots, nothing a storage volume refuses`() {
        assertEquals("passwd", Downloads.safeName("../../etc/passwd"))
        assertEquals("bashrc", Downloads.safeName(".bashrc"))
        assertEquals("file", Downloads.safeName(".."))
        assertEquals("file", Downloads.safeName("   "))
        assertEquals("file", Downloads.safeName("folder/"))
        assertEquals("a_b_c.txt", Downloads.safeName("a\\b:c.txt"))
        assertEquals("report.pdf", Downloads.safeName("report.pdf\u0000"))
        assertEquals("Bill for March.pdf", Downloads.safeName("Bill for March.pdf"))
        val long = Downloads.safeName("x".repeat(300) + ".pdf")
        assertTrue(long.length <= 120)
        assertTrue(long.endsWith(".pdf"))
    }

    // ---- Android 10 and later: MediaStore ----

    @Test fun `on Android 10+ it goes into Download - PaLLi through MediaStore, hidden until it is whole`() = runTest {
        val store = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        val saved = Downloads.save(context, herFile("%PDF-1.7"), "../Bill.pdf", "application/pdf")

        val row = store.rows.values.single()
        assertEquals("Bill.pdf", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("Download/PaLLi", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("application/pdf", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(1, store.pendingWhileWritten.values.single())
        assertEquals(0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("%PDF-1.7", store.files.values.single().readText())
        assertEquals("Download/PaLLi/Bill.pdf", saved.where)
        assertTrue(saved.uri.toString().startsWith(MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString()))
    }

    @Test fun `a file of no particular type is left for the store to type by its name`() = runTest {
        val store = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        Downloads.save(context, herFile("x"), "notes.md", "application/octet-stream")
        assertFalse(store.rows.values.single().containsKey(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Test fun `when the folder already has that name, it says the name the store chose`() = runTest {
        val store = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        store.taken = "Bill (1).pdf"
        assertEquals("Download/PaLLi/Bill (1).pdf", Downloads.save(context, herFile("x"), "Bill.pdf").where)
    }

    @Test fun `a copy that fails leaves no half-written entry behind`() = runTest {
        val store = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        val missing = File(tmp.root, "not-there")
        val error = runCatching { Downloads.save(context, missing, "gone.bin") }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(store.rows.isEmpty())
    }

    // ---- before Android 10: the app's own Downloads folder ----

    @Test fun `before Android 10 it goes into the app's own Downloads folder, never over an earlier save`() {
        val first = Downloads.toAppFolder(context, herFile("one"), "Bill.pdf")
        val second = Downloads.toAppFolder(context, herFile("two"), "Bill.pdf")

        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
        assertEquals("one", File(dir, "Bill.pdf").readText())
        assertEquals("two", File(dir, "Bill (1).pdf").readText())
        assertTrue(first.where, first.where.endsWith("Download/Bill.pdf"))
        assertTrue(second.where, second.where.endsWith("Download/Bill (1).pdf"))
        assertFalse(second.where.startsWith("/storage/emulated/0/"))
    }
}
