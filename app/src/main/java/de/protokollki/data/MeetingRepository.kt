package de.protokollki.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Speichert Besprechungen als JSON im privaten App-Speicher
 * (filesDir/meetings/<id>/meeting.json + audio.wav). Nichts verlässt das Gerät.
 */
object MeetingRepository {
    private lateinit var root: File
    private val _meetings = MutableStateFlow<List<Meeting>>(emptyList())
    val meetings: StateFlow<List<Meeting>> = _meetings.asStateFlow()

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "meetings").apply { mkdirs() }
        rawStatus.clear()
        val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                meetingFromJson(JSONObject(File(dir, "meeting.json").readText()))
            }.onSuccess { rawStatus[it.id] = it.status }.recoverCatching {
                // Absturz zwischen Schreiben und Umbenennen: vollständige Hilfsdatei übernehmen
                val tmp = File(dir, "meeting.json.tmp")
                meetingFromJson(JSONObject(tmp.readText())).also { tmp.renameTo(File(dir, "meeting.json")) }
            }.getOrNull()
        }.map {
            // Nach Absturz/Neustart hängende Zustände zurücksetzen
            when {
                it.status == MeetingStatus.RECORDING -> {
                    val f = File(it.audioFile)
                    runCatching { de.protokollki.audio.WavIO.repairHeader(f) }
                    if (f.length() > 16044) it.copy(status = MeetingStatus.RECORDED, error = null,
                        durationSec = runCatching { de.protokollki.audio.WavIO.durationSec(it.audioFile) }
                            .getOrDefault(0f))
                    else it.copy(status = MeetingStatus.ERROR, error = "Aufnahme wurde unterbrochen.")
                }
                // Prozess wurde während der Verarbeitung beendet (Absturz, Systemabschuss, kill -9).
                // Nicht automatisch neu starten (bei einem wiederholbaren Absturz wäre das eine
                // Endlosschleife), sondern verständlich melden; "Erneut versuchen" setzt fort
                // (mit fertigem Transkript nur noch der Protokoll-Schritt).
                it.isBusy -> if (it.segments.isNotEmpty() && !it.summary.isEmpty)
                    // Neuerstellung eines fertigen Protokolls unterbrochen: altes Protokoll bleibt gültig
                    it.copy(status = MeetingStatus.DONE, progress = 0f, progressText = "")
                else it.copy(status = MeetingStatus.ERROR, progress = 0f, progressText = "",
                    error = INTERRUPTED_MESSAGE)
                else -> it
            }
        }
        _meetings.value = loaded.sortedByDescending { it.createdAt }
        // Reparierte Zustände auch auf die Platte schreiben (sonst stünde dort weiter "in Arbeit")
        loaded.filter { rawStatus[it.id] != it.status }.forEach { save(it) }
    }

    private val rawStatus = mutableMapOf<String, MeetingStatus>()

    fun dirFor(id: String) = File(root, id).apply { mkdirs() }

    /** Legt eine neue, leere Besprechung an (Audio-Datei existiert noch nicht). */
    fun create(title: String, status: MeetingStatus = MeetingStatus.RECORDING): Meeting {
        val id = UUID.randomUUID().toString()
        val m = Meeting(
            id = id,
            title = title,
            createdAt = System.currentTimeMillis(),
            audioFile = File(dirFor(id), "audio.wav").absolutePath,
            status = status,
        )
        save(m)
        return m
    }

    fun get(id: String): Meeting? = _meetings.value.firstOrNull { it.id == id }

    const val INTERRUPTED_MESSAGE =
        "Die Verarbeitung wurde unterbrochen, weil die App beendet wurde (z. B. vom System bei " +
            "wenig Arbeitsspeicher). Bitte „Erneut versuchen“ tippen."

    /**
     * Speichert atomar (erst Hilfsdatei, dann umbenennen): wird die App beim Schreiben beendet,
     * bleibt die alte meeting.json vollständig erhalten statt abgeschnitten (die Besprechung
     * wäre sonst beim nächsten Start verschwunden). Ist der Speicher voll, bleibt der Stand im
     * Arbeitsspeicher erhalten und die App stürzt nicht ab.
     */
    @Synchronized
    fun save(m: Meeting) {
        _meetings.value = (_meetings.value.filterNot { it.id == m.id } + m)
            .sortedByDescending { it.createdAt }
        try {
            val dir = dirFor(m.id)
            val tmp = File(dir, "meeting.json.tmp")
            tmp.writeText(m.toJson().toString())
            if (!tmp.renameTo(File(dir, "meeting.json"))) {
                File(dir, "meeting.json").writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("MeetingRepository", "Besprechung ${m.id} konnte nicht gespeichert werden", e)
        }
    }

    /** Atomar ändern. Fortschritt wird nur im Speicher gehalten, nicht jedes Mal geschrieben. */
    @Synchronized
    fun update(id: String, persist: Boolean = true, change: (Meeting) -> Meeting): Meeting? {
        val current = get(id) ?: return null
        val updated = change(current)
        if (persist) save(updated) else {
            _meetings.value = _meetings.value.map { if (it.id == id) updated else it }
        }
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        File(root, id).deleteRecursively()
        _meetings.value = _meetings.value.filterNot { it.id == id }
    }
}
