package de.protokollki.ui.components

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000

@SuppressLint("MissingPermission")
private fun openRecord(source: Int, bufBytes: Int): AudioRecord? = try {
    val r = AudioRecord(
        source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
    )
    if (r.state == AudioRecord.STATE_INITIALIZED) r else {
        r.release(); null
    }
} catch (e: Exception) {
    null
}

/**
 * Nimmt eine kurze Stimmprobe auf (16 kHz mono, Werte −1..1) – für „Stimme einlernen“.
 * Endet nach [maxMs], wenn [stopRequested] true liefert oder die Coroutine abgebrochen wird.
 * [onProgress] wird auf dem Main-Thread mit Pegel (0..1) und verstrichener Zeit aufgerufen.
 * Die RECORD_AUDIO-Berechtigung muss vorher erteilt sein.
 */
suspend fun recordVoiceSample(
    maxMs: Long,
    stopRequested: () -> Boolean,
    onProgress: (level: Float, elapsedMs: Long) -> Unit,
): FloatArray = withContext(Dispatchers.IO) {
    val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuf <= 0) throw IllegalStateException("Das Mikrofon ist nicht verfügbar.")
    val bufBytes = maxOf(minBuf, SAMPLE_RATE / 2 * 2)   // mind. 0,5 s Puffer
    val rec = openRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufBytes)
        ?: openRecord(MediaRecorder.AudioSource.MIC, bufBytes)
        ?: throw IllegalStateException("Das Mikrofon konnte nicht geöffnet werden.")
    val maxSamples = (SAMPLE_RATE * maxMs / 1000).toInt()
    val out = FloatArray(maxSamples)
    val chunk = ShortArray(SAMPLE_RATE / 10)   // 100 ms
    var n = 0
    try {
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Das Mikrofon wird gerade von einer anderen App verwendet.")
        }
        while (n < maxSamples && isActive && !stopRequested()) {
            val want = minOf(chunk.size, maxSamples - n)
            val r = rec.read(chunk, 0, want)
            if (r < 0) throw IllegalStateException("Fehler beim Lesen des Mikrofons.")
            if (r == 0) continue
            var sum = 0.0
            for (i in 0 until r) {
                val v = chunk[i] / 32768f
                out[n + i] = v
                sum += (v * v).toDouble()
            }
            n += r
            val rms = sqrt(sum / r)
            val db = 20.0 * log10(rms + 1e-9)
            val level = ((db + 55.0) / 50.0).toFloat().coerceIn(0f, 1f)
            val elapsed = n * 1000L / SAMPLE_RATE
            withContext(Dispatchers.Main) { onProgress(level, elapsed) }
        }
    } finally {
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
    out.copyOf(n)
}
