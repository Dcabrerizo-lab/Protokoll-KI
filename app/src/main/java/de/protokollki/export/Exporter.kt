package de.protokollki.export

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import de.protokollki.data.Meeting
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

    fun export(context: Context, m: Meeting, f: ExportFormat): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        cleanup(dir)
        val file = File(dir, sanitizeFileName(m.title) + "." + f.ext)
        val blocks = buildBlocks(m)
        val tmp = File(dir, file.name + ".tmp")
        try {
            when (f) {
                ExportFormat.PDF -> ProtocolPdf.write(blocks, m.title.ifBlank { "Protokoll" }, tmp)
                ExportFormat.DOCX -> FileOutputStream(tmp).use { DocxWriter.write(blocks, it) }
                ExportFormat.MARKDOWN -> tmp.writeText(renderMarkdown(blocks), Charsets.UTF_8)
                ExportFormat.TEXT -> tmp.writeText(renderPlainText(blocks), Charsets.UTF_8)
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("Export als ${f.label} fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return file
    }

    fun share(context: Context, m: Meeting, f: ExportFormat) {
        val file = export(context, m, f)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = f.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, m.title)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Protokoll teilen").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (findActivity(context) == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun plainText(m: Meeting): String = renderPlainText(buildBlocks(m))

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
