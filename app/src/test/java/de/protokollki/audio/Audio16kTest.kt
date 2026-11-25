package de.protokollki.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Abschnittsweises Lesen langer Aufnahmen ohne die ganze Datei im Speicher. */
class Audio16kTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pcm16File(name: String, samples: ShortArray): File {
        val f = File(tmp.root, name)
        WavWriter(f).use { it.write(samples, samples.size) }
        return f
    }

    private fun ramp(n: Int) = ShortArray(n) { ((it * 37) % 65536 - 32768).toShort() }

    @Test
    fun mappedEqualsReadMono16k_forPcm16Mono16k() {
        val f = pcm16File("a.wav", ramp(16000 * 5 + 123))
        val whole = WavIO.readMono16k(f.path)
        val scratch = File(tmp.root, "scratch")
        MappedPcm16Audio.open(f.path, scratch).use { a ->
            assertEquals(whole.size, a.size)
            assertEquals(5f, a.durationSec, 0.01f)
            assertArrayEquals(whole, a.floats(0, a.size), 0f)
            assertArrayEquals(whole.copyOfRange(70_001, 70_100), a.floats(70_001, 70_100), 0f)
            assertEquals(0, a.floats(10, 10).size)
            // kein Umrechnen nötig: keine temporäre Datei
            assertTrue(scratch.listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun otherFormatsAreConvertedToTempFile_andDeletedOnClose() {
        // 48 kHz Stereo, 16 bit
        val f = File(tmp.root, "s.wav")
        val frames = 48000 * 2
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = 8000; val r = -4000
            data[i * 4] = (l and 0xFF).toByte(); data[i * 4 + 1] = (l shr 8).toByte()
            data[i * 4 + 2] = (r and 0xFF).toByte(); data[i * 4 + 3] = (r shr 8).toByte()
        }
        f.writeBytes(WavIO.header(frames * 4L, 48000, 2, 16) + data)
        val reference = WavIO.readMono16k(f.path)
        val scratch = File(tmp.root, "scratch")
        val a = MappedPcm16Audio.open(f.path, scratch)
        assertEquals(reference.size, a.size)
        val mid = a.floats(a.size / 2, a.size / 2 + 1)[0]
        assertEquals(reference[reference.size / 2], mid, 2f / 32768f)
        assertEquals(1, scratch.listFiles()!!.size)
        a.close()
        assertTrue("temporäre Datei nicht gelöscht", scratch.listFiles().isNullOrEmpty())
    }

    @Test
    fun truncatedHeaderAfterCrash_usesFileLength() {
        val f = pcm16File("c.wav", ramp(32000))
        RandomAccessFile(f, "rw").use { raf -> raf.seek(40); raf.write(ByteArray(4)) }   // data-Größe 0
        MappedPcm16Audio.open(f.path, tmp.root).use { assertEquals(32000, it.size) }
    }

    @Test
    fun emptyAndInvalidFiles() {
        val empty = pcm16File("e.wav", ShortArray(0))
        MappedPcm16Audio.open(empty.path, tmp.root).use { assertEquals(0, it.size) }
        val junk = File(tmp.root, "j.wav").apply { writeText("kein wav") }
        try {
            MappedPcm16Audio.open(junk.path, tmp.root)
            throw AssertionError("Ausnahme erwartet")
        } catch (e: IOException) {
            // erwartet
        }
    }

    @Test
    fun convertStreams_withoutLoadingWholeFile() {
        // 24-bit mono 16 kHz → PCM16: gleiche Länge, gleiche Werte (bis auf Rundung)
        val f = File(tmp.root, "p24.wav")
        val n = 16000
        val data = ByteArray(n * 3)
        for (i in 0 until n) {
            val v = (i % 1000) * 4000 - 2_000_000
            data[i * 3] = (v and 0xFF).toByte(); data[i * 3 + 1] = (v shr 8 and 0xFF).toByte(); data[i * 3 + 2] = (v shr 16).toByte()
        }
        f.writeBytes(WavIO.header(n * 3L, 16000, 1, 24) + data)
        assertFalse(WavIO.isPcm16Mono16k(f.path))
        val out = File(tmp.root, "out.wav")
        assertEquals(n.toLong(), WavIO.convertToPcm16Mono16k(f, out))
        assertTrue(WavIO.isPcm16Mono16k(out.path))
        val a = WavIO.readMono16k(f.path)
        val b = WavIO.readMono16k(out.path)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 2f / 32768f)
    }

    @Test
    fun floatAudioCopies() {
        val s = floatArrayOf(0.1f, 0.2f, 0.3f)
        val a = FloatAudio(s)
        val part = a.floats(1, 3)
        part[0] = 9f
        assertEquals(0.2f, s[1], 0f)
    }

    @Test
    fun writerCloseKeepsHeaderConsistentWithDisk() {
        val f = File(tmp.root, "w.wav")
        val w = WavWriter(f)
        w.write(ramp(48000), 48000)
        w.close()
        w.close()   // doppelt schließen ist harmlos
        assertEquals(48000L, w.samplesWritten)
        assertEquals(3f, WavIO.durationSec(f.path), 1e-4f)
    }
}
