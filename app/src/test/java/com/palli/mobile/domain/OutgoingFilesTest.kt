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
