package de.protokollki.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class AudioMathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resampleChunked(input: FloatArray, src: Int, dst: Int, chunk: Int): FloatArray {
        val r = LinearResampler(src, dst)
        val out = ArrayList<Float>()
        var pos = 0
        while (pos < input.size) {
            val n = minOf(chunk, input.size - pos)
            val part = input.copyOfRange(pos, pos + n)
            val buf = FloatArray(r.maxOutput(n))
            val got = r.process(part, n, buf)
            for (i in 0 until got) out += buf[i]
            pos += n
        }
        return out.toFloatArray()
    }

    @Test
    fun resamplerCountsAndChunkingAreConsistent() {
        val input = FloatArray(10_007) { (it % 100) / 100f }
        for (src in intArrayOf(8000, 16000, 22050, 44100, 48000)) {
            val whole = resampleChunked(input, src, 16000, input.size)
            val chunked = resampleChunked(input, src, 16000, 333)
            assertEquals(LinearResampler.outputCount(input.size.toLong(), src, 16000), whole.size.toLong())
            assertArrayEquals(whole, chunked, 1e-6f)
        }
    }

    @Test
    fun resamplerInterpolatesLinearly() {
        // Rampe 0,1,2,... bei 48 kHz → jedes 3. Sample bei 16 kHz
        val input = FloatArray(30) { it.toFloat() }
        val out = resampleChunked(input, 48000, 16000, 7)
        assertEquals(10, out.size)
        for (i in out.indices) assertEquals(i * 3f, out[i], 1e-4f)
        // 8 kHz → 16 kHz: Zwischenwerte
        val up = resampleChunked(floatArrayOf(0f, 2f, 4f), 8000, 16000, 1)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f, 4f), up, 1e-6f)
    }

    @Test
    fun levelAndFormatting() {
        assertEquals(0f, levelFromPcm16(ShortArray(1600), 1600), 0f)
        val loud = ShortArray(1600) { (if (it % 2 == 0) 32767 else -32767).toShort() }
        assertTrue(levelFromPcm16(loud, loud.size) > 0.99f)
        assertEquals("00:12:34", formatHms(754_000L))
        assertEquals("01:00:00", formatHms(3_600_999L))
        assertEquals(32767.toShort(), floatToPcm16(2f))
        assertEquals((-32768).toShort(), floatToPcm16(-2f))
    }

    @Test
    fun wavRoundTripAndRepair() {
        val f = File(tmp.root, "a.wav")
        val samples = ShortArray(16000 * 3) { ((it % 200) * 100 - 10000).toShort() }
        WavWriter(f).use { w ->
            w.write(samples, 10000)
            w.write(samples.copyOfRange(10000, samples.size), samples.size - 10000)
        }
        assertEquals(44L + samples.size * 2, f.length())
        assertEquals(3f, WavIO.durationSec(f.path), 1e-4f)
        val read = WavIO.readMono16k(f.path)
        assertEquals(samples.size, read.size)
        for (i in samples.indices) assertEquals(samples[i] / 32768f, read[i], 1e-6f)

        // Größen im Header zerstören (wie nach Absturz) und reparieren
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(4); raf.write(ByteArray(4))
            raf.seek(40); raf.write(ByteArray(4))
        }
        WavIO.repairHeader(f)
        RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(4)
            raf.seek(40); raf.readFully(b)
            val size = (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            assertEquals(samples.size * 2L, size)
        }
        assertEquals(3f, WavIO.durationSec(f.path), 1e-4f)
    }

    @Test
    fun readsStereo48kAndResamples() {
        val f = File(tmp.root, "s.wav")
        val frames = 48000
        val header = WavIO.header(frames * 4L, 48000, 2, 16)
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = 8000
            val r = -4000
            data[i * 4] = (l and 0xFF).toByte(); data[i * 4 + 1] = (l shr 8).toByte()
            data[i * 4 + 2] = (r and 0xFF).toByte(); data[i * 4 + 3] = (r shr 8).toByte()
        }
        f.writeBytes(header + data)
        val read = WavIO.readMono16k(f.path)
        assertEquals(LinearResampler.outputCount(frames.toLong(), 48000, 16000).toInt(), read.size)
        assertEquals(2000f / 32768f, read[read.size / 2], 1e-5f)
        assertEquals(1f, WavIO.durationSec(f.path), 1e-4f)
    }
}
