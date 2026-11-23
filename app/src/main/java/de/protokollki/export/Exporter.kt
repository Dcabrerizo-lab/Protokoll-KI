package de.protokollki.export

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.privacy.Redactor
import java.io.File
import java.io.FileOutputStream

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    TEXT("Text", "txt", "text/plain"),
}

/**
 * Exportiert Protokolle als PDF, Word, Markdown oder Text nach cacheDir/exports/ und teilt sie
 * über den FileProvider. [export] und [share] arbeiten synchron (PDF bei langen Wortprotokollen
 * ggf. einige hundert ms) – idealerweise aus einem Hintergrund-Thread aufrufen.
 */
object Exporter {

    /** Namenszusatz anonymisierter Exporte. */
    const val ANONYMIZED_SUFFIX = "-anonymisiert"

    /**
     * Schreibt das Protokoll in cacheDir/exports/. Mit [anonymized] wird vorher
     * [Redactor.redact] angewandt (Begriffe der Besprechung + eigene Begriffe aus den
     * Einstellungen); der Dateiname wird dann aus dem geschwärzten Titel gebildet und
     * erhält den Zusatz „-anonymisiert“.
     */
    fun export(context: Context, m: Meeting, f: ExportFormat, anonymized: Boolean = false): File =
        writeExport(context, prepare(m, anonymized), f, anonymized)

    private fun writeExport(context: Context, src: Meeting, f: ExportFormat, anonymized: Boolean): File {
        val dir = exportDir(context)
        cleanup(dir)
        val baseName = sanitizeFileName(src.title) + if (anonymized) ANONYMIZED_SUFFIX else ""
        val file = File(dir, baseName + "." + f.ext)
        val blocks = buildBlocks(src)
        val tmp = File(dir, file.name + ".tmp")
        try {
            when (f) {
                ExportFormat.PDF -> ProtocolPdf.write(blocks, src.title.ifBlank { "Protokoll" }, tmp)
                ExportFormat.DOCX -> FileOutputStream(tmp).use { DocxWriter.write(blocks, it) }
                ExportFormat.MARKDOWN -> tmp.writeText(renderMarkdown(blocks), Charsets.UTF_8)
                ExportFormat.TEXT -> tmp.writeText(renderPlainText(blocks), Charsets.UTF_8)
            }
            moveInto(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("Export als ${f.label} fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return file
    }

    fun share(context: Context, m: Meeting, f: ExportFormat, anonymized: Boolean = false) {
        val src = prepare(m, anonymized)
        val file = writeExport(context, src, f, anonymized)
        // Betreff ebenfalls aus dem (ggf. geschwärzten) Titel – sonst stünde der Name doch in der Mail
        shareFile(context, file, f.mime, src.title, "Protokoll teilen")
    }

    /** Protokoll als Klartext (z. B. für die Zwischenablage), optional anonymisiert. */
    fun plainText(m: Meeting, anonymized: Boolean = false): String =
        renderPlainText(buildBlocks(prepare(m, anonymized)))

    /**
     * Teilt eine Datei aus cacheDir/exports/ über den FileProvider (z. B. den Datenschutz-Nachweis).
     * Liegt [file] woanders, wird sie vorher dorthin kopiert.
     */
    fun shareFile(context: Context, file: File, mime: String, subject: String, chooserTitle: String = "Teilen") {
        val dir = exportDir(context)
        val shared = if (file.parentFile?.canonicalPath == dir.canonicalPath) file
        else File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, shared.name)
            clipData = ClipData.newUri(context.contentResolver, shared.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (findActivity(context) == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Export-Ordner (vom FileProvider freigegeben). */
    internal fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Fertige temporäre Datei an ihren Zielort bringen (überschreibt). */
    internal fun moveInto(tmp: File, file: File) {
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun prepare(m: Meeting, anonymized: Boolean): Meeting =
        if (anonymized) Redactor.redact(m, AppSettings.state.value.customTerms) else m

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Alte Exporte (älter als 1 Tag) entfernen. */
    private fun cleanup(dir: File) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }
}
