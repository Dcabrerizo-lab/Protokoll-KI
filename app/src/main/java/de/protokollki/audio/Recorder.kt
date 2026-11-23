package de.protokollki.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.service.ProcessingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecState(
    val meetingId: String? = null,
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val levels: List<Float> = emptyList(), // letzte ~120 Pegel 0..1
)

/**
 * Steuert die Aufnahme. Ablauf:
 * [start] legt die Besprechung an und startet [RecordingService]; der Dienst geht in den
 * Vordergrund und ruft [beginCapture] auf, das einen eigenen Aufnahme-Thread startet.
 * [stop] beendet den Thread; dieser schließt die WAV-Datei, aktualisiert die Besprechung,
 * stößt ggf. die Verarbeitung an und beendet den Dienst.
 */
object Recorder {
    private const val TAG = "Recorder"
    private const val RATE = WavIO.SAMPLE_RATE
    private const val CHUNK = RATE / 10          // 100 ms
    private const val MAX_LEVELS = 120

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var appContext: Context? = null
    private var thread: Thread? = null

    @Synchronized
    fun start(context: Context): String {
        val current = _state.value
        val currentId = current.meetingId
        if (current.active && currentId != null) return currentId

        // Eine noch abschließende vorige Aufnahme kurz abwarten
        thread?.let { if (it.isAlive) runCatching { it.join(2000) } }

        val ctx = context.applicationContext
        appContext = ctx
        val title = "Besprechung " +
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        val created = MeetingRepository.create(title, MeetingStatus.RECORDING)
        val mode = AppSettings.state.value.defaultMode
        MeetingRepository.update(created.id) { it.copy(mode = mode) }
        val id = created.id

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            markError(id, "Keine Berechtigung für das Mikrofon. Bitte den Zugriff erlauben.")
            _state.value = RecState()
            return id
        }

        paused = false
        stopRequested = false
        _state.value = RecState(meetingId = id, active = true)
        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, RecordingService::class.java).putExtra(RecordingService.EXTRA_MEETING_ID, id),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dienst konnte nicht gestartet werden", e)
            _state.value = RecState()
            markError(id, "Die Aufnahme konnte nicht gestartet werden.")
        }
        return id
    }

    fun pause() {
        if (!_state.value.active) return
        paused = true
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!_state.value.active) return
        paused = false
        _state.update { it.copy(paused = false) }
    }

    @Synchronized
    fun stop(context: Context) {
        val s = _state.value
        if (!s.active) return
        appContext = context.applicationContext
        stopRequested = true
        paused = false
        _state.update { it.copy(active = false, paused = false) }
        val id = s.meetingId ?: return
        if (thread == null) {
            // Aufnahme hatte noch nicht begonnen
            finish(id, 0L, null)
        }
    }

    // ------------------------------------------------------------ vom Dienst aufgerufen

    /** Startet den Aufnahme-Thread (nach startForeground). false, wenn nichts aufzunehmen ist. */
    @Synchronized
    internal fun beginCapture(service: Context): Boolean {
        val s = _state.value
        val id = s.meetingId
        if (!s.active || id == null || stopRequested) return false
        if (thread != null) return true
        if (appContext == null) appContext = service.applicationContext
        val t = Thread({ captureLoop(id) }, "ProtokollKI-Aufnahme")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
        return true
    }

    /** Der Dienst konnte nicht in den Vordergrund gehen. */
    @Synchronized
    internal fun failStart(message: String) {
        val id = _state.value.meetingId
        stopRequested = true
        _state.value = RecState()
        if (id != null && thread == null) markError(id, message)
    }

    /** Dienst wurde vom System beendet, während die Aufnahme noch lief. */
    internal fun onServiceDestroyed(context: Context) {
        if (_state.value.active) stop(context)
    }

    // ------------------------------------------------------------ Aufnahme-Thread

    @SuppressLint("MissingPermission")
    private fun captureLoop(id: String) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var samples = 0L
        var error: String? = null
        var record: AudioRecord? = null
        var ns: NoiseSuppressor? = null
        var writer: WavWriter? = null
        try {
            val meeting = MeetingRepository.get(id)
                ?: throw IllegalStateException("Besprechung nicht gefunden.")
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) throw IllegalStateException("Das Mikrofon unterstützt 16 kHz nicht.")
            val bufBytes = maxOf(minBuf, CHUNK * 2 * 4)

            val rec = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufBytes)
                ?: createRecord(MediaRecorder.AudioSource.MIC, bufBytes)
                ?: throw IllegalStateException("Das Mikrofon konnte nicht geöffnet werden.")
            record = rec

            // Rauschunterdrückung an (falls vorhanden), automatische Verstärkung bleibt aus.
            if (NoiseSuppressor.isAvailable()) {
                val effect: NoiseSuppressor? = try {
                    NoiseSuppressor.create(rec.audioSessionId)
                } catch (e: Exception) {
                    null
                }
                if (effect != null) {
                    try {
                        effect.setEnabled(true)
                    } catch (e: Exception) {
                        // ignorieren
                    }
                }
                ns = effect
            }

            val w = WavWriter(File(meeting.audioFile))
            writer = w
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(
                    "Das Mikrofon wird gerade von einer anderen App verwendet.",
                )
            }

            val buf = ShortArray(CHUNK)
            val levels = ArrayDeque<Float>(MAX_LEVELS + 1)
            while (!stopRequested) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    throw IllegalStateException("Fehler beim Lesen vom Mikrofon ($n).")
                }
                // Pausiert: weiterlesen (Puffer leeren), aber nichts schreiben – Zeit bleibt stehen.
                if (n == 0 || paused) continue
                w.write(buf, n)
                samples += n
                levels.addLast(levelFromPcm16(buf, n))
                while (levels.size > MAX_LEVELS) levels.removeFirst()
                val elapsed = samples * 1000L / RATE
                val snapshot = levels.toList()
                _state.update {
                    if (it.meetingId == id && it.active) {
                        it.copy(elapsedMs = elapsed, levels = snapshot)
                    } else it
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aufnahmefehler", e)
            error = e.message ?: "Unbekannter Fehler bei der Aufnahme."
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { ns?.release() }
            runCatching { writer?.close() }
        }
        finish(id, samples, error)
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, bufBytes: Int): AudioRecord? = try {
        val r = AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
        )
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release(); null
        }
    } catch (e: Exception) {
        Log.w(TAG, "AudioRecord mit Quelle $source fehlgeschlagen", e)
        null
    }

    /** Abschluss: Besprechung aktualisieren, ggf. verarbeiten, Dienst beenden. */
    private fun finish(id: String, samples: Long, error: String?) {
        val ctx = appContext
        val duration = samples / RATE.toFloat()
        if (samples <= 0L) {
            markError(id, error ?: "Die Aufnahme ist leer.")
        } else {
            MeetingRepository.update(id) {
                it.copy(
                    status = MeetingStatus.RECORDED,
                    durationSec = duration,
                    error = error?.let { msg -> "Aufnahme vorzeitig beendet: $msg" },
                )
            }
            // Verarbeitung starten, solange unser Vordergrunddienst noch läuft
            // (erlaubt den Start eines weiteren Vordergrunddienstes auch aus dem Hintergrund).
            if (ctx != null && AppSettings.state.value.autoProcess) {
                try {
                    ProcessingService.process(ctx, id)
                } catch (e: Exception) {
                    Log.e(TAG, "Verarbeitung konnte nicht gestartet werden", e)
                }
            }
        }
        synchronized(this) {
            thread = null
            _state.update {
                if (it.meetingId == id) it.copy(active = false, paused = false) else it
            }
        }
        if (ctx != null) {
            runCatching { ctx.stopService(Intent(ctx, RecordingService::class.java)) }
        }
    }

    private fun markError(id: String, message: String) {
        MeetingRepository.update(id) { it.copy(status = MeetingStatus.ERROR, error = message) }
        val ctx = appContext ?: return
        runCatching { ctx.stopService(Intent(ctx, RecordingService::class.java)) }
    }
}
