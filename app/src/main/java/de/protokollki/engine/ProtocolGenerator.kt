package de.protokollki.engine

import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Abstraktion des Sprachmodells (für Tests austauschbar). */
interface TextModel {
    val contextSize: Int
    fun countTokens(text: String): Int
    fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                 grammar: String?, progress: LlmProgress?): String
}

class LocalLlmModel(private val llm: LocalLlm) : TextModel {
    override val contextSize get() = llm.contextSize
    override fun countTokens(text: String) = llm.countTokens(text)
    override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                          grammar: String?, progress: LlmProgress?) =
        llm.complete(system, user, maxTokens, temperature, grammar, progress)
}

/**
 * Erzeugt Ergebnisprotokoll und Aufgabenliste aus dem Transkript.
 * Lange Besprechungen werden abschnittsweise verdichtet (Map-Reduce).
 * Die Ausgaben sind per Grammatik auf gültiges JSON beschränkt.
 */
class ProtocolGenerator(private val model: TextModel) {

    /** progress(fraction 0..1, text) -> false = abbrechen */
    fun interface Progress { fun update(fraction: Float, text: String): Boolean }

    data class Result(val summary: Summary, val tasks: List<TaskItem>)

    fun generate(
        title: String,
        dateMillis: Long,
        segments: List<Segment>,
        speakers: List<Speaker>,
        wantSummary: Boolean,
        progress: Progress,
    ): Result {
        val names = speakers.associate { it.id to it.name }
        val lines = segments.map { "${names[it.speakerId] ?: "Person ${it.speakerId + 1}"}: ${it.text}" }
        val participants = segments.map { names[it.speakerId] ?: "Person ${it.speakerId + 1}" }.distinct()
        val date = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN).format(Date(dateMillis))
        val header = "Besprechung: $title\nDatum: $date\nTeilnehmende: ${participants.joinToString(", ")}"

        // Platz für Systemprompt, Anweisungen und Antwort freihalten
        val budget = (model.contextSize - 2800).coerceAtLeast(1500)
        val chunks = chunk(lines, budget)

        val material: String
        val tasks = mutableListOf<TaskItem>()
        val steps = (if (chunks.size > 1) chunks.size else 0) +
            (if (wantSummary) 1 else chunks.size)
        var step = 0
        fun prog(text: String, inner: Float = 0f): Boolean =
            progress.update(((step + inner) / steps).coerceIn(0f, 1f), text)

        if (chunks.size == 1 || !wantSummary) {
            material = "Transkript:\n" + chunks[0]
        } else {
            val notes = chunks.mapIndexed { i, c ->
                val label = "Fasse Abschnitt ${i + 1} von ${chunks.size} zusammen …"
                if (!prog(label)) throw LlmException("Abgebrochen")
                val n = model.complete(SYSTEM, NOTES_PROMPT.format(header, i + 1, chunks.size, c),
                    900, 0.2f, null, llmProgress { prog(label, it) })
                step++
                "Notizen zu Teil ${i + 1}:\n$n"
            }
            material = notes.joinToString("\n\n")
        }

        var summary = Summary()
        val meetingDate = java.time.Instant.ofEpochMilli(dateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (wantSummary) {
            // Ein Durchgang für Protokoll UND Aufgaben (halbiert die Rechenzeit)
            val label = "Schreibe Protokoll und Aufgaben …"
            if (!prog(label)) throw LlmException("Abgebrochen")
            val raw = model.complete(SYSTEM, SUMMARY_PROMPT.format(header, material),
                2200, 0.2f, SUMMARY_GRAMMAR, llmProgress { prog(label, it) })
            summary = parseSummary(raw)
            tasks += parseTasks(raw, participants)
            step++
        } else {
            for ((i, c) in chunks.withIndex()) {
                val label = if (chunks.size > 1) "Ermittle Aufgaben (${i + 1}/${chunks.size}) …"
                else "Ermittle Aufgaben …"
                if (!prog(label)) throw LlmException("Abgebrochen")
                val raw = model.complete(SYSTEM, TASKS_PROMPT.format(header, c),
                    1200, 0.0f, TASKS_GRAMMAR, llmProgress { prog(label, it) })
                tasks += parseTasks(raw, participants)
                step++
            }
        }
        val resolved = tasks.map { it.copy(due = DateResolver.resolve(it.due, meetingDate)) }
        progress.update(1f, "Fertig")
        return Result(summary, dedupe(resolved))
    }

    private fun llmProgress(onFraction: (Float) -> Boolean) = LlmProgress { phase, done, total ->
        // Lesen ~40 %, Schreiben ~60 % (grobe Schätzung)
        val f = if (phase == 0) 0.4f * done / total.coerceAtLeast(1)
        else 0.4f + 0.6f * (done.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f)
        onFraction(f)
    }

    /** Teilt Zeilen so auf, dass jeder Teil höchstens [budget] Tokens hat. */
    internal fun chunk(lines: List<String>, budget: Int): List<String> {
        val all = lines.joinToString("\n")
        if (model.countTokens(all) <= budget) return listOf(all)
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        var curTokens = 0
        for (line in lines) {
            val t = model.countTokens(line) + 1
            if (curTokens + t > budget && cur.isNotEmpty()) {
                chunks += cur.toString().trim(); cur.clear(); curTokens = 0
            }
            cur.append(line).append('\n'); curTokens += t
        }
        if (cur.isNotEmpty()) chunks += cur.toString().trim()
        return chunks
    }

    companion object {
        const val SYSTEM = "Du bist ein erfahrener, sorgfältiger Protokollführer in deutschen " +
            "Unternehmen und Einrichtungen. Du schreibst sachlich, präzise und in korrektem " +
            "Deutsch. Du hältst dich strikt an das Transkript und erfindest nichts. Erkennbare " +
            "Fehler der automatischen Spracherkennung korrigierst du stillschweigend sinngemäß."

        val NOTES_PROMPT = """
%s

Hier ist Teil %d von %d des Transkripts. Notiere knapp in Stichpunkten:
- besprochene Themen mit den wesentlichen Aussagen (wer vertritt welche Position),
- Entscheidungen und Beschlüsse,
- offene Fragen,
- vereinbarte Aufgaben (wer, was, bis wann).
Keine Einleitung.

Transkript:
%s
""".trimIndent()

        private const val TASK_RULES = """Regeln für "aufgaben" (alles, was jemand erledigen soll oder zugesagt hat):
- "aufgabe": kurz und konkret, mit dem Verb am Ende (z. B. "Drei Angebote für Möbel einholen").
- "verantwortlich": Name der zuständigen Person, wie er im Gespräch fällt (wird jemand mit "Frau X" oder "Herr Y" angesprochen, diesen Namen verwenden). Sagt jemand "mache ich", ist es die sprechende Person. Unklar: "offen".
- "frist": die Zeitangabe GENAU so, wie sie im Gespräch genannt wurde (z. B. "Freitag", "Ende Januar", "15. Januar", "nächste Sitzung"). NICHT selbst in ein Datum umrechnen. Keine Angabe: "offen".
- Nur echte Aufgaben, nichts erfinden."""

        val SUMMARY_PROMPT = """
%s

Erstelle aus dem folgenden Material ein professionelles Ergebnisprotokoll als JSON:
- "zusammenfassung": 3 bis 5 Sätze, worum es ging und was herauskam.
- "themen": die besprochenen Themen in zeitlicher Reihenfolge. Je Thema ein kurzer "titel" und 2 bis 6 "punkte" mit Ergebnissen, Argumenten und Zahlen (vollständige, knappe Sätze).
- "beschluesse": Entscheidungen, die die Runde getroffen hat (z. B. "Ab März wird ..."). Reine Aufgabenverteilungen gehören NICHT hierher, sondern zu "aufgaben". Leere Liste, wenn keine.
- "offene_punkte": ungeklärte Fragen und vertagte Themen. Leere Liste, wenn keine.
- "aufgaben": siehe Regeln.

""".trimIndent() + "\n" + TASK_RULES + "\n\n%s"

        val TASKS_PROMPT = """
%s

Extrahiere alle Aufgaben, die in diesem Gespräch vereinbart, zugesagt oder jemandem zugewiesen wurden, als JSON {"aufgaben": [...]}. Keine Aufgaben: {"aufgaben": []}

""".trimIndent() + "\n" + TASK_RULES + "\n\nTranskript:\n%s"

        private const val JSON_STRING =
            "string ::= \"\\\"\" ( [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4}) )* \"\\\"\"\n" +
                "ws ::= [ \\t\\n]{0,20}\n" +
                "strlist ::= \"[\" ws ( string ws (\",\" ws string ws)* )? \"]\"\n"

        val SUMMARY_GRAMMAR = """
root ::= "{" ws "\"zusammenfassung\":" ws string ws "," ws "\"themen\":" ws themen ws "," ws "\"beschluesse\":" ws strlist ws "," ws "\"offene_punkte\":" ws strlist ws "," ws "\"aufgaben\":" ws tasks ws "}"
tasks ::= "[" ws ( task ws ("," ws task ws)* )? "]"
task ::= "{" ws "\"aufgabe\":" ws string ws "," ws "\"verantwortlich\":" ws string ws "," ws "\"frist\":" ws string ws "}"
themen ::= "[" ws ( thema ws ("," ws thema ws)* )? "]"
thema ::= "{" ws "\"titel\":" ws string ws "," ws "\"punkte\":" ws strlist ws "}"
""".trimIndent() + "\n" + JSON_STRING

        val TASKS_GRAMMAR = """
root ::= "{" ws "\"aufgaben\":" ws "[" ws ( task ws ("," ws task ws)* )? "]" ws "}"
task ::= "{" ws "\"aufgabe\":" ws string ws "," ws "\"verantwortlich\":" ws string ws "," ws "\"frist\":" ws string ws "}"
""".trimIndent() + "\n" + JSON_STRING

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList()
            else (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }

        private fun jsonObject(raw: String): JSONObject {
            val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
            if (s < 0 || e <= s) throw LlmException("Ungültige Antwort des Sprachmodells")
            return JSONObject(raw.substring(s, e + 1))
        }

        fun parseSummary(raw: String): Summary {
            val o = jsonObject(raw)
            val themen = o.optJSONArray("themen") ?: JSONArray()
            return Summary(
                overview = o.optString("zusammenfassung").trim(),
                topics = (0 until themen.length()).mapNotNull {
                    val t = themen.optJSONObject(it) ?: return@mapNotNull null
                    Topic(t.optString("titel").trim(), t.optJSONArray("punkte").strings())
                }.filter { it.title.isNotEmpty() },
                decisions = o.optJSONArray("beschluesse").strings(),
                openPoints = o.optJSONArray("offene_punkte").strings(),
            )
        }

        fun parseTasks(raw: String, participants: List<String> = emptyList()): List<TaskItem> {
            val arr = jsonObject(raw).optJSONArray("aufgaben") ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                val t = arr.optJSONObject(it) ?: return@mapNotNull null
                val text = t.optString("aufgabe").trim()
                if (text.isEmpty()) return@mapNotNull null
                fun norm(v: String) = v.trim().ifEmpty { "offen" }.let {
                    if (it.equals("unbekannt", true) || it == "-" || it.equals("n/a", true)) "offen" else it
                }
                TaskItem(text, norm(t.optString("verantwortlich")), norm(t.optString("frist")))
            }
        }

        /** Entfernt doppelte Aufgaben (gleicher Kern-Text). */
        fun dedupe(tasks: List<TaskItem>): List<TaskItem> {
            fun key(t: TaskItem) = t.text.lowercase().replace(Regex("[^a-zäöüß0-9 ]"), "")
                .split(" ").filter { it.length > 3 }.toSet()
            val out = mutableListOf<TaskItem>()
            for (t in tasks) {
                val k = key(t)
                val dup = out.indexOfFirst {
                    val o = key(it)
                    val inter = (k intersect o).size
                    val union = (k union o).size.coerceAtLeast(1)
                    inter.toFloat() / union >= 0.6f
                }
                if (dup < 0) out += t
                else if (out[dup].owner == "offen" && t.owner != "offen") out[dup] = t
            }
            return out
        }
    }
}
