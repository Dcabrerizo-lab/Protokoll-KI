package de.protokollki.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.UUID

/** Fehler beim Import mit deutscher, anzeigbarer Meldung. */
class AudioImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val TAG = "AudioImporter"

/**
 * Importiert eine beliebige Audio- oder Videodatei: erste Tonspur dekodieren, auf mono
 * heruntermischen, auf 16 kHz umrechnen und als WAV in einer neuen Besprechung ablegen.
 * Die Besprechung wird erst angelegt, wenn die Dekodierung erfolgreich war.
 *
 * @throws AudioImportException mit deutscher Fehlermeldung
 */
suspend fun importAudio(context: Context, uri: Uri): Meeting = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val displayName = queryDisplayName(appContext, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Import"
    val title = displayName.substringBeforeLast('.', displayName).trim()
        .ifBlank { "Importierte Aufnahme" }

    val tmp = File(appContext.cacheDir, "import-${UUID.randomUUID()}.wav")
    var meetingId: String? = null
    try {
        val samples = decodeToWav(appContext, uri, tmp)
        if (samples <= 0L) throw AudioImportException("Die Datei enthält keinen verwertbaren Ton.")
        val duration = samples / WavIO.SAMPLE_RATE.toFloat()

        val created = MeetingRepository.create(title, MeetingStatus.RECORDED)
        meetingId = created.id
        val target = File(created.audioFile)
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        val mode = AppSettings.state.value.defaultMode
        MeetingRepository.update(created.id) {
            it.copy(status = MeetingStatus.RECORDED, durationSec = duration, mode = mode)
        } ?: throw AudioImportException("Die Besprechung konnte nicht gespeichert werden.")
    } catch (e: CancellationException) {
        tmp.delete()
        meetingId?.let { MeetingRepository.delete(it) }
        throw e
    } catch (e: AudioImportException) {
        tmp.delete()
        meetingId?.let { MeetingRepository.delete(it) }
        throw e
    } catch (e: Throwable) {
        Log.e(TAG, "Import fehlgeschlagen", e)
        tmp.delete()
        meetingId?.let { MeetingRepository.delete(it) }
        val msg = if (e is OutOfMemoryError) "Nicht genügend Arbeitsspeicher für den Import."
        else "Die Datei konnte nicht gelesen werden. Bitte eine gängige Audio- oder Videodatei wählen."
        throw AudioImportException(msg, e)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
        ?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** Dekodiert die erste Tonspur nach [out] (16 kHz mono PCM16). Gibt die Anzahl Samples zurück. */
private suspend fun decodeToWav(context: Context, uri: Uri, out: File): Long {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var writer: WavWriter? = null
    try {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw AudioImportException("Die Datei konnte nicht geöffnet werden.", e)
        }
        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            throw AudioImportException("Die Datei enthält keine Tonspur.")
        }
        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw AudioImportException("Unbekanntes Audioformat.")

        val dec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw AudioImportException("Dieses Audioformat wird vom Gerät nicht unterstützt ($mime).", e)
        }
        codec = dec
        dec.configure(inputFormat, null, null, 0)
        dec.start()

        var channels = intOr(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
        var sampleRate = intOr(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 44_100).coerceAtLeast(1)
        var encoding = intOr(inputFormat, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        val w = WavWriter(out)
        writer = w
        var resampler: LinearResampler? = null
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var mono = FloatArray(0)
        var resampled = FloatArray(0)
        var pcmOut = ShortArray(0)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var idleAfterInput = 0
        var loops = 0

        while (!outputDone) {
            if (++loops % 64 == 0) currentCoroutineContext().ensureActive()

            if (!inputDone) {
                val inIdx = dec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = dec.getInputBuffer(inIdx)
                    val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                    if (size < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val time = extractor.sampleTime.coerceAtLeast(0L)
                        dec.queueInputBuffer(inIdx, 0, size, time, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx >= 0 -> {
                    idleAfterInput = 0
                    if (info.size > 0) {
                        val buf = dec.getOutputBuffer(outIdx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val ord = buf.slice().order(ByteOrder.nativeOrder())

                            // 1) in Mono-Float umwandeln
                            val frames: Int
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = ord.asFloatBuffer()
                                val n = fb.remaining()
                                if (floats.size < n) floats = FloatArray(n)
                                fb.get(floats, 0, n)
                                frames = n / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                downmixFloat(floats, frames, channels, mono)
                            } else if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                val sb = ord.asShortBuffer()
                                val n = sb.remaining()
                                if (shorts.size < n) shorts = ShortArray(n)
                                sb.get(shorts, 0, n)
                                frames = n / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                downmixPcm16(shorts, frames, channels, mono)
                            } else {
                                throw AudioImportException("Nicht unterstütztes Ausgabeformat des Decoders.")
                            }

                            // 2) auf 16 kHz umrechnen und schreiben
                            if (frames > 0) {
                                val rs = resampler?.takeIf { it.srcRate == sampleRate }
                                    ?: LinearResampler(sampleRate, WavIO.SAMPLE_RATE).also { resampler = it }
                                val maxOut = rs.maxOutput(frames)
                                if (resampled.size < maxOut) resampled = FloatArray(maxOut)
                                val n = rs.process(mono, frames, resampled)
                                if (pcmOut.size < n) pcmOut = ShortArray(n)
                                for (i in 0 until n) pcmOut[i] = floatToPcm16(resampled[i])
                                w.write(pcmOut, n)
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = dec.outputFormat
                    channels = intOr(f, MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    sampleRate = intOr(f, MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceAtLeast(1)
                    encoding = intOr(f, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                else -> {
                    // INFO_TRY_AGAIN_LATER (bzw. veraltetes OUTPUT_BUFFERS_CHANGED)
                    if (inputDone && ++idleAfterInput > 500) {
                        Log.w(TAG, "Decoder liefert kein Dateiende – Abbruch nach Wartezeit")
                        outputDone = true
                    }
                }
            }
        }

        w.close()
        return w.samplesWritten
    } finally {
        runCatching { writer?.close() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun intOr(f: MediaFormat, key: String, def: Int): Int =
    try {
        if (f.containsKey(key)) f.getInteger(key) else def
    } catch (e: Exception) {
        def
    }

private fun downmixPcm16(src: ShortArray, frames: Int, channels: Int, out: FloatArray) {
    if (channels == 1) {
        for (i in 0 until frames) out[i] = src[i] / 32768f
        return
    }
    val scale = 1f / (32768f * channels)
    var p = 0
    for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += src[p++]
        out[i] = sum * scale
    }
}

private fun downmixFloat(src: FloatArray, frames: Int, channels: Int, out: FloatArray) {
    if (channels == 1) {
        System.arraycopy(src, 0, out, 0, frames)
        return
    }
    val scale = 1f / channels
    var p = 0
    for (i in 0 until frames) {
        var sum = 0f
        for (c in 0 until channels) sum += src[p++]
        out[i] = sum * scale
    }
}
