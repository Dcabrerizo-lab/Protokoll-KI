package de.protokollki.importdoc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.service.ProcessingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ProtocolImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Importiert alte Protokolle (PDF, Word .docx, Text/Markdown), damit sie durchsuchbar sind und ihre
 * Aufgaben in die Aufgabenverfolgung einfließen. Das Sprachmodell erstellt danach Zusammenfassung
 * und Aufgaben wie bei einer Aufnahme. Alles offline.
 */
object ProtocolImporter {

    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain", "text/markdown",
    )

    /** true, wenn [mime] bzw. der Dateiname ein unterstütztes Dokument ist (statt Audio/Video). */
    fun isDocument(mime: String?, name: String?): Boolean {
        val n = name?.lowercase().orEmpty()
        // .doc (altes Word) wird erkannt, damit import() einen verständlichen Hinweis gibt
        return mime in MIME_TYPES || mime?.startsWith("text/") == true || mime == "application/msword" ||
            n.endsWith(".doc") || n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".txt") || n.endsWith(".md")
    }

    fun isDocument(context: Context, uri: Uri): Boolean =
        isDocument(context.contentResolver.getType(uri), displayName(context, uri))

    suspend fun import(context: Context, uri: Uri, startProcessing: Boolean = true): Meeting =
        withContext(Dispatchers.IO) {
            val name = displayName(context, uri) ?: "Protokoll"
            val mime = context.contentResolver.getType(uri)
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val lower = name.lowercase()
                    when {
                        mime == "application/pdf" || lower.endsWith(".pdf") -> pdfText(context, input)
                        lower.endsWith(".docx") || mime == MIME_TYPES[1] -> DocumentText.fromDocx(input)
                        lower.endsWith(".doc") || mime == "application/msword" -> throw ProtocolImportException(
                            "Alte .doc-Dateien werden nicht unterstützt. Bitte in Word als .docx oder PDF speichern.")
                        else -> DocumentText.decode(input.readBytes())
                    }
                } ?: throw ProtocolImportException("Die Datei konnte nicht geöffnet werden.")
            } catch (e: ProtocolImportException) {
                throw e
            } catch (e: LinkageError) {
                throw ProtocolImportException("Diese PDF-Datei kann nicht gelesen werden (Verschlüsselung).", e)
            } catch (e: Exception) {
                throw ProtocolImportException("Die Datei konnte nicht gelesen werden: ${e.message ?: e.javaClass.simpleName}", e)
            }
            if (text.isBlank()) throw ProtocolImportException(
                "In der Datei wurde kein Text gefunden. Eingescannte PDFs (nur Bilder) können nicht gelesen werden.")
            val m = try {
                DocumentText.toMeeting(UUID.randomUUID().toString(), name, text, System.currentTimeMillis())
            } catch (e: IllegalArgumentException) {
                throw ProtocolImportException(e.message ?: "In der Datei wurde kein Text gefunden.", e)
            }
            MeetingRepository.save(m)
            if (startProcessing) ProcessingService.regenerate(context, m.id)
            m
        }

    private fun pdfText(context: Context, input: java.io.InputStream): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) throw ProtocolImportException("Die PDF-Datei ist geschützt und kann nicht gelesen werden.")
            return PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
