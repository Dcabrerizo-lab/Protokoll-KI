package de.protokollki.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.UUID

/*
 * Audio für die Verarbeitung – bewusst ohne android.*, damit per JVM-Unit-Test prüfbar.
 */

/**
 * 16 kHz / mono Audio, das abschnittsweise gelesen wird. Lange Aufnahmen (2–3 h = 115–172 Mio.
 * Samples) passen als FloatArray nicht in den Java-Heap (3 h ≈ 690 MB); deshalb holen sich
 * Sprechererkennung und Spracherkennung immer nur den Teil, den sie gerade brauchen.
 */
interface Audio16k {
    /** Anzahl Samples. */
    val size: Int

    /** Samples [from, to) als Float -1..1 (neues Array der Länge to - from). */
    fun floats(from: Int, to: Int): FloatArray

    val durationSec: Float get() = size / WavIO.SAMPLE_RATE.toFloat()
}

/** Audio, das bereits vollständig im Speicher liegt (kurze Aufnahmen, Tests). */
class FloatAudio(val samples: FloatArray) : Audio16k {
    override val size: Int get() = samples.size
    override fun floats(from: Int, to: Int): FloatArray = samples.copyOfRange(from, to)
}

/**
 * Liest 16 kHz / mono / PCM16 direkt aus der WAV-Datei über ein Memory-Mapping: der Java-Heap
 * bleibt unberührt, das System lädt nur die gerade gelesenen Seiten. Andere WAV-Formate werden
 * vorher blockweise in eine temporäre Datei umgerechnet (wird bei [close] gelöscht).
 */
class MappedPcm16Audio private constructor(
    private val buf: ShortBuffer,
    override val size: Int,
    private val tempFile: File?,
) : Audio16k, Closeable {

    override fun floats(from: Int, to: Int): FloatArray {
        require(from in 0..to && to <= size) { "Bereich $from..$to außerhalb 0..$size" }
        val n = to - from
        val out = FloatArray(n)
        if (n == 0) return out
        val view = buf.duplicate()
        view.position(from)
        val tmp = ShortArray(minOf(n, 65_536))
        var i = 0
        while (i < n) {
            val k = minOf(tmp.size, n - i)
            view.get(tmp, 0, k)
            for (j in 0 until k) out[i + j] = tmp[j] / 32768f
            i += k
        }
        return out
    }

    override fun close() {
        tempFile?.delete()
    }

    companion object {
        /**
         * Öffnet [path]. Ist die Datei nicht 16 kHz / mono / PCM16, wird sie in [scratchDir]
         * umgerechnet. Wirft [IOException] bei unlesbaren Dateien.
         */
        fun open(path: String, scratchDir: File): MappedPcm16Audio {
            val src = File(path)
            if (WavIO.isPcm16Mono16k(path)) return map(src, null)
            scratchDir.mkdirs()
            val tmp = File(scratchDir, "audio16k-${UUID.randomUUID()}.wav")
            try {
                WavIO.convertToPcm16Mono16k(src, tmp)
                return map(tmp, tmp)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }

        private fun map(file: File, temp: File?): MappedPcm16Audio {
            val info = WavIO.checkedInfo(file)
            val samples = info.dataSize / 2
            if (samples * 2 > Int.MAX_VALUE) throw IOException("Audiodatei ist zu lang")
            val bytes = samples * 2
            val shorts: ShortBuffer = RandomAccessFile(file, "r").use { raf ->
                if (bytes == 0L) ShortBuffer.allocate(0)
                else raf.channel.map(FileChannel.MapMode.READ_ONLY, info.dataOffset, bytes)
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            }
            return MappedPcm16Audio(shorts, samples.toInt(), temp)
        }
    }
}
