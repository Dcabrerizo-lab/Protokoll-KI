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
    /** Wie [complete]; [cachePrefix] ist der gleichbleibende Anfang von [user], den das Modell vorberechnet laden darf. */
    fun completeCached(system: String, user: String, maxTokens: Int, temperature: Float,
                       grammar: String?, progress: LlmProgress?, cachePrefix: String): String =
        complete(system, user, maxTokens, temperature, grammar, progress)
}

class LocalLlmModel(private val llm: LocalLlm) : TextModel {
    override val contextSize get() = llm.contextSize
    override fun countTokens(text: String) = llm.countTokens(text)
    override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                          grammar: String?, progress: LlmProgress?) =
        llm.complete(system, user, maxTokens, temperature, grammar, progress)
    override fun completeCached(system: String, user: String, maxTokens: Int, temperature: Float,
                                grammar: String?, progress: LlmProgress?, cachePrefix: String) =
        llm.complete(system, user, maxTokens, temperature, grammar, progress, cachePrefix)
}

/**
 * Erzeugt Ergebnisprotokoll, Aufgaben, Quellenbelege, Aufgaben-Abgleich und
 * sensible Begriffe aus dem Transkript. Lange Besprechungen werden abschnittsweise
 * verdichtet (Map-Reduce). Ausgaben sind per Grammatik auf gültiges JSON beschränkt.
 */
/**
 * [compactJson]: JSON ohne Einrückungen schreiben lassen. Bewertung 26.11. (je zwei Läufe): beim kleinen
 * Modell (Kompakt) 35 % weniger Tokens und weniger Fehler; bei Standard/Premium nur 7–10 % weniger Tokens,
 * aber zusätzliche erfundene Aufgaben. Daher nur für Kompakt.
 */
class ProtocolGenerator(private val model: TextModel, private val compactJson: Boolean = false) {
    private val summaryGrammar get() = if (compactJson) compact(SUMMARY_GRAMMAR) else SUMMARY_GRAMMAR
    private val tasksGrammar get() = if (compactJson) compact(TASKS_GRAMMAR) else TASKS_GRAMMAR


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
        participants: List<String> = emptyList(),
        progress: Progress,
    ): Result {
        val names = speakers.associate { it.id to it.name }
        // Sprecherbezeichnungen ("Person 2"), deren Name aus direkter Anrede eindeutig folgt, mit Namen zeigen:
        // das Modell ordnet Aufgaben dann richtig zu ("[4] Frau Becker: Ja, das mache ich.")
        val known = TranscriptRules.speakerNames(segments)
            .filterKeys { id -> names[id]?.let { DEFAULT_LABEL.matches(it) } ?: true }
        fun nameOf(id: Int) = known[id] ?: names[id] ?: "Person ${id + 1}"
        val lines = segments.mapIndexed { i, s -> "[$i] ${nameOf(s.speakerId)}: ${s.text}" }
        val invited = participants.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val voices = segments.map { nameOf(it.speakerId) }.distinct()
        val date = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN).format(Date(dateMillis))
        val header = buildString {
            append("Besprechung: $title\nDatum: $date\nTeilnehmende: ${voices.joinToString(", ")}")
            if (invited.isNotEmpty()) append("\nTeilnehmende laut Einladung: ${invited.joinToString(", ")}")
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
        // Antwortlängen passend zum Kontext (bei 4k-Kontext sonst "Text zu lang")
        val ctx = model.contextSize
        val summaryMax = minOf(2600, ctx / 3)
        val notesMax = minOf(1000, ctx / 5)
        val tasksMax = minOf(1400, ctx / 4)
        val reserve = summaryMax + 900 + model.countTokens(header)
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
                        notesMax, 0.2f, null, llmProgress { prog(label, it) })
                    step++
                    "Notizen zu Teil ${i + 1}:\n$n"
                }.joinToString("\n\n")
            }
            // Ein Durchgang für Protokoll UND Aufgaben (halbiert die Rechenzeit)
            val label = "Schreibe Protokoll und Aufgaben …"
            if (!prog(label)) throw LlmException("Abgebrochen")
            // Notizen vieler Abschnitte können zusammen zu lang werden: dann gleichmäßig kürzen
            var fitted = material
            for (attempt in 0 until 12) {
                val tokens = model.countTokens(SYSTEM) + model.countTokens(SUMMARY_PROMPT.format(extras, header, fitted))
                if (tokens + summaryMax + 64 <= ctx) break
                fitted = shortenEvenly(fitted, 0.8)
            }
            raws += model.completeCached(SYSTEM, SUMMARY_PROMPT.format(extras, header, fitted),
                summaryMax, 0.2f, summaryGrammar, llmProgress { prog(label, it) }, fixedPart(SUMMARY_PROMPT, extras))
            step++
        } else {
            for ((i, c) in chunks.withIndex()) {
                val label = if (chunks.size > 1) "Ermittle Aufgaben (${i + 1}/${chunks.size}) …"
                else "Ermittle Aufgaben …"
                if (!prog(label)) throw LlmException("Abgebrochen")
                raws += model.completeCached(SYSTEM, TASKS_PROMPT.format(extras, header, c),
                    tasksMax, 0.0f, tasksGrammar, llmProgress { prog(label, it) }, fixedPart(TASKS_PROMPT, extras))
                step++
            }
        }

        val meetingDate = java.time.Instant.ofEpochMilli(dateMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val n = segments.size
        val mentioned = (segments.joinToString(" ") { it.text } + " " + (invited + vocabulary).joinToString(" ")).lowercase()
        var summary = if (wantSummary) runCatching { parseSummary(raws.first(), n) }.getOrElse { Summary() } else Summary()
        val commitments = TranscriptRules.commitments(segments)
        fun refine(list: List<TaskItem>) = list
            .map { it.copy(ref = checkRef(it.text, it.ref, segments, commitments)) }
            .map { t -> t.copy(due = checkDue(t, segments, meetingDate)) }
            .map { it.copy(due = DateResolver.resolve(it.due, meetingDate)) }
            .map { it.copy(owner = resolveOwner(it.owner, it.ref, segments, ::nameOf, it.text, known, mentioned)) }
        // Einzelne Wörter ("Verschiedenes") sind keine Aufgaben
        var tasks = refine(raws.flatMap { r -> runCatching { parseTasks(r, n) }.getOrDefault(emptyList()) }
            .filter { it.text.trim().contains(' ') && !TranscriptRules.procedural(it.text) && !TOPIC_LIKE(it.text) })
            // Beleg berichtet nur Erledigtes ("habe ich organisiert") und enthält keine Zusage: keine Aufgabe
            .filterNot { t -> t.ref in segments.indices && commitments.none { it.ref == t.ref } &&
                TranscriptRules.reportedDone(t.text, segments[t.ref].text) }
            // Abschrift einer bekannten offenen Aufgabe ohne Beleg im Gespräch: wird schon als offen geführt
            .filterNot { t -> t.ref !in segments.indices && openTasks.any { (words(it.text) intersect words(t.text)).size >= 2 } }

        // Aufgaben-Nachfassen ohne zusätzliche Modellrunde: Zusagen und Aufträge im Transkript, die keine
        // Aufgabe abdeckt (kleine Modelle übersehen sie oft), werden regelbasiert als Aufgabe formuliert.
        val pending = commitments.filter { c -> tasks.none { covers(it, c, segments) } }
            // Zusage als Antwort auf eine Bitte direkt davor: gehört zu dieser Bitte
            .filterNot { c -> c.kind == TranscriptRules.Kind.SELF && commitments.any { o ->
                o.kind == TranscriptRules.Kind.REQUEST && o.ref == c.ref - 1 &&
                    segments[o.ref].speakerId != segments[c.ref].speakerId } }
            .distinctBy { it.ref to it.sentence }
        if (pending.isNotEmpty()) {
            val extra = pending.map { c -> TaskItem(TranscriptRules.phraseTask(c.sentence), "offen", "offen", ref = c.ref) }
                .filter { it.text.split(' ').size >= 2 }
            tasks = tasks + refine(extra)
        } else if (wantSummary && tasks.isEmpty()) {
            // Keine Aufgaben und keine erkennbaren Zusagen: zur Sicherheit gezielt nachfassen (nur Aufgaben, je Abschnitt)
            for (c in chunks) {
                if (!progress.update(0.97f, "Prüfe Aufgaben …")) throw LlmException("Abgebrochen")
                val r = model.completeCached(SYSTEM, TASKS_PROMPT.format(extras, header, c), tasksMax, 0.0f, tasksGrammar, null,
                    fixedPart(TASKS_PROMPT, extras))
                raws += r
                tasks = tasks + refine(runCatching { parseTasks(r, n) }.getOrDefault(emptyList()))
            }
        }
        val doneKeys = mutableMapOf<String, Int>()
        raws.forEach { r -> runCatching { parseCompleted(r, n) }.getOrDefault(emptyList()).forEach { (k, ref) -> doneKeys.putIfAbsent(k, ref) } }
        if (openTasks.isNotEmpty()) {
            val open = openTasks.associate { it.key to it.text }
            // Vom Modell gemeldet, im Gespräch aber ausdrücklich noch offen: verwerfen
            doneKeys.keys.removeAll { k -> TranscriptRules.stillOpen(open[k] ?: "", segments) }
            TranscriptRules.completedFromTranscript(open, segments).forEach { (k, ref) -> doneKeys.putIfAbsent(k, ref) }
        }
        val completed = openTasks.filter { it.key in doneKeys }
        val finalTasks = dedupe(tasks).let { all ->
            // Gleiche Person, gleicher Gegenstand: die Aufgabe mit Zusage im Beleg ersetzt die ohne
            fun committed(t: TaskItem) = commitments.any { it.ref == t.ref }
            fun grounded(t: TaskItem) = segments.getOrNull(t.ref)?.let { (contentWords(t.text) intersect contentWords(it.text)).size } ?: 0
            all.filterNot { t -> !GENERIC.matches(t.owner) && !committed(t) && all.any { u -> u !== t &&
                u.owner.equals(t.owner, true) && committed(u) && TranscriptRules.sharedNouns(t.text, u.text).isNotEmpty() } }
                // Gleicher Beleg, gleiche Person: eine Formulierung ohne jeden Bezug zum Beleg weicht der passenden
                .let { kept -> kept.filterNot { t -> grounded(t) == 0 && kept.any { u -> u !== t && u.ref == t.ref &&
                    u.owner.equals(t.owner, true) && grounded(u) >= 2 } } }
        }
        // Team = Sprechende, Eingeladene und ausdrücklich Beauftragte. Verantwortliche aus der Modellausgabe zählen
        // nicht automatisch dazu: kleine Modelle tragen dort auch Klientinnen und Klienten ein.
        val assigned = TranscriptRules.staffNames(segments)
        val staff = (known.values + invited + assigned + finalTasks.map { it.owner }.filter { o -> known.values.any { it.equals(o, true) } })
            .flatMap { it.lowercase().split(' ') }.filter { it.length >= 3 && it !in setOf("frau", "herr", "person") }.toSet()
        if (wantSummary) summary = refineSummary(summary, segments, agenda, template, finalTasks, staff)
        val sensitive = (raws.flatMap { parseSensitive(it, voices + invited) } +
            (if (template == ProtocolTemplate.FALLBESPRECHUNG || template == ProtocolTemplate.UEBERGABE)
                addressedClients(segments, finalTasks) else emptyList()) +
            TranscriptRules.addressesAndPhones(segments) +
            TranscriptRules.clientsByContext(segments, staff + doers(segments)) +
            (if (template == ProtocolTemplate.FALLBESPRECHUNG || template == ProtocolTemplate.UEBERGABE)
                TranscriptRules.healthData(segments) else emptyList()))
            .filterNot { term -> term.lowercase().split(' ').filter { it.length >= 3 }.let { w -> w.isNotEmpty() && w.all { it in staff || it in setOf("frau", "herr", "herrn") } } }
            .distinctBy { it.lowercase() }
            .let { all -> all.filterNot { t -> all.any { u -> u != t && u.length > t.length &&
                Regex("(?<![\\p{L}])${Regex.escape(t.lowercase())}(?![\\p{L}])").containsMatchIn(u.lowercase()) } } }  // "Schulz" neben "Frau Schulz"
        // Klientin/Klient als Verantwortliche (kleine Modelle verwechseln Fall und Zuständige): neu ableiten
        val clientNames = sensitive.flatMap { it.lowercase().split(' ') }.filter { it.length >= 3 && it !in staff }.toSet()
        val ownedTasks = finalTasks.map { t ->
            val surname = t.owner.substringAfterLast(' ').lowercase()
            if (!GENERIC.matches(t.owner) && surname in clientNames)
                t.copy(owner = resolveOwner("offen", t.ref, segments, ::nameOf, t.text, known, mentioned)
                    .takeIf { it.substringAfterLast(' ').lowercase() !in clientNames } ?: "offen")
            else t
        }
        progress.update(1f, "Fertig")
        return Result(summary, ownedTasks, completed,
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

        private const val CITE_RULE = """Quellenangabe: Setze an das Ende JEDES Punkts, Beschlusses, offenen Punkts und jeder Aufgabe die Nummer der Transkriptzeile, die ihn belegt, in eckigen Klammern, Muster: "<Aussage>. [8]"."""

        private const val TASK_RULES = """Regeln für "aufgaben" (alles, was jemand erledigen soll oder zugesagt hat):
- "aufgabe": kurz und konkret, mit dem Verb am Ende, Muster: "<Gegenstand> <Verb> [Zeilennummer]".
- "verantwortlich": Name der Person, die die Aufgabe übernimmt, wie er im Gespräch fällt. Wird jemand mit "Frau …" oder "Herr …" angesprochen oder genannt, diesen Namen verwenden. Sagt jemand "mache ich" oder "ich kümmere mich", ist es die sprechende Person (Name vor dem Doppelpunkt). Unklar: "offen".
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

        /** Gleichbleibender Anfang eines Prompts: Vorlagentext vor dem ersten %s plus Regeln (alles vor den Kopfdaten). */
        internal fun fixedPart(template: String, extras: String): String =
            template.substringBefore("%s") + extras

        internal fun extraRules(hasAgenda: Boolean, hasOpenTasks: Boolean,
                                template: ProtocolTemplate = ProtocolTemplate.ALLGEMEIN): String = buildString {
            append(TASK_RULES)
            templateRule(template)?.let { append("\n").append(it) }
            if (hasAgenda) append("\n").append(AGENDA_RULE)
            append("\n").append(if (hasOpenTasks) FOLLOWUP_RULE else "\"erledigte_aufgaben\": immer leere Liste.")
            append("\n").append(CITE_RULE)
        }

        // Reihenfolge: gleichbleibende Anweisungen zuerst (werden vorberechnet geladen), dann Kopfdaten und Material
        val SUMMARY_PROMPT = """
Erstelle aus dem Material am Ende ein professionelles Ergebnisprotokoll als JSON:
- "zusammenfassung": 3 bis 5 Sätze, worum es ging und was herauskam (ohne Quellenangabe).
- "themen": die besprochenen Themen in zeitlicher Reihenfolge. Je Thema ein kurzer "titel" und 2 bis 6 "punkte" mit Ergebnissen, Argumenten und Zahlen (vollständige, knappe Sätze).
- "beschluesse": Entscheidungen, die die Runde getroffen hat, und jede Abstimmung mit ihrem Ergebnis (angenommen/abgelehnt). Reine Aufgabenverteilungen gehören NICHT hierher, sondern zu "aufgaben". Leere Liste, wenn keine.
- "offene_punkte": ungeklärte Fragen und vertagte Themen. Leere Liste, wenn keine.
- "aufgaben", "erledigte_aufgaben", "sensible_daten": siehe Regeln.

%s

%s

%s
""".trimIndent()

        val TASKS_PROMPT = """
Extrahiere alle Aufgaben, die im Gespräch am Ende vereinbart, zugesagt oder jemandem zugewiesen wurden, als JSON. Keine Aufgaben: "aufgaben": []

%s

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

        private const val WS_FULL = "ws ::= [ \\t\\n]{0,20}"
        internal fun compact(grammar: String) = grammar.replace(WS_FULL, "ws ::= [ ]?")

        val SUMMARY_GRAMMAR = """
root ::= "{" ws "\"zusammenfassung\":" ws string ws "," ws "\"themen\":" ws themen ws "," ws "\"beschluesse\":" ws strlist ws "," ws "\"offene_punkte\":" ws strlist ws "," ws "\"aufgaben\":" ws tasks ws tail "}"
themen ::= "[" ws ( thema ws ("," ws thema ws)* )? "]"
thema ::= "{" ws "\"titel\":" ws string ws "," ws "\"punkte\":" ws strlist ws "}"
""".trimIndent() + "\n" + JSON_STRING

        val TASKS_GRAMMAR = """
root ::= "{" ws "\"aufgaben\":" ws tasks ws tail "}"
""".trimIndent() + "\n" + JSON_STRING

        private val ADDRESS = Regex("\\b(Frau|Herr|Herrn)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})")
        private val TASK_NAME = Regex("(?<!für |mit |von |bei |an |zu |über |um |gegen |ohne |und )\\b(Frau|Herr)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})")
        private val STAFF_ACTION = Regex("\\b(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})\\s+(?:übernimmt|organisiert|beantragt|kümmert sich|klärt|erstellt|bestellt|fordert|ruft|schreibt|meldet|prüft|kontaktiert|informiert|vereinbart|besorgt|holt|plant|dokumentiert|trägt)(?![\\p{L}])")
        /** Stichpunkt statt Aufgabe ("Insulintherapie - Basalinsulin abends …"): Gliederungszeichen, kein Verb im Infinitiv */
        private fun TOPIC_LIKE(text: String): Boolean = Regex("\\s[-–+]\\s").containsMatchIn(text) &&
            !Regex("(?:^|\\s)\\p{Ll}\\p{L}+(?:en|ern|eln)\\b").containsMatchIn(text) &&
            !Regex("^\\p{L}+(?:en|ern|eln)\\b").containsMatchIn(text.trim())

        /** "Person 2: …", "Frau Kaiser: …" – Sprecherzeile aus dem Transkript übernommen */
        private val SPEAKER_PREFIX = Regex("^\\s*(?:Person \\d+|Sprecher(?:in)? \\d+|(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?[A-ZÄÖÜ][\\p{L}-]{1,30})\\s*:\\s*")
        private val DEFAULT_LABEL = Regex("^(Person|Sprecher(in)?) \\d+$")
        private val GENERIC = Regex("^(Person \\d+|Sprecher(in)? \\d+|offen|unklar|unbekannt|keine angabe|niemand|alle|alle beteiligten|alle mitarbeitenden|das team|team|ich|sie|du|wir|er|ihr|-|n/?a|)$", RegexOption.IGNORE_CASE)

        /**
         * Fall- und Übergabebesprechungen: angesprochene Personen ("Herr Krüger", "Frau Lehmann"), die
         * keine Aufgabe übernehmen, sind in der Regel Klientinnen und Klienten. Sie werden als sensibel
         * vorgeschlagen, auch wenn das Sprachmodell sie nicht meldet (kleine Modelle tun das selten).
         */
        private fun doers(segments: List<Segment>): Set<String> =
            segments.flatMap { seg -> STAFF_ACTION.findAll(seg.text).map { it.groupValues[1].lowercase() }.toList() }.toSet()

        internal fun addressedClients(segments: List<Segment>, tasks: List<TaskItem>): List<String> {
            val staff = tasks.flatMap { t -> ADDRESS.findAll(t.owner).map { it.groupValues[2].lowercase() }.toList() +
                t.owner.split(' ').map { it.lowercase() } }.toSet()
            // Wer eine Aufgabe übernimmt ("Frau Schmidt beantragt …", "Herr Neumann übernimmt …"), gehört zum Team
            val doers = segments.flatMap { seg -> STAFF_ACTION.findAll(seg.text).map { it.groupValues[1].lowercase() }.toList() }.toSet()
            return segments.flatMap { seg -> ADDRESS.findAll(seg.text).map { it.groupValues[2] }.toList() }
                .filter { it.lowercase() !in staff && it.lowercase() !in doers }
                .distinctBy { it.lowercase() }
        }

        /**
         * Kleine Modelle setzen als Verantwortliche oft die Sprecherbezeichnung ("Person 2") statt des
         * gemeinten Namens. Ist die Angabe unbestimmt (oder ein Name, der im Gespräch nicht vorkommt), wird
         * die zuständige Person aus dem Beleg abgeleitet: Name in der Aufgabe, Selbstverpflichtung oder Bitte
         * im passenden Satz ([TranscriptRules.ownerFromRef]), Anrede im Beleg oder im Beitrag davor, zuletzt
         * die per Anrede erkannte Sprecherbezeichnung ([known]). Findet sich nichts, bleibt die Angabe.
         */
        internal fun resolveOwner(owner: String, ref: Int, segments: List<Segment>,
                                  nameOf: (Int) -> String, taskText: String = "",
                                  known: Map<Int, String> = emptyMap(), mentioned: String? = null): String {
            val o = owner.trim()
            val invented = !GENERIC.matches(o) && mentioned != null &&
                o.split(' ').filter { it.length >= 3 && it.lowercase() !in setOf("frau", "herr", "herrn", "dr.") }
                    .let { w -> w.isNotEmpty() && w.none { mentioned.contains(it.lowercase()) } }
            if (!GENERIC.matches(o) && !invented) {
                // Belegsatz weist ausdrücklich jemand anderem zu: der Beleg gewinnt
                val explicit = TranscriptRules.explicitOwner(ref, segments, known, taskText)
                return if (explicit != null && !explicit.equals(o, true) &&
                    explicit.substringAfterLast(' ').lowercase() !in o.lowercase()) explicit else owner
            }
            // Name steht in der Aufgabe selbst ("Herr Wagner klärt die Telefonanlage"), nicht als Empfänger ("für Herrn X")
            TASK_NAME.find(taskText.substringBefore("("))?.takeIf { m ->
                m.range.first == 0 || TranscriptRules.assignedName(taskText.substring(m.range.first)) != null
            }?.let { m -> return "${m.groupValues[1]} ${m.groupValues[2]}" }
            val labelId = Regex("^Person (\\d+)$").find(o)?.groupValues?.get(1)?.toInt()?.minus(1)
            if (ref in segments.indices) {
                // Beleg: Anrede/Zuweisung, Selbstverpflichtung oder Bitte im passenden Satz
                TranscriptRules.ownerFromRef(ref, segments, known, nameOf, taskText)?.let { return it }
                // Genannte Sprecherbezeichnung hat selbst zugesagt: beibehalten
                if (labelId != null && TranscriptRules.selfCommittedNear(labelId, ref, segments))
                    return known[labelId] ?: owner
                for (i in listOf(ref, ref - 1)) {
                    val seg = segments.getOrNull(i) ?: continue
                    val m = ADDRESS.find(seg.text) ?: continue
                    val name = (if (m.groupValues[1] == "Herrn") "Herr" else m.groupValues[1]) + " " + m.groupValues[2]
                    // Wer spricht, meint mit der Anrede jemand anderen
                    if (nameOf(seg.speakerId).equals(name, ignoreCase = true) ||
                        known[seg.speakerId].equals(name, ignoreCase = true)) continue
                    return name
                }
            }
            // Sprecherbezeichnung, deren Name aus der Anrede bekannt ist
            labelId?.let { id -> known[id]?.let { return it } }
            return owner
        }

        /**
         * Beleg einer Aufgabe prüfen: fehlt er oder teilt er kein Wort mit der Aufgabe, den passendsten Beitrag
         * nehmen. Passt eine Zusage/ein Auftrag deutlich (zwei Wörter) und der Beleg enthält keinen, die Zusage.
         */
        internal fun checkRef(taskText: String, ref: Int, segments: List<Segment>,
                              commitments: List<TranscriptRules.Commitment> = emptyList()): Int {
            val own = if (ref in segments.indices) (words(taskText) intersect words(segments[ref].text)).size else 0
            val best = locateRef(taskText, segments)
            val bestScore = if (best >= 0) (words(taskText) intersect words(segments[best].text)).size else 0
            var r = if (ref !in segments.indices) best
            // Nachbarzeile vertauscht: ein anderer Beitrag passt deutlich besser (zwei Wörter mehr)
            else if (own > 0 && bestScore < own + 2) ref
            else best.takeIf { it >= 0 } ?: ref
            val w = words(taskText)
            fun shared(c: TranscriptRules.Commitment) = w.count { c.sentence.lowercase().contains(it) }
            if (commitments.none { it.ref == r && shared(it) >= 1 }) {
                commitments.filter { shared(it) >= 2 }.maxByOrNull { shared(it) }?.let { r = it.ref }
                    // Ohne Beleg: genau eine Zusage mit einem gemeinsamen Wort ("Kundendienst")
                    ?: if (r !in segments.indices) commitments.filter { shared(it) >= 1 }.map { it.ref }.distinct()
                        .singleOrNull()?.let { r = it }
                    // Beleg ist die Frage davor ("Gibt es einen Zuschuss?"), der Auftrag folgt direkt danach
                    else commitments.firstOrNull { it.ref == r + 1 && shared(it) >= 1 }?.let { r = it.ref }
            }
            return r
        }

        /**
         * Frist einer Aufgabe: Angabe des Modells, wenn sie im Beleg (oder direkt davor/danach) vorkommt,
         * sonst eine eindeutige "bis …"-Angabe aus dem Beleg, sonst "offen". Zahlwörter werden zu Ziffern.
         */
        private val PAST_DUE = Regex("\\b(?:vorgestern|gestern|letzte[nrs]?\\s+(?:woche|monat)|vergangene[nrs]?)\\b", RegexOption.IGNORE_CASE)

        internal fun checkDue(t: TaskItem, segments: List<Segment>, meeting: java.time.LocalDate): String {
            // Vergangenes ist keine Frist
            if (PAST_DUE.containsMatchIn(t.due)) return checkDue(t.copy(due = "offen"), segments, meeting)
            val seg = segments.getOrNull(t.ref) ?: return t.due.takeIf { d -> d == "offen" ||
                TranscriptRules.dueSupported(d, segments.joinToString(" ") { it.text }) }
                ?.let { TranscriptRules.normalizeDue(it) } ?: "offen"
            val context = (t.ref - 1..t.ref + 1).mapNotNull { segments.getOrNull(it)?.text }.joinToString(" ")
            if (t.due != "offen" && TranscriptRules.dueSupported(t.due, context))
                return TranscriptRules.normalizeDue(TranscriptRules.expandMonth(t.due, context))
            val sentence = sentenceFor(t.text, seg.text)
            // Kurze Antwort direkt danach ("Mache ich bis Mittwoch.")
            val answer = segments.getOrNull(t.ref + 1)?.takeIf {
                it.speakerId != seg.speakerId && it.text.length < 60 && TranscriptRules.isConfirmation(it.text)
            }
            // Zeitangaben ohne "bis" nur aus dem Teil, der die Aufgabe enthält ("…, bitte auf X achten")
            val own = sentence.substringAfterLast(" bitte ", sentence)
            return TranscriptRules.deadlineFromRef(sentence, meeting)
                ?: TranscriptRules.deadlineFromRef(seg.text, meeting)
                ?: answer?.let { TranscriptRules.deadlineFromRef(it.text, meeting) }
                ?: TranscriptRules.softDeadline(t.text.substringBefore("("))
                ?: TranscriptRules.softDeadline(own)
                ?: answer?.let { TranscriptRules.softDeadline(it.text) }
                ?: TranscriptRules.eventDeadline(sentence)
                ?: "offen"
        }

        /** Deckt die Aufgabe die Zusage ab? Beleg daneben und ein gemeinsames Wort, oder zwei gemeinsame Wörter. */
        internal fun covers(t: TaskItem, c: TranscriptRules.Commitment, segments: List<Segment>): Boolean {
            // Wortanfänge auch innerhalb von Zusammensetzungen ("reserv" in "Busreservierung")
            val sentence = c.sentence.lowercase()
            // Ausdrücklich jemand anderem zugewiesen ("Frau Albers übernimmt …"): eigene Aufgabe
            // Auftrag mit eigener Frist, die die Aufgabe nicht trägt: nur bei deutlicher Übereinstimmung abgedeckt
            val strict = t.due == "offen" && TranscriptRules.deadlineFromRef(c.sentence, java.time.LocalDate.of(2000, 1, 3)) != null
            if (t.ref != c.ref && TranscriptRules.assignee(c.sentence)?.substringAfterLast(' ')?.lowercase()
                    ?.let { it !in t.owner.lowercase() } == true) return false
            val shared = contentWords(t.text).count { sentence.contains(it) }
            return shared >= 2 || (!strict && shared >= 1 && t.ref in (c.ref - 1)..(c.ref + 1))
        }

        /** Der Satz eines Beitrags, der die meisten Wörter mit der Aufgabe teilt (sonst der ganze Beitrag). */
        internal fun sentenceFor(taskText: String, segText: String): String {
            val sentences = segText.split(Regex("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
            if (sentences.size <= 1) return segText
            val w = words(taskText)
            val best = sentences.maxByOrNull { (words(it) intersect w).size } ?: return segText
            return if ((words(best) intersect w).isEmpty()) segText else best
        }

        /**
         * Regelbasierte Nacharbeit am Protokoll: Abstimmungsergebnisse in die Beschlüsse, Themen nach
         * Tagesordnung, Fall-Gliederung bei Fallbesprechungen mit genau einer besprochenen Person.
         */
        internal fun refineSummary(summary: Summary, segments: List<Segment>, agenda: List<String>,
                                   template: ProtocolTemplate, tasks: List<TaskItem> = emptyList(),
                                   staff: Set<String> = emptySet()): Summary {
            var s = summary
            val votes = TranscriptRules.detectVotes(segments)
            if (votes.isNotEmpty()) {
                val (d, r) = TranscriptRules.mergeVotes(s.decisions, s.decisionRefs, votes)
                s = s.copy(decisions = d, decisionRefs = r)
            }
            // Ausdrücklich gefasste Beschlüsse ("Dann beschließen wir: …"), die im Protokoll fehlen
            TranscriptRules.explicitDecisions(segments).forEach { (text, ref) ->
                val st = TranscriptRules.stems(text)
                if (s.decisions.none { (TranscriptRules.stems(it) intersect st).size >= 2 })
                    s = s.copy(decisions = s.decisions + text, decisionRefs = s.decisions.indices.map { s.decisionRefs.getOrElse(it) { -1 } } + ref)
            }
            if (agenda.isNotEmpty()) s = s.copy(topics = TranscriptRules.alignAgenda(s.topics, agenda))
            if (template == ProtocolTemplate.FALLBESPRECHUNG && s.topics.isNotEmpty() && s.topics.none { it.title.startsWith("Fall") }) {
                // Nur eindeutig: genau eine angesprochene Person, die keine Aufgabe übernimmt und nicht teilnimmt
                val client = addressedClients(segments, tasks).filter { c -> staff.none { it.contains(c.lowercase()) } }.singleOrNull()
                if (client != null) {
                    val m = segments.firstNotNullOfOrNull { Regex("\\b(Herrn?|Frau)\\s+$client\\b").find(it.text) }
                    val who = m?.let { "${if (it.groupValues[1] == "Herrn") "Herr" else it.groupValues[1]} $client" } ?: client
                    s = s.copy(topics = s.topics.map { t ->
                        t.copy(title = if (t.title.contains(client)) "Fall: ${t.title}" else "Fall: $who – ${t.title}") })
                }
            }
            return s
        }

        /** Kürzt jeden Absatz (durch Leerzeile getrennt) auf [factor] seiner Länge, an Zeilengrenzen. */
        internal fun shortenEvenly(text: String, factor: Double): String =
            text.split("\n\n").joinToString("\n\n") { part ->
                val keep = (part.length * factor).toInt()
                if (part.length <= keep) part
                else part.take(keep).let { t -> t.substring(0, t.lastIndexOf('\n').takeIf { it > keep / 2 } ?: t.length) }
            }

        /** Wörter ohne Personennamen ("Frau Becker" verbindet keine Aufgabe mit einer Zusage) */
        private fun contentWords(t: String) = words(ADDRESS.replace(t, " "))
        private val WORD = Regex("[\\p{L}\\d]{4,}")
        private val STOP = setOf("bitte", "sich", "sind", "wird", "werden", "noch", "dann", "auch", "eine",
            "einen", "einer", "dass", "diese", "dieser", "nicht", "habe", "haben", "kann", "können", "sollen")

        private fun words(t: String) = WORD.findAll(t.lowercase()).map { it.value.take(6) }
            .filter { it !in STOP }.toSet()

        /**
         * Fehlt die Quellenangabe einer Aufgabe, wird der Redebeitrag mit den meisten gemeinsamen
         * Wörtern als Beleg gesetzt (mindestens zwei, Wortanfänge verglichen). Sonst -1.
         */
        internal fun locateRef(taskText: String, segments: List<Segment>): Int {
            val w = words(taskText)
            if (w.size < 2) return -1
            var best = -1; var bestScore = 1
            segments.forEachIndexed { i, s ->
                val score = words(s.text).count { it in w }
                if (score > bestScore) { best = i; bestScore = score }
            }
            return best
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
            if (s >= 0 && e > s) runCatching { return JSONObject(raw.substring(s, e + 1)) }
            // Abgeschnittene Antwort (Token-Grenze erreicht, Modell hat sich wiederholt): reparieren
            if (s >= 0) repairJson(raw.substring(s))?.let { r -> runCatching { return JSONObject(r) } }
            throw LlmException("Ungültige Antwort des Sprachmodells")
        }

        /**
         * Schneidet eine abgebrochene JSON-Antwort hinter dem letzten vollständigen Element ab und
         * schließt alle offenen Klammern. null, wenn nichts Brauchbares übrig bleibt.
         */
        internal fun repairJson(raw: String): String? {
            val stack = ArrayDeque<Char>()
            var inString = false; var escape = false
            var cut = -1; var cutStack: List<Char> = emptyList()
            for ((i, c) in raw.withIndex()) {
                if (inString) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> stack.addLast(c)
                    '}', ']' -> {
                        if (stack.isEmpty()) break
                        stack.removeLast()
                        if (stack.isEmpty()) return raw.substring(0, i + 1)
                        cut = i; cutStack = stack.toList()
                    }
                    ',' -> if (stack.isNotEmpty()) { cut = i - 1; cutStack = stack.toList() }
                }
            }
            if (cut < 0) return null
            val sb = StringBuilder(raw.substring(0, cut + 1).trimEnd().trimEnd(','))
            for (open in cutStack.reversed()) sb.append(if (open == '{') '}' else ']')
            return sb.toString()
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
                    .let { (x, r) -> x.replace(SPEAKER_PREFIX, "").replaceFirstChar { c -> c.uppercase() } to r }
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
