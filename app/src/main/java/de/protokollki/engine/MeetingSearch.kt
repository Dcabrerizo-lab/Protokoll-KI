package de.protokollki.engine

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import kotlin.math.ln

/** Fundstelle in einer Besprechung. [segment] = Index in Meeting.segments, -1 = nur im Protokoll. */
data class SearchHit(
    val meetingId: String,
    val title: String,
    val createdAt: Long,
    val segment: Int,
    val start: Float,
    val speaker: String,
    val text: String,
    val score: Double,
)

/**
 * Volltextsuche über alle Besprechungen (BM25 mit einfacher deutscher Stammformbildung und
 * Teilwort-Treffern für Komposita, z. B. "Budget" findet "Budgetplanung"). Läuft sofort und offline.
 */
object MeetingSearch {

    private const val K1 = 1.2
    private const val B = 0.75
    private const val MAX_PER_MEETING = 6

    private val STOP = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "einen", "einem", "und",
        "oder", "aber", "ist", "sind", "war", "waren", "wird", "werden", "wurde", "wurden", "hat",
        "haben", "hatte", "ich", "du", "er", "sie", "es", "wir", "ihr", "mit", "von", "zu", "zum",
        "zur", "im", "in", "an", "am", "auf", "aus", "bei", "für", "fuer", "über", "ueber", "um",
        "was", "wer", "wie", "wo", "wann", "warum", "welche", "welcher", "welches", "nicht", "auch",
        "noch", "schon", "so", "dass", "denn", "dann", "als", "nach", "vor", "bis", "man", "sich",
        "uns", "unser", "unsere", "mal", "ja", "nein", "doch", "hier", "da", "gibt", "gab", "zuletzt",
        "letzte", "letzten", "besprochen", "gesprochen", "besprechung", "besprechungen", "sitzung",
        "beschlossen", "gesagt", "etwas", "alle", "allen", "kann", "können", "soll", "sollen",
    )

    internal fun tokens(text: String): List<String> =
        text.lowercase()
            .replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP }
            .map(::stem)

    /** Sehr einfache Stammform: häufige Flexionsendungen abschneiden. */
    internal fun stem(w: String): String {
        if (w.length <= 4 || w.all { it.isDigit() }) return w
        for (suf in listOf("ungen", "ern", "en", "er", "es", "em", "e", "n", "s")) {
            if (w.endsWith(suf) && w.length - suf.length >= 4) {
                return if (suf == "ungen") w.dropLast(2) else w.dropLast(suf.length)
            }
        }
        return w
    }

    private class Doc(val hit: SearchHit, val terms: List<String>, val context: List<String> = emptyList())

    fun search(meetings: List<Meeting>, query: String, limit: Int = 20): List<SearchHit> {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return emptyList()
        val docs = buildDocs(meetings)
        if (docs.isEmpty()) return emptyList()

        val avgLen = docs.sumOf { it.terms.size }.toDouble() / docs.size
        // Dokumenthäufigkeit je Suchbegriff (exakt oder als Wortbestandteil)
        val df = q.associateWith { t -> docs.count { d -> d.terms.any { it.contains(t) } } }
        val newest = meetings.maxOfOrNull { it.createdAt } ?: 0L
        val oldest = meetings.minOfOrNull { it.createdAt } ?: 0L
        val span = (newest - oldest).coerceAtLeast(1L).toDouble()

        val scored = docs.mapNotNull { d ->
            var score = 0.0
            var matched = 0
            for (t in q) {
                val exact = d.terms.count { it == t }
                val partial = d.terms.count { it != t && it.contains(t) }
                // Nachbarsatz zählt nur halb (Kontext, nicht die Fundstelle selbst)
                val ctx = d.context.count { it.contains(t) }
                val tf = exact + 0.6 * partial + 0.3 * ctx
                if (tf == 0.0) continue
                matched++
                val n = df[t] ?: 0
                val idf = ln(1 + (docs.size - n + 0.5) / (n + 0.5))
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * d.terms.size / avgLen))
            }
            if (matched == 0) null else {
                // Mehr getroffene Suchbegriffe zählen deutlich, neuere Besprechungen leicht mehr
                val coverage = matched.toDouble() / q.size
                val recency = (d.hit.createdAt - oldest) / span
                d.hit.copy(score = score * (0.5 + coverage) * (1 + 0.1 * recency))
            }
        }
        val perMeeting = HashMap<String, Int>()
        return scored.sortedByDescending { it.score }
            .filter { (perMeeting.merge(it.meetingId, 1, Int::plus) ?: 0) <= MAX_PER_MEETING }
            .distinctBy { it.meetingId to (if (it.segment >= 0) "s${it.segment}" else it.text) }
            .take(limit)
    }

    private fun buildDocs(meetings: List<Meeting>): List<Doc> {
        val out = ArrayList<Doc>()
        for (m in meetings) {
            if (m.status != MeetingStatus.DONE && m.segments.isEmpty()) continue
            fun hit(seg: Int, text: String, speaker: String = ""): SearchHit {
                val s = m.segments.getOrNull(seg)
                return SearchHit(m.id, m.title, m.createdAt, if (s != null) seg else -1,
                    s?.start ?: 0f, if (s != null) m.speakerName(s.speakerId) else speaker, text, 0.0)
            }
            m.segments.forEachIndexed { i, s ->
                // Folgesatz als Kontext mitindizieren (Fragen beziehen sich oft auf Antworten)
                out += Doc(hit(i, s.text), tokens(m.speakerName(s.speakerId) + " " + s.text),
                    tokens(m.segments.getOrNull(i + 1)?.text ?: ""))
            }
            val sum = m.summary
            sum.topics.forEach { t ->
                t.points.forEachIndexed { i, p ->
                    out += Doc(hit(t.refAt(i), "${t.title}: $p"), tokens("${t.title} $p"))
                }
            }
            sum.decisions.forEachIndexed { i, d ->
                out += Doc(hit(sum.decisionRefs.getOrElse(i) { -1 }, "Beschluss: $d"), tokens("beschluss $d"))
            }
            sum.openPoints.forEachIndexed { i, d ->
                out += Doc(hit(sum.openPointRefs.getOrElse(i) { -1 }, "Offen: $d"), tokens("offen $d"))
            }
            m.tasks.forEach { t ->
                val text = "Aufgabe: ${t.text} (${t.owner}, bis ${t.due})" + if (t.done) " – erledigt" else ""
                out += Doc(hit(t.ref, text), tokens("aufgabe ${t.text} ${t.owner} ${t.due}"))
            }
        }
        return out
    }
}
