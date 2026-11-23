package de.protokollki.engine

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

    fun gain(samples: FloatArray): Float {
        val frames = samples.size / FRAME
        if (frames == 0) return 1f
        val rms = FloatArray(frames) { f ->
            var e = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) e += samples[i] * samples[i]
            sqrt(e / FRAME).toFloat()
        }
        val median = rms.sortedArray()[frames / 2]
        val loud = rms.filter { it >= median }
        if (loud.isEmpty()) return 1f
        val level = loud.average().toFloat().coerceAtLeast(1e-5f)
        return (TARGET_RMS / level).coerceAtMost(MAX_GAIN)
    }

    fun normalize(samples: FloatArray): FloatArray {
        val g = gain(samples)
        if (g in 0.9f..1.1f) return samples
        return FloatArray(samples.size) { (samples[it] * g).coerceIn(-1f, 1f) }
    }
}
