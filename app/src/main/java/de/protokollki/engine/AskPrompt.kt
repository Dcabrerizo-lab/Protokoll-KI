package de.protokollki.engine

import de.protokollki.data.Meeting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prompt-Aufbau und Nachbearbeitung (rein, testbar). */
object AskPrompt {
    const val MAX_SOURCES = 8
    const val NOTHING_FOUND = "Dazu habe ich in Ihren Besprechungen nichts gefunden."

    const val SYSTEM = "Du beantwortest Fragen zu früheren Besprechungen. Du nutzt ausschließlich die " +
        "nummerierten Fundstellen und erfindest nichts. Du antwortest auf Deutsch, sachlich und knapp."

    private val MARKER = Regex("\\[(\\d{1,2})(?:\\s*[,;]\\s*(\\d{1,2}))*]")

    fun build(question: String, hits: List<SearchHit>, meetings: List<Meeting>): String {
        val byId = meetings.associateBy { it.id }
        val fmt = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN)
        val sources = hits.mapIndexed { i, h ->
            val m = byId[h.meetingId]
            val excerpt = if (m != null && h.segment >= 0) {
                // Satz davor und danach als Kontext
                (h.segment - 1..h.segment + 1).mapNotNull { j ->
                    m.segments.getOrNull(j)?.let { s -> "${m.speakerName(s.speakerId)}: ${s.text}" }
                }.joinToString(" / ")
            } else h.text
            "[${i + 1}] Besprechung „${h.title}“ vom ${fmt.format(Date(h.createdAt))}: $excerpt"
        }.joinToString("\n")
        return """
Frage: $question

Fundstellen:
$sources

Beantworte die Frage in 1 bis 4 Sätzen nur anhand der Fundstellen. Setze hinter jede Aussage die Nummer der belegenden Fundstelle in eckigen Klammern, z. B. [2]. Nenne bei Bedarf Datum oder Besprechung. Wenn die Fundstellen die Frage nicht beantworten, antworte genau: "$NOTHING_FOUND"
""".trim()
    }

    /** Entfernt Denk-Tags, Vorreden und ungültige Quellenmarker. */
    fun clean(raw: String, sourceCount: Int): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        s = s.removePrefix("Antwort:").trim()
        s = MARKER.replace(s) { m ->
            val nums = Regex("\\d{1,2}").findAll(m.value).map { it.value.toInt() }
                .filter { it in 1..sourceCount }.distinct().toList()
            nums.joinToString("") { "[$it]" }
        }
        s = s.replace(Regex(" +([.,;:!?])"), "$1").replace(Regex(" {2,}"), " ").trim()
        return s.ifEmpty { NOTHING_FOUND }
    }

    fun citations(answer: String): List<Int> =
        Regex("\\[(\\d{1,2})]").findAll(answer).map { it.groupValues[1].toInt() }.distinct().toList()
}
