package de.protokollki.testutil

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Erzeugen und Prüfen echter Dokumente mit den Android-Bordmitteln (PdfRenderer, XmlPullParser). */
object Documents {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /**
     * Prüft, dass [file] ein echtes, darstellbares PDF ist: Kopf „%PDF-“, mindestens [minPages]
     * Seiten laut PdfRenderer, und Seite 1 enthält tatsächlich sichtbare Inhalte.
     * @return Seitenzahl
     */
    fun assertValidPdf(file: File, minPages: Int = 1): Int {
        assertTrue("PDF fehlt: $file", file.isFile)
        assertTrue("PDF ist leer: $file", file.length() > 200)
        val head = ByteArray(5)
        file.inputStream().use { assertEquals(5, it.read(head)) }
        assertEquals("Kein PDF-Kopf in ${file.name}", "%PDF-", String(head, Charsets.US_ASCII))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pages = renderer.pageCount
                assertTrue("${file.name}: nur $pages Seiten, erwartet ≥ $minPages", pages >= minPages)
                renderer.openPage(0).use { page ->
                    assertTrue("Seitengröße ${page.width}×${page.height}", page.width > 100 && page.height > 100)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        val ink = px.count { it != Color.WHITE }
                        assertTrue("${file.name}: Seite 1 ist leer ($ink Pixel)", ink > 500)
                    } finally {
                        bmp.recycle()
                    }
                }
                return pages
            }
        }
    }

    /** Inhalt einer DOCX-Datei: Rohtext aller XML-Teile und der Fließtext aus document.xml. */
    class Docx(val rawXml: Map<String, String>, val text: String)

    /**
     * Öffnet [file] als ZIP, verlangt die Pflichtteile einer Word-Datei und parst JEDEN XML-Teil
     * mit dem Android-XmlPullParser (wohlgeformt). Der Text aller `<w:t>` wird gesammelt.
     */
    fun readDocx(file: File): Docx {
        val raw = LinkedHashMap<String, String>()
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            for (required in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")) {
                assertTrue("${file.name}: $required fehlt (vorhanden: $names)", required in names)
            }
            for (e in zip.entries().toList()) {
                if (e.isDirectory) continue
                val content = zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
                if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) raw[e.name] = content
            }
        }
        val texts = StringBuilder()
        for ((name, xml) in raw) {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(StringReader(xml))
            var inText = false
            try {
                while (true) {
                    when (p.next()) {
                        XmlPullParser.START_TAG -> inText = name == "word/document.xml" && p.name == "t" && p.namespace == W_NS
                        XmlPullParser.END_TAG -> {
                            if (inText) texts.append('\n')
                            inText = false
                        }
                        XmlPullParser.TEXT -> if (inText) texts.append(p.text)
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
            } catch (e: Exception) {
                throw AssertionError("${file.name}: $name ist kein wohlgeformtes XML: ${e.message}", e)
            }
        }
        return Docx(raw, texts.toString())
    }

    // ------------------------------------------------------------------ Testdokumente erzeugen

    /** Ein einseitiges PDF mit den [lines], gezeichnet mit android.graphics.pdf.PdfDocument. */
    fun writePdf(file: File, lines: List<String>) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = 13f
                color = Color.BLACK
            }
            lines.forEachIndexed { i, l -> page.canvas.drawText(l, 56f, 80f + i * 22f, paint) }
            doc.finishPage(page)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun xmlEscape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Minimale, von Word lesbare .docx mit einem Absatz je Eintrag in [paragraphs]. */
    fun writeDocx(file: File, paragraphs: List<String>) {
        val body = paragraphs.joinToString("") { p ->
            "<w:p><w:pPr><w:pStyle w:val=\"Normal\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr>" +
                "<w:t xml:space=\"preserve\">${xmlEscape(p)}</w:t></w:r></w:p>"
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"$W_NS\"><w:body>$body<w:sectPr/></w:body></w:document>"
        val contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", document)
        }
    }
}
