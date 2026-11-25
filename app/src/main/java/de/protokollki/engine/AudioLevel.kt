package de.protokollki.engine

import de.protokollki.audio.Audio16k
import de.protokollki.audio.FloatAudio
import kotlin.math.sqrt

/**
 * Pegelangleich vor der Sprechererkennung. Sehr leise Aufnahmen (Handy weit weg) führten sonst
 * zu zu vielen erkannten Sprechern. Gemessen wird der Pegel der lauteren Hälfte der Abschnitte
 * (also der Sprache, nicht der Pausen); er wird auf etwa -20 dBFS gebracht, höchstens 100-fach
 * verstärkt, Spitzen werden begrenzt. Auf Testaufnahmen (2 bis 6 Sprecher, laut, leise, Lärm)
 * sank der Fehler der Sprecherzahl damit von 7 auf 6, bei leisen Aufnahmen von 5/3 auf 3/3.
 */
object AudioLevel {
    private const val FRAME = 400       // 25 ms bei 16 kHz
    private const val TARGET_RMS = 0.1f
    private const val MAX_GAIN = 100f

    fun gain(samples: FloatArray): Float = gain(FloatAudio(samples))

    /**
     * Wie [gain], liest das Audio aber abschnittsweise (für lange Aufnahmen direkt aus der Datei).
     * Zusatzspeicher: ein Float je 25-ms-Rahmen (2 h ≈ 1,2 MB).
     */
    fun gain(audio: Audio16k): Float {
        val frames = audio.size / FRAME
        if (frames == 0) return 1f
        val rms = FloatArray(frames)
        val framesPerBlock = 4096
        var f = 0
        while (f < frames) {
            val n = minOf(framesPerBlock, frames - f)
            val block = audio.floats(f * FRAME, (f + n) * FRAME)
            for (k in 0 until n) {
                var e = 0.0
                for (i in k * FRAME until (k + 1) * FRAME) e += block[i] * block[i]
                rms[f + k] = sqrt(e / FRAME).toFloat()
            }
            f += n
        }
        val median = rms.sortedArray()[frames / 2]
        var sum = 0.0
        var count = 0
        for (v in rms) if (v >= median) { sum += v; count++ }
        if (count == 0) return 1f
        val level = (sum / count).toFloat().coerceAtLeast(1e-5f)
        return (TARGET_RMS / level).coerceAtMost(MAX_GAIN)
    }

    /** Verstärkt [samples] an Ort und Stelle (kein zweites Array). Gibt [samples] zurück. */
    fun applyGain(samples: FloatArray, g: Float): FloatArray {
        if (g in 0.9f..1.1f) return samples
        for (i in samples.indices) samples[i] = (samples[i] * g).coerceIn(-1f, 1f)
        return samples
    }

    fun normalize(samples: FloatArray): FloatArray {
        val g = gain(samples)
        if (g in 0.9f..1.1f) return samples
        return FloatArray(samples.size) { (samples[it] * g).coerceIn(-1f, 1f) }
    }
}
