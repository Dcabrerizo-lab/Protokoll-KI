package de.protokollki.transfer

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.meetingFromJson
import de.protokollki.data.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Austauschdatei zwischen Handy und PC-Programm: „<Titel>.protokoll“.
 *
 * ZIP mit `protokoll.json` (Besprechung im JSON-Format von Model.kt plus Wörterbuch, Einrichtung
 * und offenen Aufgaben für den Abgleich) und `audio.wav`. Gleiches Format wie pc/pkpc/package.py.
 * Keine android.*-Abhängigkeiten (per JVM-Unit-Test prüfbar).
 */
object PcPackage {
    const val EXT = ".protokoll"
    const val FORMAT = "protokoll-ki"
    const val VERSION = 1
    const val MIME = "application/octet-stream"

    data class OpenTaskRef(val taskId: String, val meetingId: String, val text: String, val owner: String)

    data class Contents(
        val meeting: Meeting,
        val createdBy: String,
        val language: String = "de",
        val vocabulary: List<String> = emptyList(),
        val organization: String = "",
    )

    fun infoJson(
        m: Meeting,
        language: String,
        vocabulary: List<String>,
        organization: String,
        openTasks: List<OpenTaskRef>,
    ): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdBy", "android")
        put("settings", JSONObject().apply {
            put("language", language)
            put("vocabulary", JSONArray(vocabulary))
            put("organization", organization)
        })
        put("openTasks", JSONArray().also { a ->
            openTasks.forEach {
                a.put(JSONObject().put("taskId", it.taskId).put("meetingId", it.meetingId)
                    .put("text", it.text).put("owner", it.owner))
            }
        })
        // Pfad auf dem Handy ist für den PC bedeutungslos
        put("meeting", m.copy(audioFile = "audio.wav", progress = 0f, progressText = "").toJson())
    }

    /** Schreibt das Paket. [audio] wird gestreamt (lange Aufnahmen: über 100 MB). */
    fun write(out: OutputStream, info: JSONObject, audio: InputStream?) {
        ZipOutputStream(out.buffered()).use { z ->
            z.setLevel(1)
            z.putNextEntry(ZipEntry("protokoll.json"))
            z.write(info.toString(1).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            if (audio != null) {
                z.putNextEntry(ZipEntry("audio.wav"))
                audio.copyTo(z, 1 shl 16)
                z.closeEntry()
            }
        }
    }

    /**
     * Liest das Paket. `audio.wav` wird an [audioSink] übergeben (falls vorhanden), der Rest
     * zurückgegeben. Wirft [IllegalArgumentException] bei fremden oder zu neuen Dateien.
     */
    fun read(input: InputStream, audioSink: (InputStream) -> Unit): Contents {
        var info: JSONObject? = null
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name) {
                    "protokoll.json" -> info = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                    "audio.wav" -> audioSink(NonClosing(z))
                }
                z.closeEntry()
            }
        }
        val o = info ?: throw IllegalArgumentException("Das ist keine Datei von Protokoll KI.")
        if (o.optString("format") != FORMAT) throw IllegalArgumentException("Das ist keine Datei von Protokoll KI.")
        if (o.optInt("version", 1) > VERSION) {
            throw IllegalArgumentException("Diese Datei stammt aus einer neueren Version. Bitte die App aktualisieren.")
        }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val vocab = s.optJSONArray("vocabulary")
        return Contents(
            meeting = meetingFromJson(o.getJSONObject("meeting")),
            createdBy = o.optString("createdBy"),
            language = s.optString("language", "de"),
            vocabulary = if (vocab == null) emptyList() else (0 until vocab.length()).map { vocab.optString(it) },
            organization = s.optString("organization"),
        )
    }

    /**
     * Ergebnis vom PC in eine vorhandene Besprechung übernehmen: Inhalt (Transkript, Sprecher,
     * Protokoll, Aufgaben) vom PC, Erledigt-Häkchen und Löschfrist-Status vom Handy.
     */
    fun merge(local: Meeting, incoming: Meeting): Meeting {
        val doneTexts = local.tasks.filter { it.done }.map { it.text }.toSet()
        val hasContent = incoming.segments.isNotEmpty()
        return local.copy(
            title = incoming.title.ifBlank { local.title },
            durationSec = if (incoming.durationSec > 0f) incoming.durationSec else local.durationSec,
            mode = incoming.mode,
            template = incoming.template,
            expectedSpeakers = incoming.expectedSpeakers,
            segments = if (hasContent) incoming.segments else local.segments,
            speakers = if (hasContent) incoming.speakers else local.speakers,
            summary = if (incoming.summary.isEmpty) local.summary else incoming.summary,
            tasks = incoming.tasks.map { if (it.text in doneTexts) it.copy(done = true) else it },
            followUps = incoming.followUps,
            agenda = incoming.agenda,
            sensitiveTerms = (local.sensitiveTerms + incoming.sensitiveTerms).distinctBy { it.lowercase() },
            location = incoming.location, chair = incoming.chair, minuteTaker = incoming.minuteTaker,
            status = if (hasContent) MeetingStatus.DONE else local.status,
            error = null, progress = 0f, progressText = "",
        )
    }

    /** Neue Besprechung aus dem Paket (z. B. am PC aufgenommen). */
    fun asNew(incoming: Meeting, audioFile: String, hasAudio: Boolean): Meeting = incoming.copy(
        audioFile = audioFile,
        audioDeleted = !hasAudio,
        status = when {
            incoming.segments.isNotEmpty() && (incoming.status == MeetingStatus.DONE || !incoming.summary.isEmpty) ->
                MeetingStatus.DONE
            hasAudio -> MeetingStatus.RECORDED
            incoming.segments.isNotEmpty() -> MeetingStatus.DONE
            else -> MeetingStatus.ERROR
        },
        error = if (!hasAudio && incoming.segments.isEmpty()) "Die Datei enthält weder Aufnahme noch Transkript." else null,
        progress = 0f, progressText = "",
    )

    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {}
    }
}
