package de.protokollki.privacy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.engine.ModelCatalog
import de.protokollki.export.Block
import de.protokollki.export.Exporter
import de.protokollki.export.ProtocolPdf
import de.protokollki.export.formatDate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * „Datenschutz-Nachweis“ als PDF (z. B. für Datenschutzbeauftragte): wo und wie die App Daten
 * verarbeitet, mit den tatsächlichen Einstellungen und Modellen dieses Geräts.
 * Gleiches Layout wie die Protokoll-PDFs ([ProtocolPdf]).
 */
object PrivacyReport {

    /** Erstellt das PDF in cacheDir/exports/ (synchron, wenige ms – trotzdem besser im Hintergrund). */
    fun create(context: Context): File {
        val dir = Exporter.exportDir(context)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
        val file = File(dir, "Datenschutz-Nachweis-$day.pdf")
        val tmp = File(dir, file.name + ".tmp")
        try {
            ProtocolPdf.write(buildBlocks(context), "Datenschutz-Nachweis – Protokoll KI", tmp)
            Exporter.moveInto(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("Datenschutz-Nachweis konnte nicht erstellt werden: " +
                (e.message ?: e.javaClass.simpleName), e)
        }
        return file
    }

    /** Erstellt das PDF und öffnet das Teilen-Menü. */
    fun share(context: Context) {
        val file = create(context)
        Exporter.shareFile(context, file, "application/pdf", "Datenschutz-Nachweis Protokoll KI",
            "Datenschutz-Nachweis teilen")
    }

    // ------------------------------------------------------------------ Inhalt

    private fun buildBlocks(context: Context): List<Block> {
        val s = AppSettings.state.value
        val meetings = MeetingRepository.meetings.value
        val meetingsDir = File(context.filesDir, "meetings")
        val modelsDir = File(context.filesDir, "models")
        val withAudio = meetings.count { !it.audioDeleted && File(it.audioFile).exists() }
        val b = mutableListOf<Block>()

        b += Block.Title("Datenschutz-Nachweis", "Protokoll KI")
        b += Block.Meta("Erstellt am", formatDate(System.currentTimeMillis()))
        b += Block.Meta("App-Version", appVersion(context))
        b += Block.Meta("Gerät", "${Build.MANUFACTURER} ${Build.MODEL}".trim() +
            " · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        b += Block.Divider

        b += Block.Heading("Verarbeitungsort")
        b += Block.Paragraph(
            "Alle Verarbeitungsschritte – Aufnahme, Spracherkennung (Whisper), Sprechererkennung, " +
                "Erstellung von Protokoll und Aufgaben (lokales Sprachmodell), Schwärzung und Vorlesen – " +
                "laufen ausschließlich auf diesem Gerät. Es werden keine Cloud-Dienste verwendet.",
        )

        b += Block.Heading("Netzwerknutzung")
        b += Block.Bullet("Internet wird nur für den einmaligen Download der KI-Modelle genutzt.")
        b += Block.Bullet("Audio, Transkripte, Protokolle und Aufgaben werden nie übertragen.")
        b += Block.Bullet("Keine Analyse-, Absturz- oder Werbe-Tracker, kein Nutzerkonto.")
        val set = runCatching { ModelCatalog.byId(s.modelSetId) ?: ModelCatalog.recommended(context) }.getOrNull()
        if (set != null) {
            b += Block.SubHeading("Download-Quellen (Modellpaket „${set.label}“)")
            (ModelCatalog.common + set.whisper + set.llm).map { it.url }.distinct()
                .forEach { b += Block.Bullet(it) }
        }

        b += Block.Heading("Speicherung und Schutz")
        b += Block.Meta("Speicherort", "Privater App-Speicher (${meetingsDir.absolutePath}); " +
            "für andere Apps nicht zugänglich")
        b += Block.Meta("Cloud-Backup", backupStatus(context))
        b += Block.Meta("Verschlüsselung", encryptionStatus(context))
        b += Block.Meta("App-Sperre", if (s.appLock) "an (Fingerabdruck/Gesicht oder Geräte-PIN)" else "aus")
        b += Block.Meta("Displaysperre eingerichtet", if (AppLock.isAvailable(context)) "ja" else "nein")
        b += Block.Meta("Screenshot-Schutz", if (s.blockScreenshots) "an" else "aus")
        b += Block.Meta("Löschfrist Audio", if (s.audioRetentionDays <= 0) "keine (Audio wird behalten)"
        else "${s.audioRetentionDays} Tage nach der Aufnahme (nach abgeschlossener Verarbeitung; " +
            "Transkript und Protokoll bleiben)")
        b += Block.Meta("Eigene Schwärzungsbegriffe", "${s.customTerms.size}")
        b += Block.Paragraph(
            "Weitergegeben wird nur, was Nutzerinnen und Nutzer selbst über „Teilen“ exportieren – " +
                "auf Wunsch anonymisiert (Namen, Telefonnummern, E-Mail-Adressen, IBAN, Geburtsdaten " +
                "und Adressen geschwärzt).",
        )

        b += Block.Heading("Gespeicherte Daten")
        b += Block.Meta("Besprechungen", "${meetings.size}")
        b += Block.Meta("davon mit Audioaufnahme", "$withAudio")
        b += Block.Meta("Speicherbedarf", formatBytes(sizeOf(meetingsDir)))

        b += Block.Heading("Installierte Modelle")
        val models = installedModels(modelsDir)
        if (models.isEmpty()) b += Block.Paragraph("Keine Modelle installiert.")
        else b += Block.Table(
            listOf("Datei", "Größe", "Quelle"),
            models,
            listOf(0.36f, 0.12f, 0.52f),
        )

        b += Block.Note(
            "Automatisch erstellt von Protokoll KI. Die Angaben beruhen auf der aktuellen Konfiguration " +
                "dieses Geräts zum oben genannten Zeitpunkt.",
        )
        return b
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName ?: "?"} (Build ${info.longVersionCode})"
    }.getOrDefault("unbekannt")

    private fun backupStatus(context: Context): String {
        val allowed = (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        return if (allowed) "erlaubt (Daten sind über Regeln von der Sicherung ausgeschlossen)"
        else "deaktiviert – keine Cloud-Sicherung und keine Übertragung auf neue Geräte"
    }

    private fun encryptionStatus(context: Context): String = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        when (dpm?.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ->
                "aktiv – Android-Dateiverschlüsselung (an die Displaysperre gebunden)"
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "aktiv – Android-Geräteverschlüsselung"
            else -> "vom System nicht bestätigt"
        }
    }.getOrDefault("vom System nicht bestätigt")

    /** Zeilen (Datei, Größe, Quelle) für alle vollständig vorhandenen Modelldateien. */
    private fun installedModels(dir: File): List<List<String>> {
        val sources = HashMap<String, String>()
        (ModelCatalog.common + ModelCatalog.sets.flatMap { listOf(it.whisper, it.llm) }).forEach { f ->
            if (f.extract.isEmpty()) sources[f.target] = f.url
            else f.extract.values.forEach { sources[it] = f.url }
        }
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }
            .sortedByDescending { it.length() }
            .map { listOf(it.name, formatBytes(it.length()), sources[it.name] ?: "–") }
    }

    private fun sizeOf(f: File): Long =
        if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.GERMANY, "%.0f MB", bytes / 1e6)
        bytes >= 1_000L -> String.format(Locale.GERMANY, "%.0f KB", bytes / 1e3)
        else -> "$bytes Byte"
    }
}
