package de.protokollki.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/*
 * WAV lesen/schreiben – bewusst ohne android.*, damit per JVM-Unit-Test prüfbar.
 */

/**
 * Schreibt 16 kHz / mono / PCM16 WAV. Der Header wird zunächst mit Größe 0 geschrieben und
 * beim [close] korrigiert; zusätzlich werden Daten regelmäßig auf die Platte geschrieben und
 * der Header etwa alle 10 s aktualisiert, sodass nach einem Absturz höchstens wenige Sekunden
 * fehlen ([WavIO.repairHeader] korrigiert den Rest).
 */
class WavWriter(private val file: File) : Closeable {
    private val out: BufferedOutputStream
    private var bytes = ByteArray(0)
    private var dataBytes = 0L
    private var bytesSinceFlush = 0L
    private var bytesSinceHeader = 0L
    private var closed = false

    /** Anzahl bisher geschriebener Samples. */
    val samplesWritten: Long get() = dataBytes / 2

    val durationSec: Float get() = samplesWritten / WavIO.SAMPLE_RATE.toFloat()

    init {
        file.parentFile?.mkdirs()
        out = BufferedOutputStream(FileOutputStream(file, false), 64 * 1024)
        out.write(WavIO.header(0L, WavIO.SAMPLE_RATE, 1, 16))
    }

    @Synchronized
    fun write(samples: ShortArray, n: Int) {
        if (closed || n <= 0) return
        val count = n.coerceAtMost(samples.size)
        val need = count * 2
        if (bytes.size < need) bytes = ByteArray(need)
        var j = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(bytes, 0, need)
        dataBytes += need
        bytesSinceFlush += need
        bytesSinceHeader += need
        if (bytesSinceFlush >= FLUSH_BYTES) flush()
        if (bytesSinceHeader >= HEADER_BYTES) {
            bytesSinceHeader = 0
            runCatching { out.flush(); patchSizes() }
        }
    }

    @Synchronized
    fun flush() {
        if (closed) return
        out.flush()
        bytesSinceFlush = 0
    }

    @Synchronized
    override fun close() {
        if (closed) return
        try {
            out.flush()
        } finally {
            closed = true
            out.close()
        }
        patchSizes()
    }

    private fun patchSizes() {
        RandomAccessFile(file, "rw").use { raf ->
            WavIO.writeSizes(raf, 44L, dataBytes)
        }
    }

    private companion object {
        const val FLUSH_BYTES = 32_000L          // ~1 s
        const val HEADER_BYTES = 320_000L        // ~10 s
    }
}

object WavIO {
    const val SAMPLE_RATE = 16_000

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    internal class Info(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    ) {
        val blockAlign: Int get() = channels * ((bitsPerSample + 7) / 8)
        val frames: Long get() = if (blockAlign > 0) dataSize / blockAlign else 0
    }

    /**
     * Liest eine WAV-Datei vollständig als 16 kHz mono Float (-1..1). Unterstützt PCM 8/16/24/32 bit
     * und Float32, beliebig viele Kanäle (Downmix) und beliebige Abtastraten (lineare Interpolation).
     *
     * Speicherbedarf: 4 Byte pro Ausgabesample, d. h. ~230 MB je Stunde (2 h ≈ 460 MB, nur mit
     * largeHeap tragbar). Gelesen wird blockweise (64 KB), es entsteht kein zweites Vollpuffer-Array.
     */
    fun readMono16k(path: String): FloatArray {
        val file = File(path)
        val info = readInfo(file)
        if (info.sampleRate <= 0 || info.channels <= 0) throw IOException("Ungültiger WAV-Header")
        val bytesPerSample = (info.bitsPerSample + 7) / 8
        val isFloat = info.format == FORMAT_FLOAT
        if (isFloat && info.bitsPerSample != 32) throw IOException("Nicht unterstütztes WAV-Format")
        if (bytesPerSample !in 1..4) throw IOException("Nicht unterstütztes WAV-Format")
        val frames = info.frames
        val outCount = LinearResampler.outputCount(frames, info.sampleRate, SAMPLE_RATE)
        if (outCount > Int.MAX_VALUE - 16) throw IOException("Audiodatei ist zu lang")
        val result = FloatArray(outCount.toInt())
        if (frames == 0L) return result

        val resampler = LinearResampler(info.sampleRate, SAMPLE_RATE)
        val block = info.blockAlign
        val framesPerChunk = 16_384
        val raw = ByteArray(framesPerChunk * block)
        val mono = FloatArray(framesPerChunk)
        val resampled = FloatArray(resampler.maxOutput(framesPerChunk))
        var outPos = 0
        var framesLeft = frames

        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            skipFully(input, info.dataOffset)
            while (framesLeft > 0) {
                val want = minOf(framesPerChunk.toLong(), framesLeft).toInt()
                val got = readUpTo(input, raw, want * block) / block
                if (got <= 0) break
                decodeFrames(raw, got, info.channels, bytesPerSample, isFloat, mono)
                val n = resampler.process(mono, got, resampled)
                val copy = minOf(n, result.size - outPos)
                if (copy > 0) {
                    System.arraycopy(resampled, 0, result, outPos, copy)
                    outPos += copy
                }
                framesLeft -= got
            }
        }
        // Bei vorzeitigem Dateiende bleibt der Rest still (0f) – Länge stimmt mit durationSec überein.
        return result
    }

    /** Dauer in Sekunden laut Datei (bei kaputtem Header aus der Dateigröße). 0 bei Fehler. */
    fun durationSec(path: String): Float = try {
        val info = readInfo(File(path))
        if (info.sampleRate > 0) info.frames.toFloat() / info.sampleRate else 0f
    } catch (e: Exception) {
        0f
    }

    /**
     * Korrigiert RIFF- und data-Größe anhand der tatsächlichen Dateigröße
     * (z. B. nach Absturz während der Aufnahme). Fehler werden ignoriert.
     */
    fun repairHeader(file: File) {
        try {
            val len = file.length()
            if (len < 44) return
            val dataOffset = try {
                findDataOffset(file)
            } catch (e: Exception) {
                -1L
            }
            RandomAccessFile(file, "rw").use { raf ->
                if (dataOffset < 0) {
                    // Kein lesbarer Header → Standard-Header (16 kHz mono PCM16) annehmen
                    raf.seek(0)
                    raf.write(header(len - 44, SAMPLE_RATE, 1, 16))
                } else {
                    var dataSize = len - dataOffset
                    if (dataSize % 2L != 0L) dataSize -= 1 // nur ganze Samples
                    writeSizes(raf, dataOffset, dataSize)
                }
            }
        } catch (e: Exception) {
            // bewusst ignoriert
        }
    }

    // ------------------------------------------------------------------ intern

    internal fun header(dataSize: Long, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val h = ByteArray(44)
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        putAscii(h, 0, "RIFF")
        putInt32(h, 4, clampU32(36 + dataSize))
        putAscii(h, 8, "WAVE")
        putAscii(h, 12, "fmt ")
        putInt32(h, 16, 16)
        putInt16(h, 20, FORMAT_PCM)
        putInt16(h, 22, channels)
        putInt32(h, 24, sampleRate.toLong())
        putInt32(h, 28, byteRate.toLong())
        putInt16(h, 32, blockAlign)
        putInt16(h, 34, bits)
        putAscii(h, 36, "data")
        putInt32(h, 40, clampU32(dataSize))
        return h
    }

    /** Schreibt RIFF-Größe (Offset 4) und data-Größe (dataOffset - 4). */
    internal fun writeSizes(raf: RandomAccessFile, dataOffset: Long, dataSize: Long) {
        val b = ByteArray(4)
        putInt32(b, 0, clampU32(dataOffset - 8 + dataSize))
        raf.seek(4); raf.write(b)
        putInt32(b, 0, clampU32(dataSize))
        raf.seek(dataOffset - 4); raf.write(b)
    }

    internal fun readInfo(file: File): Info {
        return RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 12) throw IOException("Datei zu kurz")
            val head = ByteArray(12)
            raf.readFully(head)
            if (ascii(head, 0) != "RIFF" || ascii(head, 8) != "WAVE") {
                throw IOException("Keine gültige WAV-Datei")
            }
            var format = FORMAT_PCM
            var channels = 1
            var rate = SAMPLE_RATE
            var bits = 16
            var subFormatFloat = false
            var pos = 12L
            val ch = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(ch)
                val id = ascii(ch, 0)
                val size = u32(ch, 4)
                val body = pos + 8
                if (id == "data") {
                    val remaining = len - body
                    val dataSize = if (size == 0L || size > remaining) remaining else size
                    if (format == FORMAT_EXTENSIBLE) format = if (subFormatFloat) FORMAT_FLOAT else FORMAT_PCM
                    return Info(format, channels, rate, bits, body, dataSize)
                }
                if (id == "fmt " && size >= 16) {
                    val f = ByteArray(minOf(size, 40L).toInt())
                    raf.readFully(f)
                    format = u16(f, 0)
                    channels = u16(f, 2)
                    rate = u32(f, 4).toInt()
                    bits = u16(f, 14)
                    if (format == FORMAT_EXTENSIBLE && f.size >= 26) {
                        // SubFormat-GUID beginnt bei Offset 24; die ersten 2 Byte = Formatcode
                        subFormatFloat = u16(f, 24) == FORMAT_FLOAT
                    }
                }
                if (size < 0 || size > len) break
                pos = body + size + (size and 1L)
            }
            throw IOException("Keine Audiodaten in der WAV-Datei gefunden")
        }
    }

    private fun findDataOffset(file: File): Long {
        return RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val head = ByteArray(12)
            raf.readFully(head)
            if (ascii(head, 0) != "RIFF" || ascii(head, 8) != "WAVE") return -1
            var pos = 12L
            val ch = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(ch)
                val size = u32(ch, 4)
                if (ascii(ch, 0) == "data") return pos + 8
                if (size > len) return -1
                pos += 8 + size + (size and 1L)
            }
            return -1
        }
    }

    private fun decodeFrames(
        raw: ByteArray, frames: Int, channels: Int, bps: Int, isFloat: Boolean, out: FloatArray,
    ) {
        var p = 0
        val inv = 1f / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val v: Float = when (bps) {
                    1 -> ((raw[p].toInt() and 0xFF) - 128) / 128f
                    2 -> ((raw[p].toInt() and 0xFF) or (raw[p + 1].toInt() shl 8)).toShort() / 32768f
                    3 -> {
                        val i = (raw[p].toInt() and 0xFF) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            (raw[p + 2].toInt() shl 16)
                        i / 8388608f
                    }
                    else -> {
                        val i = (raw[p].toInt() and 0xFF) or
                            ((raw[p + 1].toInt() and 0xFF) shl 8) or
                            ((raw[p + 2].toInt() and 0xFF) shl 16) or
                            (raw[p + 3].toInt() shl 24)
                        if (isFloat) java.lang.Float.intBitsToFloat(i) else i / 2147483648f
                    }
                }
                sum += v
                p += bps
            }
            out[f] = sum * inv
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException()
                left -= 1
            } else {
                left -= s
            }
        }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = input.read(buf, total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun clampU32(v: Long): Long = v.coerceIn(0L, 0xFFFFFFFFL)

    private fun putAscii(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun putInt16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putInt32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun ascii(b: ByteArray, off: Int): String =
        String(CharArray(4) { (b[off + it].toInt() and 0xFF).toChar() })

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
}
