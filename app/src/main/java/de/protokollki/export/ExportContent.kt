package de.protokollki.export

import de.protokollki.data.Meeting
import de.protokollki.data.ProtocolMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Formatunabhängiger Inhalt des Protokolls + Text-/Markdown-Ausgabe.
 * Keine android.*-Abhängigkeiten (per JVM-Unit-Test prüfbar).
 */

internal const val FOOTER_NOTE = "Erstellt mit Protokoll KI – vollständig offline auf dem Gerät verarbeitet."

internal sealed class Block {
    /** Kopf: Protokollart (z. B. „Ergebnisprotokoll“) + Titel der Besprechung. */
    data class Title(val kind: String, val title: String) : Block()
    data class Meta(val label: String, val value: String) : Block()
    object Divider : Block()
    data class Heading(val text: String) : Block()
    data class SubHeading(val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    data class Bullet(val text: String) : Block()
    data class Numbered(val number: Int, val text: String) : Block()
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        /** relative Spaltenbreiten, Summe 1 */
        val weights: List<Float>,
    ) : Block()
    data class Utterance(val speaker: String, val time: String, val text: String) : Block()
    data class Note(val text: String) : Block()
}

internal val TASK_HEADER = listOf("Nr.", "Aufgabe", "Verantwortlich", "Frist", "Erledigt")
internal val TASK_WEIGHTS = listOf(0.07f, 0.45f, 0.2f, 0.16f, 0.12f)

internal const val AGENDA_HEADING = "Tagesordnung"
internal const val FOLLOWUP_HEADING = "Stand offener Aufgaben aus früheren Besprechungen"
internal val FOLLOWUP_HEADER = listOf("Aufgabe", "Verantwortlich", "Stand")
internal val FOLLOWUP_WEIGHTS = listOf(0.58f, 0.24f, 0.18f)

/** Teilnehmende in Reihenfolge des ersten Redebeitrags; ohne Transkript die Sprecherliste. */
internal fun participants(m: Meeting): List<String> {
    val fromSegments = m.segments.map { it.speakerId }.distinct().map { m.speakerName(it) }
    val names = fromSegments.ifEmpty { m.speakers.map { it.name } }
    return names.filter { it.isNotBlank() }.distinct()
}

internal fun formatDate(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
    val f = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY)
    f.timeZone = tz
    return f.format(Date(epochMs)) + " Uhr"
}

/** Dauer lesbar: „45 Sek.“, „12 Min.“, „1 Std. 5 Min.“. */
internal fun formatDuration(sec: Float): String {
    val total = if (sec.isNaN() || sec < 0f) 0 else Math.round(sec)
    if (total < 60) return "$total Sek."
    val h = total / 3600
    val min = (total % 3600) / 60
    return if (h > 0) "$h Std. $min Min." else "$min Min."
}

/** Zeitstempel im Wortlaut: „mm:ss“, ab einer Stunde „h:mm:ss“. */
internal fun formatTimestamp(sec: Float): String {
    val total = if (sec.isNaN() || sec < 0f) 0 else sec.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/** Dateiname aus dem Titel (ohne Endung). */
internal fun sanitizeFileName(title: String): String {
    val cleaned = title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ' ', '_')
    val cut = if (cleaned.length > 80) cleaned.substring(0, 80).trim() else cleaned
    return cut.ifBlank { "Protokoll" }
}

internal fun taskRows(m: Meeting): List<List<String>> = m.tasks.mapIndexed { i, t ->
    listOf(
        "${i + 1}",
        t.text,
        t.owner.ifBlank { "offen" },
        t.due.ifBlank { "offen" },
        if (t.done) "✓" else "",
    )
}

internal fun followUpRows(m: Meeting): List<List<String>> =
    m.followUps.filter { it.text.isNotBlank() }.map { f ->
        listOf(
            f.text.trim(),
            f.owner.trim().ifBlank { "offen" },
            if (f.done) "erledigt" else "offen",
        )
    }

/** Baut die Abschnitte des Protokolls. */
internal fun buildBlocks(m: Meeting, tz: TimeZone = TimeZone.getDefault()): List<Block> {
    val b = mutableListOf<Block>()
    b += Block.Title(m.mode.label, m.title.ifBlank { "Besprechung" })
    b += Block.Meta("Datum", formatDate(m.createdAt, tz))
    b += Block.Meta("Dauer", formatDuration(m.durationSec))
    b += Block.Meta("Teilnehmende", participants(m).joinToString(", ").ifBlank { "–" })
    b += Block.Divider

    val agenda = m.agenda.map { it.trim() }.filter { it.isNotEmpty() }
    if (agenda.isNotEmpty()) {
        b += Block.Heading(AGENDA_HEADING)
        agenda.forEachIndexed { i, a -> b += Block.Numbered(i + 1, a) }
    }

    fun tasks() {
        b += Block.Heading("Aufgaben")
        if (m.tasks.isEmpty()) b += Block.Paragraph("Keine Aufgaben erfasst.")
        else b += Block.Table(TASK_HEADER, taskRows(m), TASK_WEIGHTS)
        val follow = followUpRows(m)
        if (follow.isNotEmpty()) {
            b += Block.Heading(FOLLOWUP_HEADING)
            b += Block.Table(FOLLOWUP_HEADER, follow, FOLLOWUP_WEIGHTS)
        }
    }

    when (m.mode) {
        ProtocolMode.ERGEBNIS -> {
            val s = m.summary
            b += Block.Heading("Zusammenfassung")
            b += Block.Paragraph(s.overview.trim().ifBlank { "Noch keine Zusammenfassung vorhanden." })

            b += Block.Heading("Besprochene Themen")
            if (s.topics.isEmpty()) b += Block.Paragraph("Keine")
            s.topics.forEach { t ->
                b += Block.SubHeading(t.title.ifBlank { "Thema" })
                t.points.filter { it.isNotBlank() }.forEach { b += Block.Bullet(it.trim()) }
            }

            b += Block.Heading("Beschlüsse")
            val decisions = s.decisions.filter { it.isNotBlank() }
            if (decisions.isEmpty()) b += Block.Paragraph("Keine")
            decisions.forEachIndexed { i, d -> b += Block.Numbered(i + 1, d.trim()) }

            b += Block.Heading("Offene Punkte")
            val open = s.openPoints.filter { it.isNotBlank() }
            if (open.isEmpty()) b += Block.Paragraph("Keine")
            open.forEach { b += Block.Bullet(it.trim()) }

            tasks()
        }
        ProtocolMode.WORT -> {
            tasks()
            b += Block.Heading("Wortlaut")
            if (m.segments.isEmpty()) b += Block.Paragraph("Kein Transkript vorhanden.")
            m.segments.filter { it.text.isNotBlank() }.forEach { seg ->
                b += Block.Utterance(m.speakerName(seg.speakerId), formatTimestamp(seg.start), seg.text.trim())
            }
        }
    }
    b += Block.Note(FOOTER_NOTE)
    return b
}

// ------------------------------------------------------------------ Text / Markdown

internal fun renderPlainText(blocks: List<Block>): String {
    val sb = StringBuilder()
    for (bl in blocks) {
        when (bl) {
            is Block.Title -> {
                sb.append(bl.kind.uppercase(Locale.GERMANY)).append('\n')
                sb.append(bl.title).append('\n')
                sb.append("=".repeat(bl.title.length.coerceIn(10, 60))).append("\n\n")
            }
            is Block.Meta -> sb.append(bl.label).append(": ").append(bl.value).append('\n')
            Block.Divider -> sb.append('\n')
            is Block.Heading -> {
                trimBlankLines(sb)
                sb.append("\n\n").append(bl.text.uppercase(Locale.GERMANY)).append('\n')
                sb.append("-".repeat(bl.text.length)).append('\n')
            }
            is Block.SubHeading -> sb.append('\n').append(bl.text).append('\n')
            is Block.Paragraph -> sb.append(bl.text).append('\n')
            is Block.Bullet -> sb.append("• ").append(bl.text).append('\n')
            is Block.Numbered -> sb.append(bl.number).append(". ").append(bl.text).append('\n')
            is Block.Table -> if (bl.header == TASK_HEADER) {
                bl.rows.forEach { r ->
                    // Aufgaben-Tabelle als gut lesbare Liste
                    sb.append(r.getOrElse(0) { "" }).append(". ").append(r.getOrElse(1) { "" }).append('\n')
                    sb.append("   Verantwortlich: ").append(r.getOrElse(2) { "" })
                        .append(" · Frist: ").append(r.getOrElse(3) { "" })
                    if (r.getOrElse(4) { "" }.isNotBlank()) sb.append(" · erledigt ✓")
                    sb.append('\n')
                }
            } else {
                // Andere Tabellen: erste Spalte als Listenpunkt, Rest als „Kopf: Wert“
                bl.rows.forEach { r ->
                    sb.append("• ").append(r.getOrElse(0) { "" })
                    for (i in 1 until bl.header.size) {
                        val v = r.getOrElse(i) { "" }
                        if (v.isNotBlank()) sb.append(" · ").append(bl.header[i]).append(": ").append(v)
                    }
                    sb.append('\n')
                }
            }
            is Block.Utterance -> sb.append(bl.speaker).append(" [").append(bl.time).append("]: ")
                .append(bl.text).append("\n\n")
            is Block.Note -> {
                trimBlankLines(sb)
                sb.append("\n\n---\n").append(bl.text).append('\n')
            }
        }
    }
    return sb.toString().trimEnd() + "\n"
}

private fun trimBlankLines(sb: StringBuilder) {
    while (sb.isNotEmpty() && (sb[sb.length - 1] == '\n' || sb[sb.length - 1] == ' ')) {
        sb.setLength(sb.length - 1)
    }
}

/** Markdown-Sonderzeichen in Fließtext entschärfen (nur die kritischen). */
internal fun mdEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '\\', '*', '_', '`', '[', ']', '<', '>', '#', '|' -> sb.append('\\').append(c)
            '\n', '\r' -> sb.append(' ')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

internal fun renderMarkdown(blocks: List<Block>): String {
    val sb = StringBuilder()
    var lastWasList = false
    fun endList() {
        if (lastWasList) sb.append('\n')
        lastWasList = false
    }
    for (bl in blocks) {
        when (bl) {
            is Block.Title -> {
                sb.append("# ").append(mdEscape(bl.kind)).append(": ").append(mdEscape(bl.title)).append("\n\n")
            }
            is Block.Meta -> {
                sb.append("**").append(mdEscape(bl.label)).append(":** ").append(mdEscape(bl.value)).append("  \n")
            }
            Block.Divider -> { endList(); sb.append("\n---\n\n") }
            is Block.Heading -> { endList(); sb.append("## ").append(mdEscape(bl.text)).append("\n\n") }
            is Block.SubHeading -> { endList(); sb.append("### ").append(mdEscape(bl.text)).append("\n\n") }
            is Block.Paragraph -> { endList(); sb.append(mdEscape(bl.text)).append("\n\n") }
            is Block.Bullet -> { sb.append("- ").append(mdEscape(bl.text)).append('\n'); lastWasList = true }
            is Block.Numbered -> {
                sb.append(bl.number).append(". ").append(mdEscape(bl.text)).append('\n'); lastWasList = true
            }
            is Block.Table -> {
                endList()
                sb.append("| ").append(bl.header.joinToString(" | ") { mdEscape(it) }).append(" |\n")
                sb.append("|").append(bl.header.joinToString("|") { "---" }).append("|\n")
                bl.rows.forEach { r ->
                    sb.append("| ").append(r.joinToString(" | ") { mdEscape(it) }).append(" |\n")
                }
                sb.append('\n')
            }
            is Block.Utterance -> {
                endList()
                sb.append("**").append(mdEscape(bl.speaker)).append("** [").append(bl.time).append("]: ")
                    .append(mdEscape(bl.text)).append("\n\n")
            }
            is Block.Note -> { endList(); sb.append("---\n\n*").append(mdEscape(bl.text)).append("*\n") }
        }
    }
    return sb.toString()
}
