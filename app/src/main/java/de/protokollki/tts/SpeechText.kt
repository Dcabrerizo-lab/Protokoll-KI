package de.protokollki.tts

import de.protokollki.data.Meeting
import de.protokollki.data.ProtocolMode
import java.time.Instant
import java.time.ZoneId

/*
 * Vorlesetext für [ReadAloud] – reine Logik ohne android.* (per JVM-Unit-Test prüfbar).
 * Daten, Uhrzeiten und gängige Abkürzungen werden so umgeschrieben, dass die Sprachausgabe sie
 * natürlich spricht („Fr., 27.11.2026“ → „Freitag, 27. November 2026“).
 */

/** Höchstlänge eines Vorlese-Abschnitts (TextToSpeech erlaubt ca. 4000 Zeichen). */
internal const val MAX_UTTERANCE = 3000

private val WEEKDAYS = arrayOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
private val MONTHS = arrayOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)
private val WEEKDAY_ABBR = mapOf(
    "Mo" to "Montag", "Di" to "Dienstag", "Mi" to "Mittwoch", "Do" to "Donnerstag",
    "Fr" to "Freitag", "Sa" to "Samstag", "So" to "Sonntag",
)

/**
 * Vorlese-Abschnitte (je ein Satz bzw. eine Überschrift, jeweils ≤ [MAX_UTTERANCE] Zeichen):
 * Titel, Datum, Zusammenfassung, Beschlüsse, Aufgaben („Frau Becker: Angebote einholen, bis
 * Freitag, 27. November.“). Im Wortprotokoll nur die Zusammenfassung, falls vorhanden, sonst
 * die Aufgaben.
 */
fun buildSpeechText(m: Meeting, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    val out = mutableListOf<String>()
    val created = Instant.ofEpochMilli(m.createdAt).atZone(zone)
    val year = created.year

    fun add(text: String) {
        val t = speakable(text, year).trim()
        if (t.isNotEmpty()) out += splitSentences(endSentence(t))
    }
    var hasContent = false

    add("${m.mode.label}: ${m.title.ifBlank { "Besprechung" }}")
    val date = "${WEEKDAYS[created.dayOfWeek.value - 1]}, ${created.dayOfMonth}. ${MONTHS[created.monthValue - 1]} " +
        "${created.year}, ${created.hour} Uhr" + (if (created.minute > 0) " ${created.minute}" else "")
    val duration = spokenDuration(m.durationSec)
    out += if (duration.isEmpty()) "$date." else "$date. Dauer: $duration."

    val overview = m.summary.overview.trim()
    val decisions = m.summary.decisions.map { it.trim() }.filter { it.isNotEmpty() }
    val tasks = m.tasks.filter { it.text.isNotBlank() }

    fun summary() {
        if (overview.isEmpty()) return
        hasContent = true
        out += "Zusammenfassung."
        add(overview)
    }

    fun tasks() {
        if (tasks.isEmpty()) return
        hasContent = true
        out += if (tasks.size == 1) "Eine Aufgabe." else "${tasks.size} Aufgaben."
        tasks.forEach { t ->
            val owner = t.owner.trim().takeUnless { it.isEmpty() || it.equals("offen", ignoreCase = true) }
            val due = t.due.trim().takeUnless { it.isEmpty() || it.equals("offen", ignoreCase = true) }
            val sb = StringBuilder()
            if (owner != null) sb.append(owner).append(": ")
            sb.append(t.text.trim().trimEnd('.', '!', ';', ','))
            if (due != null) {
                sb.append(", ")
                if (!due.startsWith("bis ", ignoreCase = true)) sb.append("bis ")
                sb.append(due)
            }
            if (t.done) sb.append(", erledigt")
            add(sb.toString())
        }
    }

    when (m.mode) {
        ProtocolMode.ERGEBNIS -> {
            summary()
            if (decisions.isNotEmpty()) {
                hasContent = true
                out += if (decisions.size == 1) "Ein Beschluss." else "${decisions.size} Beschlüsse."
                decisions.forEach(::add)
            }
            tasks()
        }
        ProtocolMode.WORT -> if (overview.isNotEmpty()) summary() else tasks()
    }

    if (!hasContent) out += "Für diese Besprechung gibt es noch keine Zusammenfassung und keine Aufgaben."
    return out
}

/** „45 Sekunden“, „12 Minuten“, „1 Stunde 5 Minuten“; leer bei 0. */
internal fun spokenDuration(sec: Float): String {
    val total = if (sec.isNaN() || sec <= 0f) 0 else Math.round(sec)
    if (total == 0) return ""
    if (total < 60) return if (total == 1) "1 Sekunde" else "$total Sekunden"
    val h = total / 3600
    val min = (total % 3600) / 60
    val hs = if (h == 1) "1 Stunde" else "$h Stunden"
    val ms = if (min == 1) "1 Minute" else "$min Minuten"
    return when {
        h == 0 -> ms
        min == 0 -> hs
        else -> "$hs $ms"
    }
}

// ------------------------------------------------------------------ Normalisierung

private val RE_WEEKDAY_DATE = Regex("(?<![\\p{L}])(Mo|Di|Mi|Do|Fr|Sa|So)\\.,?\\s*(?=\\d{1,2}\\.\\s?\\d{1,2}\\.)")
// Jahr: vierstellig (auch nach Leerzeichen) oder zweistellig direkt nach dem Punkt –
// sonst würde „27.11. 10:30“ zu „2010“.
private val RE_DATE = Regex("(?<![\\p{N}.])(\\d{1,2})\\.\\s?(\\d{1,2})\\.(?:\\s?(\\d{4})|(\\d{2}))?(?!\\p{N})")
private val RE_TIME = Regex("(?<![\\p{N}:.])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\p{N}:])(?:\\s?Uhr(?!\\p{L}))?")
private val RE_TOP = Regex("(?<!\\p{L})TOP(?=\\s*\\d)")
private val RE_MARKUP = Regex("[*_#`•|]+")
private val RE_SPACES = Regex("[ \\t]+")

/** Abkürzung → ausgeschrieben. Reihenfolge: längere zuerst. */
private val ABBREVIATIONS = listOf(
    "z. B." to "zum Beispiel", "z.B." to "zum Beispiel",
    "d. h." to "das heißt", "d.h." to "das heißt",
    "u. a." to "unter anderem", "u.a." to "unter anderem",
    "z. T." to "zum Teil", "z.T." to "zum Teil",
    "bzw." to "beziehungsweise", "ggf." to "gegebenenfalls", "evtl." to "eventuell",
    "ca." to "circa", "usw." to "und so weiter", "inkl." to "inklusive", "zzgl." to "zuzüglich",
    "Nr." to "Nummer", "Tel." to "Telefon", "Std." to "Stunden", "Min." to "Minuten",
    "Hr." to "Herr", "Dr." to "Doktor", "Prof." to "Professor",
).map { (abbr, full) ->
    // als ganzes Wort, Leerzeichen innerhalb flexibel
    Regex("(?<![\\p{L}])" + abbr.split(" ").joinToString("\\s*") { Regex.escape(it) }) to full
}
private val RE_FRAU = Regex("(?<![\\p{L}])Fr\\.\\s+(?=\\p{Lu})")

/**
 * Macht Text sprechbar: Wochentag-Abkürzungen, Daten („27.11.2026“ → „27. November 2026“; das
 * Jahr entfällt, wenn es [omitYear] ist), Uhrzeiten („10:30 Uhr“ → „10 Uhr 30“), Abkürzungen,
 * Markdown-Zeichen.
 */
fun speakable(text: String, omitYear: Int? = null): String {
    var t = text.replace('\n', ' ').replace('\r', ' ')
    t = RE_MARKUP.replace(t, " ")
    t = RE_WEEKDAY_DATE.replace(t) { WEEKDAY_ABBR.getValue(it.groupValues[1]) + ", " }
    t = RE_DATE.replace(t) { mr ->
        val day = mr.groupValues[1].toInt()
        val month = mr.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return@replace mr.value
        val y4 = mr.groupValues[3]
        val y2 = mr.groupValues[4]
        val year = when {
            y4.isNotEmpty() -> y4.toInt()
            y2.isNotEmpty() -> y2.toInt().let { if (it <= 69) 2000 + it else 1900 + it }
            else -> null
        }
        val base = "$day. ${MONTHS[month - 1]}"
        if (year == null || year == omitYear) base else "$base $year"
    }
    t = RE_TIME.replace(t) { mr ->
        val h = mr.groupValues[1].toInt()
        val min = mr.groupValues[2].toInt()
        if (min == 0) "$h Uhr" else "$h Uhr $min"
    }
    t = RE_TOP.replace(t, "Tagesordnungspunkt")
    t = RE_FRAU.replace(t, "Frau ")
    for ((re, full) in ABBREVIATIONS) t = re.replace(t, full)
    return RE_SPACES.replace(t, " ").trim()
}

private fun endSentence(t: String): String =
    if (t.isEmpty() || t.last() in ".!?:…") t else "$t."

/** Wörter, nach deren Punkt kein Satzende ist. */
private val NO_BREAK_AFTER = setOf("dr", "prof", "nr", "st", "abs", "art", "bzw", "ca", "vgl", "ggf", "evtl", "hr", "fr")

/**
 * Teilt Text in Sätze (nicht nach Ordnungszahlen wie „27. November“ oder Abkürzungen) und
 * zu lange Sätze an Wortgrenzen, sodass jeder Abschnitt höchstens [max] Zeichen hat.
 */
internal fun splitSentences(text: String, max: Int = MAX_UTTERANCE): List<String> {
    val sentences = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < text.length && text[i + 1].isWhitespace()) {
            var j = i + 1
            while (j < text.length && text[j].isWhitespace()) j++
            val next = if (j < text.length) text[j] else ' '
            var breakHere = next.isUpperCase() || next.isDigit() || next == '„' || next == '"'
            if (breakHere && c == '.') {
                var k = i - 1
                while (k >= start && text[k].isLetterOrDigit()) k--
                val word = text.substring(k + 1, i)
                if (word.isEmpty() || word.all { it.isDigit() } || word.length == 1 ||
                    word.lowercase() in NO_BREAK_AFTER
                ) breakHere = false
            }
            if (breakHere) {
                sentences += text.substring(start, i + 1).trim()
                start = j
                i = j
                continue
            }
        }
        i++
    }
    if (start < text.length) sentences += text.substring(start).trim()
    return sentences.filter { it.isNotEmpty() }.flatMap { hardWrap(it, max) }
}

private fun hardWrap(s: String, max: Int): List<String> {
    if (s.length <= max) return listOf(s)
    val parts = mutableListOf<String>()
    var rest = s
    while (rest.length > max) {
        var cut = rest.lastIndexOf(' ', max)
        if (cut <= max / 2) cut = max
        parts += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) parts += rest
    return parts
}
