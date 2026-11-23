package de.protokollki.audio

import kotlin.math.log10
import kotlin.math.sqrt

/*
 * Reine Rechenlogik ohne android.*-Abhängigkeiten (per JVM-Unit-Test prüfbar).
 */

/**
 * Streaming-Resampler mit linearer Interpolation. Hält den Zustand über Pufferstrecken hinweg,
 * sodass beliebig große Eingaben stückweise verarbeitet werden können.
 *
 * Ausgabe-Sample k liegt exakt bei Eingabeposition k * srcRate / dstRate (Ganzzahlarithmetik,
 * kein Drift). Für N Eingabesamples entstehen genau [outputCount] Ausgabesamples.
 */
internal class LinearResampler(val srcRate: Int, val dstRate: Int) {
    init {
        require(srcRate > 0 && dstRate > 0) { "Ungültige Abtastrate" }
    }

    private var outIndex = 0L      // Index des nächsten Ausgabesamples
    private var consumed = 0L      // Anzahl Eingabesamples vor dem aktuellen Puffer
    private var prev = 0f          // letztes Eingabesample des vorigen Puffers

    /** Maximale Ausgabelänge für einen Eingabepuffer der Länge [n]. */
    fun maxOutput(n: Int): Int = ((n.toLong() * dstRate) / srcRate + 2).toInt()

    /**
     * Verarbeitet [n] Samples aus [input] und schreibt das Ergebnis nach [output]
     * (mindestens [maxOutput] groß). Gibt die Anzahl geschriebener Samples zurück.
     */
    fun process(input: FloatArray, n: Int, output: FloatArray): Int {
        if (n <= 0) return 0
        var written = 0
        if (srcRate == dstRate) {
            System.arraycopy(input, 0, output, 0, n)
            written = n
            outIndex += n
        } else {
            val last = consumed + n - 1          // globaler Index des letzten verfügbaren Samples
            val src = srcRate.toLong()
            val dst = dstRate.toLong()
            while (true) {
                val num = outIndex * src
                val idx = num / dst
                val rem = num % dst
                val needed = if (rem == 0L) idx else idx + 1
                if (needed > last) break
                val s0 = sampleAt(input, idx)
                val v = if (rem == 0L) s0 else {
                    val s1 = sampleAt(input, idx + 1)
                    s0 + (s1 - s0) * (rem.toFloat() / dst.toFloat())
                }
                if (written >= output.size) break
                output[written++] = v
                outIndex++
            }
        }
        prev = input[n - 1]
        consumed += n
        return written
    }

    private fun sampleAt(input: FloatArray, globalIndex: Long): Float {
        val local = globalIndex - consumed
        return if (local < 0) prev else input[local.toInt()]
    }

    companion object {
        /** Exakte Anzahl Ausgabesamples für [nIn] Eingabesamples. */
        fun outputCount(nIn: Long, srcRate: Int, dstRate: Int): Long =
            if (nIn <= 0) 0 else ((nIn - 1) * dstRate) / srcRate + 1
    }
}

/** RMS eines PCM16-Puffers → Pegel 0..1 auf logarithmischer Skala (-60 dBFS … 0 dBFS). */
internal fun levelFromPcm16(buf: ShortArray, n: Int): Float {
    if (n <= 0) return 0f
    var sum = 0.0
    for (i in 0 until n) {
        val s = buf[i].toDouble()
        sum += s * s
    }
    val rms = sqrt(sum / n) / 32768.0
    if (rms <= 1e-6) return 0f
    val db = 20.0 * log10(rms)
    return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}

/** Float (-1..1) → PCM16 mit Begrenzung. */
internal fun floatToPcm16(v: Float): Short {
    val s = (v * 32767f).toInt()
    return s.coerceIn(-32768, 32767).toShort()
}

/** Formatiert Millisekunden als HH:mm:ss (für die Aufnahme-Benachrichtigung). */
internal fun formatHms(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s)
}
