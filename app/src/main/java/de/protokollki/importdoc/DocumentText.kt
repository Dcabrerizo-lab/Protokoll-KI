package de.protokollki.importdoc

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * Reine Logik für den Import alter Protokolle: Text aus Word (.docx) und Textdateien lesen,
 * in Absätze zerlegen und daraus eine durchsuchbare Besprechung bauen (ohne Audio).
 */
object DocumentText {

    const val SPEAKER_NAME = "Protokoll"
    private const val MAX_PARAGRAPH = 700

    /** Liest den Fließtext aus einer .docx-Datei (word/document.xml), Absatz für Absatz. */
    fun fromDocx(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "word/document.xml") {
                    return docxXmlToText(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        throw IllegalArgumentException("Die Word-Datei enthält keinen lesbaren Text.")
    }

    internal fun docxXmlToText(xml: String): String {
        val out = StringBuilder()
        // Absätze <w:p>…</w:p>, darin Texte <w:t>, Tabs und Zeilenumbrüche
        Regex("<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { p ->
            val para = StringBuilder()
            Regex("<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL)
                .findAll(p.value).forEach { m ->
                    when {
                        m.value.startsWith("<w:tab") -> para.append('\t')
                        m.value.startsWith("<w:br") -> para.append('\n')
                        else -> para.append(unescapeXml(m.groupValues[1]))
                    }
                }
            out.append(para).append('\n')
        }
        return out.toString()
    }

    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Text robust dekodieren (UTF-8, sonst Windows-1252 wie bei alten Word-/Editor-Dateien). */
    fun decode(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('�')) bytes.toString(charset("windows-1252")) else utf8.removePrefix("﻿")
    }

    /** Zerlegt Text in sinnvolle Absätze; Silbentrennung und harte Zeilenumbrüche aus PDFs werden repariert. */
    fun paragraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")         // Silbentrennung am Zeilenende
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
        val out = mutableListOf<String>()
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            // Aufzählungen und kurze Überschriften bleiben eigene Zeilen, Fließtext wird verbunden
            val cur = StringBuilder()
            for (l in lines) {
                val startsItem = l.matches(Regex("^([-•*–]|\\d{1,2}[.)]|TOP\\s*\\d+).*"))
                val looksHeading = l.length <= 60 && !l.endsWith(".") && !l.endsWith(",") && cur.isEmpty()
                if (cur.isNotEmpty() && (startsItem || cur.endsWith(":"))) {
                    out += cur.toString(); cur.clear()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(l)
                if (looksHeading && lines.size > 1) { out += cur.toString(); cur.clear() }
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        // Sehr lange Absätze an Satzgrenzen teilen (bessere Fundstellen)
        return out.flatMap { splitLong(it) }.map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }
    }

    private fun splitLong(p: String): List<String> {
        if (p.length <= MAX_PARAGRAPH) return listOf(p)
        val sentences = p.split(Regex("(?<=[.!?])\\s+"))
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length > MAX_PARAGRAPH) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private val MONTHS = listOf("januar", "februar", "märz", "april", "mai", "juni", "juli",
        "august", "september", "oktober", "november", "dezember")

    /** Sucht das Sitzungsdatum im Kopf des Dokuments ("vom 12.03.2025", "12. März 2025"). */
    fun findDate(text: String): LocalDate? {
        val head = text.take(1500)
        Regex("(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d)").find(head)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            runCatching { return LocalDate.of(year, mo.toInt(), d.toInt()) }
        }
        Regex("(\\d{1,2})\\.\\s*(${MONTHS.joinToString("|")})\\s+(\\d{4})", RegexOption.IGNORE_CASE)
            .find(head)?.let { m ->
                val (d, mo, y) = m.destructured
                runCatching { return LocalDate.of(y.toInt(), MONTHS.indexOf(mo.lowercase()) + 1, d.toInt()) }
            }
        return null
    }

    /** Titel: erste aussagekräftige Zeile, sonst der Dateiname ohne Endung. */
    fun findTitle(paragraphs: List<String>, fileName: String): String {
        val fromFile = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val first = paragraphs.take(3).firstOrNull {
            it.length in 4..80 && it.any(Char::isLetter) && !it.endsWith(".") && !it.startsWith("-")
        }
        return (first ?: fromFile).trimEnd(':').ifBlank { "Importiertes Protokoll" }
    }

    /** Baut aus dem Dokumenttext eine Besprechung, die anschließend vom Sprachmodell ausgewertet wird. */
    fun toMeeting(id: String, fileName: String, text: String, now: Long,
                  zone: ZoneId = ZoneId.systemDefault()): Meeting {
        val paras = paragraphs(text)
        require(paras.isNotEmpty()) { "In der Datei wurde kein Text gefunden." }
        val date = findDate(text)
        val created = date?.atTime(12, 0)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: now
        return Meeting(
            id = id,
            title = findTitle(paras, fileName),
            createdAt = created,
            audioFile = "",
            status = MeetingStatus.RECORDED,
            mode = ProtocolMode.ERGEBNIS,
            segments = paras.map { Segment(0, 0f, 0f, it) },
            speakers = listOf(Speaker(0, SPEAKER_NAME)),
            audioDeleted = true,
            importedFrom = fileName,
        )
    }
}
