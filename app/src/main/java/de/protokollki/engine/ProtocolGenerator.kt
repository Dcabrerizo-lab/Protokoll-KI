package de.protokollki.engine

import de.protokollki.data.ProtocolTemplate
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
 * Erzeugt Ergebnisprotokoll, Aufgaben, Quellenbelege, Aufgaben-Abgleich und
 * sensible Begriffe aus dem Transkript. Lange Besprechungen werden abschnittsweise
 * verdichtet (Map-Reduce). Ausgaben sind per Grammatik auf gültiges JSON beschränkt.
 */
class ProtocolGenerator(private val model: TextModel) {

    /** progress(fraction 0..1, text) -> false = abbrechen */
    fun interface Progress { fun update(fraction: Float, text: String): Boolean }

    /** Offene Aufgabe aus einer früheren Besprechung, die abgeglichen werden soll. */
    data class OpenTask(val key: String, val taskId: String, val meetingId: String,
                        val text: String, val owner: String)

    data class Result(
        val summary: Summary,
        val tasks: List<TaskItem>,
        val completed: List<OpenTask> = emptyList(),   // laut Gespräch erledigt
        val completedRefs: Map<String, Int> = emptyMap(),
        val sensitiveTerms: List<String> = emptyList(),
    )

    fun generate(
        title: String,
        dateMillis: Long,
        segments: List<Segment>,
        speakers: List<Speaker>,
        wantSummary: Boolean,
        agenda: List<String> = emptyList(),
        openTasks: List<OpenTask> = emptyList(),
        template: ProtocolTemplate = ProtocolTemplate.ALLGEMEIN,
        vocabulary: List<String> = emptyList(),
        progress: Progress,
    ): Result {
        val names = speakers.associate { it.id to it.name }
        fun nameOf(id: Int) = names[id] ?: "Person ${id + 1}"
        val lines = segments.mapIndexed { i, s -> "[$i] ${nameOf(s.speakerId)}: ${s.text}" }
        val participants = segments.map { nameOf(it.speakerId) }.distinct()
        val date = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN).format(Date(dateMillis))
        val header = buildString {
            append("Besprechung: $title\nDatum: $date\nTeilnehmende: ${participants.joinToString(", ")}")
            if (template != ProtocolTemplate.ALLGEMEIN) append("\nArt: ${template.label}")
            if (vocabulary.isNotEmpty()) {
                append("\nRichtige Schreibweise von Namen und Fachbegriffen: ")
                append(vocabulary.take(60).joinToString(", "))
            }
            if (agenda.isNotEmpty()) {
                append("\nTagesordnung:\n")
                agenda.forEachIndexed { i, t -> append("TOP ${i + 1}: $t\n") }
            }
            if (openTasks.isNotEmpty()) {
                append("\nOffene Aufgaben aus früheren Besprechungen:\n")
                openTasks.forEach { append("${it.key}: ${it.text} (${it.owner})\n") }
            }
        }.trimEnd()

        // Platz für Systemprompt, Anweisungen und Antwort freihalten
        val reserve = 3000 + model.countTokens(header)
        val budget = (model.contextSize - reserve).coerceAtLeast(1500)
        val chunks = chunk(lines, budget)

        val steps = (if (chunks.size > 1 && wantSummary) chunks.size else 0) +
            (if (wantSummary) 1 else chunks.size)
        var step = 0
        fun prog(text: String, inner: Float = 0f): Boolean =
            progress.update(((step + inner) / steps).coerceIn(0f, 1f), text)

        val extras = extraRules(agenda.isNotEmpty(), openTasks.isNotEmpty(), template)
        val raws = mutableListOf<String>()
        if (wantSummary) {
            val material = if (chunks.size == 1) "Transkript:\n" + chunks[0] else {
                chunks.mapIndexed { i, c ->
                    val label = "Fasse Abschnitt ${i + 1} von ${chunks.size} zusammen …"
                    if (!prog(label)) throw LlmException("Abgebrochen")
                    val n = model.complete(SYSTEM, NOTES_PROMPT.format(header, i + 1, chunks.size, c),
                        1000, 0.2f, null, llmProgress { prog(label, it) })
                    step++
                    "Notizen zu Teil ${i + 1}:\n$n"
                }.joinToString("\n\n")
            }
            // Ein Durchgang für Protokoll UND Aufgaben (halbiert die Rechenzeit)
            val label = "Schreibe Protokoll und Aufgaben …"
            if (!prog(label)) throw LlmException("Abgebrochen")
            raws += model.complete(SYSTEM, SUMMARY_PROMPT.format(header, extras, material),
                2600, 0.2f, SUMMARY_GRAMMAR, llmProgress { prog(label, it) })
            step++
        } else {
            for ((i, c) in chunks.withIndex()) {
                val label = if (chunks.size > 1) "Ermittle Aufgaben (${i + 1}/${chunks.size}) …"
                else "Ermittle Aufgaben …"
                if (!prog(label)) throw LlmException("Abgebrochen")
                raws += model.complete(SYSTEM, TASKS_PROMPT.format(header, extras, c),
                    1400, 0.0f, TASKS_GRAMMAR, llmProgress { prog(label, it) })
                step++
            }
        }

        // Kleine Modelle lassen im kombinierten Durchgang manchmal die Aufgaben weg:
        // dann gezielt nachfassen (nur Aufgaben, je Abschnitt).
        if (wantSummary && raws.none { parseTasks(it, segments.size).isNotEmpty() }) {
            for (c in chunks) {
                val label = "Prüfe Aufgaben …"
                if (!progress.update(0.97f, label)) throw LlmException("Abgebrochen")
                raws += model.complete(SYSTEM, TASKS_PROMPT.format(header, extras, c),
                    1400, 0.0f, TASKS_GRAMMAR, null)
            }
        }

        val meetingDate = java.time.Instant.ofEpochMilli(dateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val n = segments.size
        val summary = if (wantSummary) parseSummary(raws.first(), n) else Summary()
        val tasks = raws.flatMap { parseTasks(it, n) }
            .map { it.copy(due = DateResolver.resolve(it.due, meetingDate)) }
            .map { it.copy(owner = resolveOwner(it.owner, it.ref, segments, ::nameOf)) }
        val doneKeys = mutableMapOf<String, Int>()
        raws.forEach { r -> parseCompleted(r, n).forEach { (k, ref) -> doneKeys.putIfAbsent(k, ref) } }
        val completed = openTasks.filter { it.key in doneKeys }
        val sensitive = raws.flatMap { parseSensitive(it, participants) }.distinctBy { it.lowercase() }
        progress.update(1f, "Fertig")
        return Result(summary, dedupe(tasks), completed,
            completed.associate { it.taskId to (doneKeys[it.key] ?: -1) }, sensitive)
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
- vereinbarte Aufgaben (wer, was, bis wann),
- Aussagen, dass frühere Aufgaben erledigt sind,
- Namen von Klienten, Patienten oder Bewohnern sowie Adressen und Diagnosen.
Behalte hinter jedem Stichpunkt die Zeilennummer aus dem Transkript in eckigen Klammern bei, z. B. [12]. Keine Einleitung.

Transkript:
%s
""".trimIndent()

        private const val CITE_RULE = """Quellenangabe: Setze an das Ende JEDES Punkts, Beschlusses, offenen Punkts und jeder Aufgabe die Nummer der Transkriptzeile, die ihn belegt, in eckigen Klammern, z. B. "Ab März wird die Tour Nord geteilt. [8]"."""

        private const val TASK_RULES = """Regeln für "aufgaben" (alles, was jemand erledigen soll oder zugesagt hat):
- "aufgabe": kurz und konkret, mit dem Verb am Ende (z. B. "Drei Angebote für Möbel einholen [3]").
- "verantwortlich": Name der zuständigen Person, wie er im Gespräch fällt (wird jemand mit "Frau X" oder "Herr Y" angesprochen, diesen Namen verwenden). Sagt jemand "mache ich", ist es die sprechende Person. Unklar: "offen".
- "frist": die Zeitangabe GENAU so, wie sie im Gespräch genannt wurde (z. B. "Freitag", "Ende Januar", "15. Januar", "nächste Sitzung"). NICHT selbst in ein Datum umrechnen. Keine Angabe: "offen".
- Nur echte Aufgaben, nichts erfinden.
Regeln für "sensible_daten": Namen von Klienten, Patienten, Bewohnern oder anderen Personen, über die gesprochen wird (NICHT die Teilnehmenden), sowie Adressen, Telefonnummern, Geburtsdaten und Diagnosen, jeweils genau so geschrieben wie im Transkript. Leere Liste, wenn keine."""

        private const val AGENDA_RULE = """Tagesordnung: Gliedere "themen" nach der Tagesordnung, Titel jeweils "TOP 1: …", "TOP 2: …". Nicht besprochene TOPs trotzdem aufführen mit dem einzigen Punkt "Nicht besprochen.". Zusätzliche Themen danach unter "Sonstiges"."""

        private const val FOLLOWUP_RULE = """Regeln für "erledigte_aufgaben": Kennungen (z. B. "A2 [14]") der offenen Aufgaben aus früheren Besprechungen, von denen im Gespräch gesagt wird, dass sie erledigt sind, jeweils mit der belegenden Zeilennummer. Nur bei klarer Aussage. Leere Liste, wenn keine."""

        internal fun templateRule(t: ProtocolTemplate): String? = when (t) {
            ProtocolTemplate.ALLGEMEIN -> null
            ProtocolTemplate.DIENSTBESPRECHUNG -> """Vorlage Dienstbesprechung: Gliedere "themen" nach Bereichen wie "Informationen", "Organisation und Dienstplan", "Termine", "Sonstiges" (nur Bereiche, die vorkamen; passende eigene Titel erlaubt). Termine immer mit Datum bzw. Zeitangabe nennen."""
            ProtocolTemplate.FALLBESPRECHUNG -> """Vorlage Fallbesprechung: Je besprochenem Fall (Klient, Patient, Bewohner) ein Thema mit dem Titel "Fall: <Name oder Kürzel wie im Gespräch>". Punkte, soweit besprochen, in dieser Reihenfolge und mit diesen Anfängen: "Ausgangslage: …", "Einschätzung: …", "Vereinbarte Maßnahmen: …", "Nächste Überprüfung: …". Fachlich und wertschätzend formulieren, keine Vermutungen."""
            ProtocolTemplate.UEBERGABE -> """Vorlage Übergabe: Je Person (Klient, Patient, Bewohner) ein Thema mit dem Namen als Titel. Punkte, soweit genannt: "Befinden: …", "Besonderheiten: …", "Pflege/Medikation: …", "Für den nächsten Dienst: …". Allgemeine Informationen unter dem Titel "Allgemein". Kurz und eindeutig."""
            ProtocolTemplate.VEREINSSITZUNG -> """Vorlage Vereinssitzung: Formales Sitzungsprotokoll. Erstes Thema "Eröffnung und Formalien" (Begrüßung, Beschlussfähigkeit, Genehmigung der Tagesordnung und des letzten Protokolls), soweit erwähnt. Jede Abstimmung gehört in "beschluesse" im Format "<Beschlusstext> – Abstimmung: X Ja, Y Nein, Z Enthaltungen, angenommen." bzw. "… einstimmig angenommen." oder "… abgelehnt.". Zahlen nur, wenn sie genannt wurden."""
        }

        internal fun extraRules(hasAgenda: Boolean, hasOpenTasks: Boolean,
                                template: ProtocolTemplate = ProtocolTemplate.ALLGEMEIN): String = buildString {
            append(TASK_RULES)
            templateRule(template)?.let { append("\n").append(it) }
            if (hasAgenda) append("\n").append(AGENDA_RULE)
            append("\n").append(if (hasOpenTasks) FOLLOWUP_RULE else "\"erledigte_aufgaben\": immer leere Liste.")
            append("\n").append(CITE_RULE)
        }

        val SUMMARY_PROMPT = """
%s

Erstelle aus dem folgenden Material ein professionelles Ergebnisprotokoll als JSON:
- "zusammenfassung": 3 bis 5 Sätze, worum es ging und was herauskam (ohne Quellenangabe).
- "themen": die besprochenen Themen in zeitlicher Reihenfolge. Je Thema ein kurzer "titel" und 2 bis 6 "punkte" mit Ergebnissen, Argumenten und Zahlen (vollständige, knappe Sätze).
- "beschluesse": Entscheidungen, die die Runde getroffen hat (z. B. "Ab März wird ..."). Reine Aufgabenverteilungen gehören NICHT hierher, sondern zu "aufgaben". Leere Liste, wenn keine.
- "offene_punkte": ungeklärte Fragen und vertagte Themen. Leere Liste, wenn keine.
- "aufgaben", "erledigte_aufgaben", "sensible_daten": siehe Regeln.

%s

%s
""".trimIndent()

        val TASKS_PROMPT = """
%s

Extrahiere alle Aufgaben, die in diesem Gespräch vereinbart, zugesagt oder jemandem zugewiesen wurden, als JSON. Keine Aufgaben: "aufgaben": []

%s

Transkript:
%s
""".trimIndent()

        private const val JSON_STRING =
            "string ::= \"\\\"\" ( [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4}) )* \"\\\"\"\n" +
                "ws ::= [ \\t\\n]{0,20}\n" +
                "strlist ::= \"[\" ws ( string ws (\",\" ws string ws)* )? \"]\"\n" +
                "tasks ::= \"[\" ws ( task ws (\",\" ws task ws)* )? \"]\"\n" +
                "task ::= \"{\" ws \"\\\"aufgabe\\\":\" ws string ws \",\" ws \"\\\"verantwortlich\\\":\" ws string ws \",\" ws \"\\\"frist\\\":\" ws string ws \"}\"\n" +
                "tail ::= \",\" ws \"\\\"erledigte_aufgaben\\\":\" ws strlist ws \",\" ws \"\\\"sensible_daten\\\":\" ws strlist ws\n"

        val SUMMARY_GRAMMAR = """
root ::= "{" ws "\"zusammenfassung\":" ws string ws "," ws "\"themen\":" ws themen ws "," ws "\"beschluesse\":" ws strlist ws "," ws "\"offene_punkte\":" ws strlist ws "," ws "\"aufgaben\":" ws tasks ws tail "}"
themen ::= "[" ws ( thema ws ("," ws thema ws)* )? "]"
thema ::= "{" ws "\"titel\":" ws string ws "," ws "\"punkte\":" ws strlist ws "}"
""".trimIndent() + "\n" + JSON_STRING

        val TASKS_GRAMMAR = """
root ::= "{" ws "\"aufgaben\":" ws tasks ws tail "}"
""".trimIndent() + "\n" + JSON_STRING

        private val ADDRESS = Regex("\\b(Frau|Herr|Herrn)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})")
        private val GENERIC = Regex("^(Person \\d+|offen|unklar|alle|team|)$", RegexOption.IGNORE_CASE)

        /**
         * Kleine Modelle setzen als Verantwortliche oft die Sprecherbezeichnung ("Person 2") statt des
         * angesprochenen Namens. Steht im Beleg oder im Satz davor eine Anrede ("Frau Becker, können
         * Sie …"), die eine andere Person an diese richtet, wird dieser Name übernommen.
         */
        internal fun resolveOwner(owner: String, ref: Int, segments: List<Segment>,
                                  nameOf: (Int) -> String): String {
            val o = owner.trim()
            if (!GENERIC.matches(o) || ref !in segments.indices) return owner
            for (i in listOf(ref, ref - 1)) {
                val seg = segments.getOrNull(i) ?: continue
                val m = ADDRESS.find(seg.text) ?: continue
                val title = if (m.groupValues[1] == "Herrn") "Herr" else m.groupValues[1]
                val name = "$title ${m.groupValues[2]}"
                // Wer spricht, meint mit der Anrede jemand anderen
                if (nameOf(seg.speakerId).equals(name, ignoreCase = true)) continue
                return name
            }
            return owner
        }

        // ------------------------------------------------------------ Parsing

        private val REF = Regex("\\s*\\[(\\d{1,5})(?:\\s*[,;–-]\\s*\\d{1,5})*]")

        /** Trennt Quellenangaben "[12]" vom Text. Rückgabe: (Text ohne Angaben, erster gültiger Index). */
        fun splitRef(raw: String, segmentCount: Int): Pair<String, Int> {
            var ref = -1
            REF.findAll(raw).forEach { m ->
                val idx = m.groupValues[1].toInt()
                if (ref < 0 && idx in 0 until segmentCount) ref = idx
            }
            val text = raw.replace(REF, "").replace(Regex("\\s+([.,;:!?])"), "$1").trim()
            return text to ref
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList()
            else (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }

        private fun jsonObject(raw: String): JSONObject {
            val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
            if (s < 0 || e <= s) throw LlmException("Ungültige Antwort des Sprachmodells")
            return JSONObject(raw.substring(s, e + 1))
        }

        fun parseSummary(raw: String, segmentCount: Int = Int.MAX_VALUE): Summary {
            val o = jsonObject(raw)
            val themen = o.optJSONArray("themen") ?: JSONArray()
            fun split(list: List<String>) = list.map { splitRef(it, segmentCount) }
            val decisions = split(o.optJSONArray("beschluesse").strings())
            val open = split(o.optJSONArray("offene_punkte").strings())
            return Summary(
                overview = splitRef(o.optString("zusammenfassung"), 0).first,
                topics = (0 until themen.length()).mapNotNull {
                    val t = themen.optJSONObject(it) ?: return@mapNotNull null
                    val pts = split(t.optJSONArray("punkte").strings())
                    Topic(splitRef(t.optString("titel"), 0).first, pts.map { p -> p.first },
                        pts.map { p -> p.second })
                }.filter { it.title.isNotEmpty() },
                decisions = decisions.map { it.first },
                openPoints = open.map { it.first },
                decisionRefs = decisions.map { it.second },
                openPointRefs = open.map { it.second },
            )
        }

        fun parseTasks(raw: String, segmentCount: Int = Int.MAX_VALUE): List<TaskItem> {
            val arr = jsonObject(raw).optJSONArray("aufgaben") ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                val t = arr.optJSONObject(it) ?: return@mapNotNull null
                val (text, ref) = splitRef(t.optString("aufgabe"), segmentCount)
                if (text.isEmpty()) return@mapNotNull null
                fun norm(v: String) = splitRef(v, 0).first.ifEmpty { "offen" }.let {
                    if (it.equals("unbekannt", true) || it == "-" || it.equals("n/a", true) ||
                        it.equals("keine", true)) "offen" else it
                }
                TaskItem(text, norm(t.optString("verantwortlich")), norm(t.optString("frist")), ref = ref)
            }
        }

        /** "A2 [14]" -> ("A2", 14) */
        fun parseCompleted(raw: String, segmentCount: Int = Int.MAX_VALUE): List<Pair<String, Int>> =
            runCatching { jsonObject(raw).optJSONArray("erledigte_aufgaben").strings() }
                .getOrDefault(emptyList())
                .mapNotNull { s ->
                    val key = Regex("\\bA\\d{1,3}\\b").find(s)?.value ?: return@mapNotNull null
                    key to splitRef(s.replace(key, ""), segmentCount).second
                }

        fun parseSensitive(raw: String, participants: List<String> = emptyList()): List<String> {
            val people = participants.map { it.lowercase() }.toSet()
            return runCatching { jsonObject(raw).optJSONArray("sensible_daten").strings() }
                .getOrDefault(emptyList())
                .map { splitRef(it, 0).first.trim().trim('"', '„', '“') }
                .filter { it.length >= 3 && it.lowercase() !in people }
                .filterNot { it.lowercase().startsWith("person ") }
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
