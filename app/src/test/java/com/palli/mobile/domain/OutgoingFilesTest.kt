package com.palli.mobile.domain

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OutgoingFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outgoing = OutgoingFiles(context)

    private fun picked(name: String, vararg bytes: Byte): Uri =
        Uri.fromFile(tmp.newFile(name).apply { writeBytes(bytes) })

    @Test fun `two picks with the same name in one send stay two files`() {
        val first = outgoing.copyIn("c-1", picked("one.bin", 1, 1), "file")
        val second = outgoing.copyIn("c-1", picked("two.bin", 2, 2, 2), "file")

        assertNotEquals(first, second)
        assertEquals(first.parentFile, second.parentFile)
        assertArrayEquals(byteArrayOf(1, 1), first.readBytes())
        assertArrayEquals(byteArrayOf(2, 2, 2), second.readBytes())
        assertEquals(2, outgoing.dirFor("c-1").listFiles()!!.size)
    }

    @Test fun `a copy keeps the sanitised name and never leaves its send's folder`() {
        val copy = outgoing.copyIn("c-2", picked("x.bin", 9), "../../evil name.jpg")
        assertTrue(copy.name, copy.name.endsWith("-evil_name.jpg"))
        assertEquals(outgoing.dirFor("c-2").canonicalFile, copy.parentFile!!.canonicalFile)
    }

    @Test fun `a copy that runs past its limit stops, says so, and leaves no partial file`() {
        val error = runCatching { outgoing.copyIn("c-5", picked("big.bin", *ByteArray(11)), "big.bin", maxBytes = 10) }
            .exceptionOrNull()
        assertTrue(error is OutgoingFiles.TooLarge)
        assertEquals(0, outgoing.dirFor("c-5").listFiles()!!.size)

        val exact = outgoing.copyIn("c-5", picked("edge.bin", *ByteArray(10)), "edge.bin", maxBytes = 10)
        assertEquals(10L, exact.length())
    }

    @Test fun `by default a copy may be as big as one part PaLLi takes, and no bigger`() {
        val big = tmp.newFile("big.bin").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(SendLimits.MAX_PART_BYTES + 1) }
        }
        val error = runCatching { outgoing.copyIn("c-6", Uri.fromFile(big), "big.bin") }.exceptionOrNull()
        assertTrue(error is OutgoingFiles.TooLarge)
        assertEquals(0, outgoing.dirFor("c-6").listFiles()!!.size)
    }

    @Test fun `forgetting a send removes its folder and no other`() {
        outgoing.copyIn("c-3", picked("a.bin", 1), "a.jpg")
        outgoing.copyIn("c-4", picked("b.bin", 1), "b.jpg")
        outgoing.forget("c-3")
        assertFalse(File(context.filesDir, "outgoing/c-3").exists())
        assertTrue(File(context.filesDir, "outgoing/c-4").exists())
        outgoing.forgetAll()
        assertFalse(File(context.filesDir, "outgoing").exists())
    }
}
