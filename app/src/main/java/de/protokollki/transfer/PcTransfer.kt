package de.protokollki.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.TaskTracker
import de.protokollki.export.Exporter
import de.protokollki.export.sanitizeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * „Am PC auswerten“: Aufnahme samt Einstellungen als .protokoll-Datei weitergeben (Quick Share,
 * USB-Kabel) und das fertige Protokoll vom PC wieder übernehmen. Die Datei geht nur dorthin,
 * wohin der Nutzer sie selbst schickt; die App lädt nichts hoch.
 */
object PcTransfer {

    /** Erstellt die Austauschdatei im Export-Ordner (für Teilen über den FileProvider). */
    suspend fun createPackage(context: Context, m: Meeting): File = withContext(Dispatchers.IO) {
        val dir = Exporter.exportDir(context)
        val file = File(dir, sanitizeFileName(m.title) + PcPackage.EXT)
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.outputStream().use { writeTo(it, m) }
            Exporter.moveInto(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("Die Datei für den PC konnte nicht erstellt werden: ${e.message}", e)
        }
        file
    }

    /** Schreibt die Austauschdatei direkt an einen vom Nutzer gewählten Ort (z. B. „Download“). */
    suspend fun saveTo(context: Context, m: Meeting, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: throw IllegalStateException("Der Speicherort ist nicht beschreibbar.")
        out.use { writeTo(it, m) }
    }

    fun suggestedName(m: Meeting): String = sanitizeFileName(m.title) + PcPackage.EXT

    fun share(context: Context, file: File, m: Meeting) =
        Exporter.shareFile(context, file, PcPackage.MIME, m.title, "An PC senden")

    private fun writeTo(out: java.io.OutputStream, m: Meeting) {
        val s = AppSettings.state.value
        val open = if (s.trackTasks) TaskTracker.candidatesFor(m).map { (om, t) ->
            PcPackage.OpenTaskRef(t.id, om.id, t.text, t.owner)
        } else emptyList()
        val info = PcPackage.infoJson(m, s.language, de.protokollki.engine.Vocabulary.terms(), s.organization, open)
        val audio = File(m.audioFile).takeIf { !m.audioDeleted && it.exists() && it.length() > 44 }
        if (audio != null) audio.inputStream().use { PcPackage.write(out, info, it) }
        else PcPackage.write(out, info, null)
    }

    // ------------------------------------------------------------------ Import

    /** true, wenn die Datei eine .protokoll-Datei ist (Endung oder Inhalt). */
    suspend fun isPackage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)?.lowercase().orEmpty()
        if (name.endsWith(PcPackage.EXT)) return@withContext true
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("audio/") || mime.startsWith("video/") || mime.startsWith("text/") ||
            mime == "application/pdf") return@withContext false
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val z = ZipInputStream(input)
                var found = false
                var n = 0
                while (n++ < 4) {
                    val e = z.nextEntry ?: break
                    if (e.name == "protokoll.json") { found = true; break }
                }
                found
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * Übernimmt eine .protokoll-Datei. Gibt es die Besprechung schon (zurück vom PC), wird sie
     * aktualisiert, sonst neu angelegt (z. B. am PC aufgenommen). Rückgabe: die Besprechung.
     */
    suspend fun import(context: Context, uri: Uri): Meeting = withContext(Dispatchers.IO) {
        val tmpDir = File(context.cacheDir, "pc-import-" + UUID.randomUUID()).apply { mkdirs() }
        val tmpAudio = File(tmpDir, "audio.wav")
        try {
            val contents = context.contentResolver.openInputStream(uri)?.use { input ->
                PcPackage.read(input) { audio -> tmpAudio.outputStream().use { audio.copyTo(it, 1 shl 16) } }
            } ?: throw IllegalArgumentException("Die Datei konnte nicht geöffnet werden.")
            val incoming = contents.meeting
            val hasAudio = tmpAudio.exists() && tmpAudio.length() > 44
            val existing = MeetingRepository.get(incoming.id)
            val result = if (existing != null) {
                if (existing.isBusy) throw IllegalStateException("Diese Besprechung wird gerade verarbeitet. Bitte danach erneut übernehmen.")
                val local = File(existing.audioFile)
                // Aufnahme nur ergänzen, wenn sie auf dem Handy fehlt und nicht per Löschfrist entfernt wurde
                if (hasAudio && !local.exists() && !existing.audioDeleted) moveFile(tmpAudio, local)
                MeetingRepository.update(existing.id) { PcPackage.merge(it, incoming) }!!
            } else {
                val target = File(MeetingRepository.dirFor(incoming.id), "audio.wav")
                if (hasAudio) moveFile(tmpAudio, target)
                PcPackage.asNew(incoming, target.absolutePath, hasAudio).also { MeetingRepository.save(it) }
            }
            applyFollowUps(result)
            result
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Am PC als erledigt erkannte Aufgaben früherer Besprechungen auch auf dem Handy abhaken. */
    private fun applyFollowUps(m: Meeting) {
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN).format(Date(m.createdAt))
        m.followUps.filter { it.done }.forEach { f ->
            val origin = MeetingRepository.get(f.meetingId) ?: return@forEach
            if (origin.tasks.any { it.id == f.taskId && !it.done }) {
                TaskTracker.setDone(f.meetingId, f.taskId, true, "Erledigt laut Besprechung „${m.title}“ vom $date")
            }
        }
    }

    private fun moveFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
