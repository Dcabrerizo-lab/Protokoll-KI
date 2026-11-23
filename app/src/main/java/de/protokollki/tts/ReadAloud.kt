package de.protokollki.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import de.protokollki.data.Meeting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ReadAloudState(
    val active: Boolean = false,     // es wird vorgelesen (oder ist pausiert)
    val paused: Boolean = false,
    val title: String = "",
    val index: Int = 0,              // aktueller Abschnitt (0-basiert)
    val total: Int = 0,
    val error: String? = null,
    val speed: Float = 1f,           // aktuelle Sprechgeschwindigkeit (1 = normal)
)

/**
 * Liest ein Protokoll mit der Android-Sprachausgabe vor – deutsche Stimme, bevorzugt eine
 * Offline-Stimme. Der Text wird in Sätze geteilt ([buildSpeechText]), damit Pause/Fortsetzen und
 * Springen satzgenau funktionieren. Bei Anrufen o. Ä. (Audiofokus verloren) wird pausiert.
 *
 * Alle Methoden dürfen von jedem Thread aufgerufen werden.
 */
object ReadAloud {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var initializing = false
    private var ready = false
    private var items: List<String> = emptyList()
    private var generation = 0                  // verwirft Rückrufe abgebrochener Durchläufe
    private var pendingStart: Int? = null       // Start, sobald die Sprachausgabe bereit ist
    private var speed = 1f
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ------------------------------------------------------------------ öffentlich

    fun speakMeeting(context: Context, m: Meeting) {
        val list = buildSpeechText(m)
        synchronized(lock) {
            val app = context.applicationContext
            audioManager = app.getSystemService(AudioManager::class.java)
            generation++
            tts?.stop()
            items = list
            _state.value = ReadAloudState(
                active = list.isNotEmpty(), title = m.title, index = 0, total = list.size, speed = speed,
            )
            if (list.isEmpty()) return
            if (tts != null && ready) {
                startFrom(0)
                return
            }
            pendingStart = 0
            val engine = tts
            if (engine == null) {
                if (!initializing) {
                    initializing = true
                    tts = TextToSpeech(app) { status -> onInit(status) }
                }
            } else if (!initializing) {
                // Engine da, aber beim letzten Mal fehlte die deutsche Stimme: erneut versuchen
                onInit(TextToSpeech.SUCCESS)
            }
        }
    }

    /** Anhalten; [resume] setzt am Anfang des aktuellen Satzes fort. */
    fun pause() = synchronized(lock) {
        val s = _state.value
        if (!s.active || s.paused) return@synchronized
        generation++
        tts?.stop()
        abandonFocus()
        _state.value = s.copy(paused = true)
    }

    fun resume() = synchronized(lock) {
        val s = _state.value
        if (!s.active || !s.paused || items.isEmpty()) return@synchronized
        _state.value = s.copy(paused = false)
        if (tts != null && ready) startFrom(s.index) else pendingStart = s.index
    }

    fun stop() = synchronized(lock) {
        generation++
        pendingStart = null
        tts?.stop()
        abandonFocus()
        items = emptyList()
        _state.value = ReadAloudState(speed = speed)
    }

    /** Sprechgeschwindigkeit (0,5 … 2,5; 1 = normal). Wirkt sofort ab dem aktuellen Satz. */
    fun setSpeed(f: Float) = synchronized(lock) {
        speed = if (f.isNaN()) 1f else f.coerceIn(0.5f, 2.5f)
        tts?.setSpeechRate(speed)
        val s = _state.value
        _state.value = s.copy(speed = speed)
        if (s.active && !s.paused && ready && tts != null) startFrom(s.index)
    }

    /** Um [delta] Sätze vor (+) oder zurück (−) springen. */
    fun skip(delta: Int) = synchronized(lock) {
        val s = _state.value
        if (!s.active || items.isEmpty()) return@synchronized
        val target = (s.index + delta).coerceIn(0, items.lastIndex)
        if (!s.paused && ready && tts != null) startFrom(target)
        else {
            if (pendingStart != null) pendingStart = target
            _state.value = s.copy(index = target)
        }
    }

    /** Fehlermeldung quittieren (z. B. nach Anzeige in einer Snackbar). */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Sprachausgabe komplett freigeben (z. B. in `onDestroy` der Activity, optional). */
    fun shutdown() = synchronized(lock) {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        initializing = false
    }

    // ------------------------------------------------------------------ intern

    private fun onInit(status: Int) = synchronized(lock) {
        initializing = false
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            runCatching { engine?.shutdown() }
            tts = null
            ready = false
            fail("Die Sprachausgabe ist auf diesem Gerät nicht verfügbar. Bitte in den Android-Einstellungen " +
                "unter „Sprachausgabe“ eine Sprach-Engine auswählen.")
            return@synchronized
        }
        if (!configure(engine)) {
            ready = false
            fail("Keine deutsche Stimme installiert. Bitte in den Android-Einstellungen unter " +
                "„Sprachausgabe“ die deutschen Sprachdaten herunterladen.")
            return@synchronized
        }
        ready = true
        engine.setOnUtteranceProgressListener(listener)
        val start = pendingStart
        pendingStart = null
        val s = _state.value
        if (start != null && s.active && !s.paused) startFrom(start)
    }

    /** Deutsche Sprache + beste Offline-Stimme wählen. */
    private fun configure(engine: TextToSpeech): Boolean {
        val res = runCatching { engine.setLanguage(Locale.GERMANY) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        val best = voices
            .filter { v ->
                v.locale.language == "de" && !v.isNetworkConnectionRequired &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == "DE" }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency },
            )
            .firstOrNull()
        if (best != null) runCatching { engine.setVoice(best) }
        engine.setSpeechRate(speed)
        engine.setAudioAttributes(attributes)
        return true
    }

    /** Ab Satz [index] alles neu in die Warteschlange stellen (lock wird gehalten). */
    private fun startFrom(index: Int) {
        val engine = tts ?: return
        if (items.isEmpty()) return
        val from = index.coerceIn(0, items.lastIndex)
        generation++
        val gen = generation
        engine.stop()
        requestFocus()
        _state.value = _state.value.copy(active = true, paused = false, index = from, error = null)
        for (i in from until items.size) {
            val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = engine.speak(items[i], mode, null, "pk|$gen|$i")
            if (r != TextToSpeech.SUCCESS) {
                fail("Vorlesen konnte nicht gestartet werden.")
                return
            }
        }
    }

    private fun fail(message: String) {
        generation++
        abandonFocus()
        _state.value = _state.value.copy(active = false, paused = false, error = message)
    }

    private fun finished() {
        abandonFocus()
        _state.value = _state.value.copy(active = false, paused = false, index = 0)
    }

    /** "pk|gen|index" → index, wenn der Rückruf zum aktuellen Durchlauf gehört. */
    private fun currentIndex(id: String?): Int? {
        val parts = id?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != "pk") return null
        val gen = parts[1].toIntOrNull() ?: return null
        if (gen != generation) return null
        return parts[2].toIntOrNull()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = synchronized(lock) {
            val i = currentIndex(utteranceId) ?: return@synchronized
            _state.value = _state.value.copy(index = i)
        }

        override fun onDone(utteranceId: String?) = synchronized(lock) {
            val i = currentIndex(utteranceId) ?: return@synchronized
            if (i >= items.lastIndex) finished()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) = synchronized(lock) {
            currentIndex(utteranceId) ?: return@synchronized
            fail("Fehler bei der Sprachausgabe.")
        }
    }

    // ------------------------------------------------------------------ Audiofokus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) pause()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        runCatching { am.requestAudioFocus(req) }
        focusRequest = req
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(req) }
    }
}
