package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DiskSpaceTest {
    @Test
    fun recognizesDiskFull_alsoAsCause() {
        assertTrue(DiskSpace.isDiskFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(DiskSpace.isDiskFull(RuntimeException("x", IOException("No space left on device"))))
        assertTrue(DiskSpace.isDiskFull(DiskFullException()))
        assertTrue(DiskSpace.isDiskFull(IllegalStateException("wrap", DiskFullException())))
        assertFalse(DiskSpace.isDiskFull(IOException("Connection reset")))
        assertFalse(DiskSpace.isDiskFull(null))
        assertEquals(DiskSpace.FULL_MESSAGE, DiskFullException().message)
    }

    @Test
    fun freeBytesAndFormat() {
        assertTrue(DiskSpace.freeBytes(File(System.getProperty("java.io.tmpdir"))) > 0)
        assertEquals(0L, DiskSpace.freeBytes(File("/gibt/es/nicht")))
        assertEquals("50 MB", DiskSpace.format(50_000_000L))
        assertEquals("1,5 GB", DiskSpace.format(1_500_000_000L))
    }
}
