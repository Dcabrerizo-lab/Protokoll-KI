package de.protokollki.privacy

import android.content.Context
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.tts.ReadAloud
import java.io.File

/**
 * Löschfristen und „Alles löschen“.
 *
 * [apply] beim App-Start (und nach Ändern der Frist) aufrufen; die Arbeit ist klein
 * (nur Dateilöschungen), kann aber auch aus einem Hintergrund-Thread laufen.
 */
object Retention {

    /**
     * Löscht `audio.wav` aller fertig verarbeiteten Besprechungen, die älter als
     * `audioRetentionDays` sind, und setzt `audioDeleted = true`. Transkript und Protokoll bleiben.
     * @return Anzahl der gelöschten Aufnahmen
     */
    fun apply(context: Context): Int {
        val days = AppSettings.state.value.audioRetentionDays
        if (days <= 0) return 0
        val now = System.currentTimeMillis()
        var count = 0
        MeetingRepository.meetings.value
            .filter { shouldDeleteAudio(it, now, days) }
            .forEach { m ->
                val f = File(m.audioFile)
                val gone = !f.exists() || f.delete()
                if (gone) {
                    // Reste einer abgebrochenen Reparatur/Konvertierung gleich mit entfernen
                    File(f.parentFile, f.name + ".tmp").delete()
                    MeetingRepository.update(m.id) { it.copy(audioDeleted = true) }
                    count++
                }
            }
        return count
    }

    /**
     * Löscht alle Besprechungen (Audio, Transkripte, Protokolle) und alle Export-Dateien.
     * Einstellungen und heruntergeladene Modelle bleiben erhalten. Eine gerade laufende Aufnahme
     * oder Verarbeitung wird nicht angerührt (vorher stoppen).
     * @return Anzahl der gelöschten Besprechungen
     */
    fun deleteAll(context: Context): Int {
        runCatching { ReadAloud.stop() }
        val all = MeetingRepository.meetings.value
        val active = all.filter { it.status == MeetingStatus.RECORDING || it.isBusy }.map { it.id }.toSet()
        val toDelete = all.filterNot { it.id in active }
        toDelete.forEach { MeetingRepository.delete(it.id) }
        // Ordner auch dann leeren, wenn Dateien nicht (mehr) in der Liste stehen
        File(context.filesDir, "meetings").listFiles()
            ?.filterNot { it.name in active }
            ?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "exports").deleteRecursively()
        runCatching { de.protokollki.engine.VoiceProfiles.deleteAll() }
        return toDelete.size
    }
}
