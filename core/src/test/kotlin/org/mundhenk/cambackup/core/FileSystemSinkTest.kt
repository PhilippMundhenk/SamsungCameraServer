package org.mundhenk.cambackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A camera upload that stops early (user cancels on the camera) must not leave a "complete" file behind. */
class FileSystemSinkTest {
    @Test
    fun shortUploadsAreRejectedAndNotKept() {
        val root = Files.createTempDirectory("cambackup-fss").toFile()
        try {
            val sink = FileSystemSink(root)
            val file = IncomingFile("SAM_8808.JPG", "2021-06-07", 100, "image/jpeg", "object.item.imageItem", "cam")
            try { sink.store(file, ByteArrayInputStream(ByteArray(30))); fail("expected IOException") }
            catch (e: IOException) { assertEquals("short upload of SAM_8808.JPG: 30 of 100 bytes", e.message) }
            val target = File(File(root, "2021-06-07"), "SAM_8808.JPG")
            assertFalse(target.exists()); assertFalse(File(target.path + ".part").exists())
            assertFalse(sink.exists(file))
            // the full thing is fine; unknown size (-1) is taken as is
            assertEquals(StoreResult.STORED, sink.store(file, ByteArrayInputStream(ByteArray(100))))
            assertTrue(target.isFile && target.length() == 100L)
            assertEquals(StoreResult.STORED, sink.store(file.copy(name = "X.JPG", size = -1), ByteArrayInputStream(ByteArray(7))))
            assertEquals(7L, File(File(root, "2021-06-07"), "X.JPG").length())
        } finally { root.deleteRecursively() }
    }
}
