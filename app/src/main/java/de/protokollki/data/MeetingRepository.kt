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
        val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                meetingFromJson(JSONObject(File(dir, "meeting.json").readText()))
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
                it.isBusy -> it.copy(status = MeetingStatus.RECORDED, progress = 0f,
                    progressText = "")
                else -> it
            }
        }
        _meetings.value = loaded.sortedByDescending { it.createdAt }
    }

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

    @Synchronized
    fun save(m: Meeting) {
        File(dirFor(m.id), "meeting.json").writeText(m.toJson().toString())
        _meetings.value = (_meetings.value.filterNot { it.id == m.id } + m)
            .sortedByDescending { it.createdAt }
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
