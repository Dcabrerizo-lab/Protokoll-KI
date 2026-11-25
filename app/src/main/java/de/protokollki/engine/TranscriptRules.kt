package de.protokollki.engine

import de.protokollki.data.Segment
import de.protokollki.data.Topic
import java.time.LocalDate

/**
 * Deterministische Regeln, die das Protokoll des Sprachmodells anhand des Transkripts ergänzen oder
 * berichtigen. Grundsatz: nur übernehmen, was wörtlich im Gespräch steht; im Zweifel nichts tun.
 * Kleine Modelle übersehen gerade die formalen Dinge (Abstimmungsergebnisse, wer gemeint ist), die
 * sich zuverlässig an der Sprache erkennen lassen.
 */
object TranscriptRules {

    /**
     * Wortgrenze, die Umlaute als Buchstaben zählt: "\\b" der JVM ist nur ASCII-bewusst ("\\bübernehme"
     * passt dort nie), unter Android aber Unicode-bewusst. So verhalten sich die Regeln überall gleich.
     */
    private const val B = "(?:(?<![\\p{L}\\d])(?=[\\p{L}\\d])|(?<=[\\p{L}\\d])(?![\\p{L}\\d]))"

    private fun rx(pattern: String, ignoreCase: Boolean = false) =
        Regex(pattern.replace("\\b", B), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

    // ------------------------------------------------------------ Zahlen

    private val NUMBERS = mapOf(
        "null" to 0, "keine" to 0, "keiner" to 0, "niemand" to 0, "ein" to 1, "eine" to 1, "einer" to 1,
        "eins" to 1, "einen" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6,
        "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "dreizehn" to 13, "vierzehn" to 14, "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17,
        "achtzehn" to 18, "neunzehn" to 19, "zwanzig" to 20,
    )

    internal fun number(w: String): Int? = w.toIntOrNull()?.takeIf { it in 0..999 } ?: NUMBERS[w.lowercase()]

    // ------------------------------------------------------------ Abstimmungen

    data class Vote(
        val subject: String?, val accepted: Boolean, val unanimous: Boolean,
        val yes: Int?, val no: Int?, val abstain: Int?, val ref: Int,
    ) {
        val result get() = if (accepted) "angenommen" else "abgelehnt"

        /** "Abstimmung: 5 Ja, 1 Nein, 1 Enthaltung, angenommen." bzw. "einstimmig angenommen." */
        fun suffix(): String {
            if (unanimous) return "einstimmig $result."
            val counts = listOfNotNull(yes?.let { "$it Ja" }, no?.let { "$it Nein" },
                abstain?.let { "$it Enthaltung" + if (it == 1) "" else "en" })
            return if (counts.isEmpty()) "$result." else "Abstimmung: ${counts.joinToString(", ")}, $result."
        }

        fun text(): String? = subject?.let { "$it – ${suffix()}" }
    }

    private const val W = "([\\p{L}\\d]+)"
    private val RESULT = rx("\\b(einstimmig\\s+|mehrheitlich\\s+)?(angenommen|abgelehnt)\\b", true)
    private val VOTE_CONTEXT = rx("abstimm|stimmen\\s+(?:wir\\s+)?(?:jetzt\\s+|nun\\s+|also\\s+)?(?:darüber\\s+)?ab\\b|wer ist dafür|wer ist für|dafür\\s*[?:]|dagegen\\s*[?:]|gegenstimme|enthaltung|der antrag|den antrag",
        true)
    private val YES = listOf(
        rx("(?:wer ist dafür|wer stimmt dafür|wer ist für[^?.!]{0,90}|dafür)\\s*[?:]\\s*$W", true),
        rx("\\b$W\\s+(?:ja-stimmen|jastimmen|stimmen dafür)", true))
    private val NO = listOf(
        rx("(?:wer ist dagegen|dagegen|wer ist gegen[^?.!]{0,60}|gegenstimmen)\\s*[?:]\\s*$W", true),
        rx("\\b$W\\s+(?:gegenstimmen?|nein-stimmen?|stimmen? dagegen)", true))
    private val ABSTAIN = listOf(
        rx("enthaltung(?:en)?\\s*[?:.]?\\s*$W", true),
        rx("\\b$W\\s+enthaltung(?:en)?", true))
    private val SUBJECT_VOTE = rx("stimmen\\s+(?:wir\\s+)?(?:jetzt\\s+|nun\\s+|also\\s+)?(?:darüber\\s+|über\\s+)?ab\\s*[:,]\\s*([^.?!]{3,160})",
        true)
    private val SUBJECT_FOR = rx("wer ist für\\s+([^?.!]{3,120})\\?", true)
    private val AGENDA_ITEM = rx("(?:nächster|nächsten|zum)\\s+(?:punkt|tagesordnungspunkt|top)\\s*[:,]?\\s*(?:der|die|das|zum|zur)?\\s*([\\p{L}-]{4,})",
        true)

    private fun firstNumber(patterns: List<Regex>, text: String): Int? {
        for (p in patterns) for (m in p.findAll(text)) number(m.groupValues[1])?.let { return it }
        return null
    }

    private fun cleanSubject(s: String): String {
        var t = s.trim().trimEnd(',', ';', ':')
        t = t.replace(rx("^(?:die|den|das|der|eine|einen|ein)\\s+", true), "")
        return t.replaceFirstChar { it.uppercase() }
    }

    /**
     * Abstimmungen mit ausdrücklichem Ergebnis ("Der Antrag ist angenommen.") samt genannter Stimmen und
     * Gegenstand ("Wir stimmen ab: …", "Wer ist für …?"). Nur Ergebnisse in einem Abstimmungszusammenhang.
     */
    fun detectVotes(segments: List<Segment>): List<Vote> {
        val votes = mutableListOf<Vote>()
        var lastEnd = -1
        for ((i, seg) in segments.withIndex()) {
            val m = RESULT.findAll(seg.text).lastOrNull() ?: continue
            // Fenster: dieser und die zwei vorigen Beiträge (Zählung über mehrere Beiträge verteilt), nicht vor die letzte Abstimmung
            val from = maxOf(lastEnd + 1, i - 2)
            val window = segments.subList(from, i + 1).joinToString(" ") { it.text }
            if (!VOTE_CONTEXT.containsMatchIn(window)) continue
            // Nur der Teil bis zum Ergebnis (danach beginnt oft der nächste Punkt)
            val cut = window.length - (seg.text.length - m.range.last - 1)
            val w = window.substring(0, cut.coerceIn(0, window.length))
            var subject = SUBJECT_VOTE.findAll(w).lastOrNull()?.groupValues?.get(1)
                ?.takeUnless { it.trim().lowercase().startsWith("wer ") }
                ?: SUBJECT_FOR.findAll(w).lastOrNull()?.groupValues?.get(1)
            subject = subject?.let(::cleanSubject)?.takeIf { it.length >= 3 }
            // Gegenstand ohne Thema ("Erhöhung auf vierzig Euro"): Tagesordnungspunkt davor ergänzen
            if (subject != null) {
                val ctx = segments.subList(maxOf(0, i - 3), i + 1).joinToString(" ") { it.text }
                AGENDA_ITEM.findAll(ctx).lastOrNull()?.groupValues?.get(1)?.let { item ->
                    if (!subject!!.lowercase().contains(item.lowercase().take(6))) subject = "$item: $subject"
                }
            }
            val unanimous = m.groupValues[1].trim().equals("einstimmig", true)
            votes += Vote(subject, m.groupValues[2].equals("angenommen", true), unanimous,
                if (unanimous) null else firstNumber(YES, w), if (unanimous) null else firstNumber(NO, w),
                if (unanimous) null else firstNumber(ABSTAIN, w), i)
            lastEnd = i
        }
        return votes
    }

    private val STOP = setOf("über", "wird", "werden", "wurde", "eine", "einen", "einem", "einer", "dass", "sich",
        "nicht", "noch", "dann", "auch", "abstimmung", "antrag", "angenommen", "abgelehnt", "beschluss",
        "beschlossen", "stimmen", "enthaltung", "enthaltungen", "einstimmig", "euro", "jahr", "danke", "euch",
        "ihnen", "gerne", "ordnung", "prima", "okay", "genau", "alles", "klar", "selbstverständlich", "selbst")

    internal fun stems(t: String): Set<String> = rx("[\\p{L}\\d]{4,}").findAll(t.lowercase())
        .map { it.value }.filter { it !in STOP }.map { it.take(5) }.toSet()

    private fun longWords(t: String): Set<String> = rx("[\\p{L}]{8,}").findAll(t.lowercase()).map { it.value }
        .filter { it !in STOP }.toSet()

    /** Lange Hauptwörter (groß geschrieben) einer Aufgabe – Verben wie "erstellen" zählen nicht. */
    private fun longNouns(t: String): Set<String> = rx("[\\p{Lu}][\\p{L}]{7,}").findAll(t).map { it.value.lowercase() }
        .filter { it !in STOP }.toSet()

    private val HAS_RESULT = rx("angenommen|abgelehnt", true)
    private val OLD_RESULT = rx("\\s*(?:[–—-]\\s*)?(?:abstimmung\\s*:.*|\\(?(?:der antrag )?(?:ist |wurde )?(?:einstimmig |mehrheitlich )?(?:angenommen|abgelehnt)\\)?\\.?)$",
        true)

    /**
     * Ergänzt Beschlüsse um erkannte Abstimmungen: passender Beschluss (gleiche Stelle oder gleiche
     * Wörter) bekommt das Ergebnis, sonst wird die Abstimmung als eigener Beschluss angefügt.
     */
    fun mergeVotes(decisions: List<String>, refs: List<Int>, votes: List<Vote>): Pair<List<String>, List<Int>> {
        val d = decisions.toMutableList()
        val r = decisions.indices.map { refs.getOrElse(it) { -1 } }.toMutableList()
        val used = mutableSetOf<Int>()
        for (v in votes) {
            val vs = v.subject?.let(::stems) ?: emptySet()
            val j = d.indices.filter { it !in used }.maxByOrNull { k ->
                val overlap = (stems(d[k]) intersect vs).size
                val near = r[k] in (v.ref - 2)..v.ref
                // Fremdes Ergebnis im Beschluss: nur bei klarer Wortgleichheit
                val needed = if (vs.isEmpty()) 0 else minOf(2, vs.size)
                when {
                    overlap >= needed && vs.isNotEmpty() -> 10 + overlap + (if (near) 1 else 0)
                    near && vs.isEmpty() -> 5
                    near && overlap >= 1 -> 4
                    else -> -1
                }
            }?.takeIf { k ->
                val overlap = (stems(d[k]) intersect vs).size
                val near = r[k] in (v.ref - 2)..v.ref
                (vs.isNotEmpty() && overlap >= minOf(2, vs.size)) || (near && (vs.isEmpty() || overlap >= 1))
            }
            if (j != null) {
                used += j
                val text = d[j]
                val hasCounts = rx("\\d").containsMatchIn(text.substringAfter("Abstimmung", ""))
                val correct = rx("\\b${v.result}\\b", true).containsMatchIn(text) &&
                    !rx("\\b${if (v.accepted) "abgelehnt" else "angenommen"}\\b", true).containsMatchIn(text)
                if (!correct || (!hasCounts && !v.unanimous && (v.yes != null || v.no != null))) {
                    val base = text.replace(OLD_RESULT, "").trim().trimEnd('.', ',', ';', ' ')
                    d[j] = "$base – ${v.suffix()}"
                }
                if (r[j] < 0) r[j] = v.ref
            } else {
                val t = v.text() ?: continue
                d += t; r += v.ref; used += d.size - 1
            }
        }
        return d to r
    }

    // ------------------------------------------------------------ Wer ist wer

    private val ADDRESS = rx("\\b(Frau|Herr)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})")
    private val THANKS = rx("(?:danke|dankeschön|vielen dank|danke schön)(?:\\s+ihnen)?\\s*,?\\s*$", true)
    private val GREETING = rx("(?:hallo|guten morgen|guten tag|guten abend|willkommen|morgen)\\s*,?\\s*$", true)
    private val SECOND_PERSON = rx("\\b(Sie|Ihnen|Ihr|Ihre|Ihren|bitte)\\b")

    private fun sentenceAround(text: String, pos: Int): String {
        val start = text.lastIndexOfAny(charArrayOf('.', '?', '!'), (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOfAny(charArrayOf('.', '?', '!'), pos).let { if (it < 0) text.length else it + 1 }
        return text.substring(start, end)
    }

    /** "Frau Becker" für Anrede, "Herr Wagner" – vereinheitlicht. */
    private fun formal(title: String, name: String) = "${if (title == "Herrn") "Herr" else title} $name"

    /**
     * Leitet aus direkter Anrede ab, welche Sprecherbezeichnung zu welchem Namen gehört:
     * "Frau Becker, können Sie …?" -> wer direkt danach spricht, ist Frau Becker;
     * "Danke, Herr Schröder." -> wer direkt davor sprach, ist Herr Schröder.
     * Nur eindeutige Zuordnungen (kein Widerspruch, niemand spricht sich selbst an).
     */
    fun speakerNames(segments: List<Segment>): Map<Int, String> {
        val votes = mutableMapOf<Int, MutableMap<String, Int>>()
        val notMe = mutableMapOf<Int, MutableSet<String>>()
        for ((i, seg) in segments.withIndex()) {
            for (m in ADDRESS.findAll(seg.text)) {
                val name = formal(m.groupValues[1], m.groupValues[2])
                notMe.getOrPut(seg.speakerId) { mutableSetOf() } += name
                val before = seg.text.substring(0, m.range.first)
                val after = seg.text.substring(m.range.last + 1)
                val vocative = after.isEmpty() || after[0] in ",?!" || (after[0] == '.' && THANKS.containsMatchIn(before))
                if (!vocative) continue
                val target = when {
                    THANKS.containsMatchIn(before) -> segments.getOrNull(i - 1)
                    GREETING.containsMatchIn(before) || SECOND_PERSON.containsMatchIn(sentenceAround(seg.text, m.range.first)) ->
                        segments.getOrNull(i + 1)
                    else -> null
                } ?: continue
                if (target.speakerId == seg.speakerId) continue
                votes.getOrPut(target.speakerId) { mutableMapOf() }.merge(name, 1, Int::plus)
            }
        }
        val best = votes.mapNotNull { (spk, names) ->
            val ranked = names.filterKeys { it !in (notMe[spk] ?: emptySet<String>()) }.entries.sortedByDescending { it.value }
            if (ranked.isEmpty() || (ranked.size > 1 && ranked[0].value == ranked[1].value)) null
            else spk to ranked[0].key
        }
        // Ein Name nur für eine Sprecherbezeichnung
        val dup = best.groupBy { it.second }.filter { it.value.size > 1 }.keys
        return best.filter { it.second !in dup }.toMap()
    }

    // ------------------------------------------------------------ Verantwortliche

    private val SELF_COMMIT = rx("\\b(mache ich|übernehme ich|erledige ich|kläre ich|das kann ich|ich kümmere mich|ich übernehme|ich mache|ich schreibe|ich melde|ich messe|ich rufe|ich kläre|ich erstelle|ich bestelle|ich organisiere|ich trage|ich dokumentiere|ich schicke|ich sende|ich frage|ich gebe|ich bereite|ich prüfe|ich besorge|ich hole|ich buche|ich lade)\\b",
        true)
    private val REQUEST = rx("\\b(?i:können|könnten|würden|kümmern|übernehmen|holen|schalten|erstellen|bestellen|klären|laden|melden|schicken|senden|prüfen|bereiten|organisieren|tragen|rufen|schreiben)\\s+Sie\\b")

    /**
     * Zuständige Person einer Aufgabe aus dem Beleg: Anrede ("Frau X, …"), Selbstverpflichtung
     * ("mache ich", "ich kümmere mich") oder Bitte an das Gegenüber ("Können Sie …?" -> wer antwortet).
     * Gibt null zurück, wenn der Beleg nichts Eindeutiges hergibt.
     */
    fun ownerFromRef(ref: Int, segments: List<Segment>, names: Map<Int, String>, label: (Int) -> String,
                     taskText: String = ""): String? {
        val seg = segments.getOrNull(ref) ?: return null
        fun who(spk: Int) = names[spk] ?: label(spk)
        val sentence = sentenceFor(taskText, seg.text)
        // Anrede im Satz ("Frau Becker, bitte bestellen Sie …", "Frau Schmidt beantragt …")
        ADDRESS.find(sentence)?.let { m ->
            val name = formal(m.groupValues[1], m.groupValues[2])
            if (!name.equals(names[seg.speakerId], true) && !name.equals(label(seg.speakerId), true)) return name
        }
        val self = SELF_COMMIT.containsMatchIn(sentence)
        val request = REQUEST.containsMatchIn(sentence)
        if (self && !request) return who(seg.speakerId)
        // Bitte ohne Namen: gemeint ist das Gegenüber (wer direkt antwortet, sonst wer zuvor sprach)
        if (request && !self) {
            val other = listOfNotNull(segments.getOrNull(ref + 1), segments.getOrNull(ref - 1))
                .firstOrNull { it.speakerId != seg.speakerId } ?: return null
            return who(other.speakerId)
        }
        return null
    }

    private fun sentenceFor(taskText: String, text: String): String {
        val sentences = text.split(rx("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
        if (sentences.size <= 1 || taskText.isBlank()) return text
        val w = stems(taskText)
        val best = sentences.maxByOrNull { (stems(it) intersect w).size } ?: return text
        return if ((stems(best) intersect w).isEmpty()) text else best
    }

    data class Commitment(val ref: Int, val sentence: String)

    private val STAFF_DOES = rx("\\b(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?[A-ZÄÖÜ][\\p{L}-]{1,30}\\s+(?:übernimmt|organisiert|beantragt|kümmert sich|klärt|erstellt|bestellt|fordert|ruft|schreibt|meldet|prüft|kontaktiert|informiert|besorgt|holt|plant|dokumentiert|trägt|schickt|lädt)\\b")
    private val PLEASE_DO = rx("\\bbitte\\s+(?:[\\p{L}-]+\\s+){0,5}[\\p{L}]+(?:en|ern|eln)\\s*[.!]?$", true)

    /**
     * Sätze, die eine Aufgabe vergeben oder zusagen: Selbstverpflichtung mit Inhalt ("Ich kümmere mich um die
     * Genehmigung"), Bitte an jemanden ("Können Sie … ?", "laden Sie bitte … ein"), Zuweisung in dritter Person
     * ("Frau Schmidt beantragt …") und Bitten ohne Person ("bitte auf Flüssigkeit achten").
     * Bloße Zusagen ohne Inhalt ("Ja, mache ich.") zählen nicht – dort steht die Aufgabe im Satz davor.
     */
    fun commitments(segments: List<Segment>): List<Commitment> = segments.flatMapIndexed { i, seg ->
        seg.text.split(rx("(?<=[.?!])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.filter { sen ->
            val content = stems(SELF_COMMIT.replace(sen, " ")).size
            (SELF_COMMIT.containsMatchIn(sen) && content >= 2) || REQUEST.containsMatchIn(sen) ||
                STAFF_DOES.containsMatchIn(sen) || PLEASE_DO.containsMatchIn(sen)
        }.map { Commitment(i, it) }
    }

    // ------------------------------------------------------------ Fristen

    private val DEADLINE = rx("\\bbis\\s+(?=((?:zum|zur|spätestens|ende|anfang|mitte|nächsten|nächste|kommenden|kommende|übermorgen|morgen|heute)?\\s*[^.,;!?]{2,28}))",
        true)

    private val ORDINALS = listOf("erst", "zweit", "dritt", "viert", "fünft", "sechst", "siebt", "acht", "neunt", "zehnt",
        "elft", "zwölft", "dreizehnt", "vierzehnt", "fünfzehnt", "sechzehnt", "siebzehnt", "achtzehnt", "neunzehnt",
        "zwanzigst", "einundzwanzigst", "zweiundzwanzigst", "dreiundzwanzigst", "vierundzwanzigst", "fünfundzwanzigst",
        "sechsundzwanzigst", "siebenundzwanzigst", "achtundzwanzigst", "neunundzwanzigst", "dreißigst", "einunddreißigst")
    private const val MONTHS = "januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember"
    private val ORDINAL_DATE = rx("\\b(${ORDINALS.sortedByDescending { it.length }.joinToString("|")})(?:e|en|er|es|em)\\s+($MONTHS)\\b", true)
    private val DOTTED_DATE = rx("(\\d{1,2})\\.\\s+($MONTHS)\\b", true)
    private val DAY_MONTH = rx("(\\d{1,2})\\s+($MONTHS)\\b", true)
    private val THIS_WEEK = rx("\\b(?:in\\s+)?(?:noch\\s+)?diese[rn]?\\s+woche\\b", true)

    /** "zum zwanzigsten Dezember" -> "zum 20. Dezember" */
    fun normalizeOrdinals(text: String): String = ORDINAL_DATE.replace(text) { m ->
        "${ORDINALS.indexOf(m.groupValues[1].lowercase()) + 1}. ${m.groupValues[2]}"
    }

    /** Frist: "zwanzigsten Dezember" -> "20. Dezember", "möglichst diese Woche" -> "Ende dieser Woche". */
    fun normalizeDue(text: String): String {
        var t = normalizeOrdinals(text)
        if (THIS_WEEK.containsMatchIn(t) && !t.contains("ende", ignoreCase = true)) t = "Ende dieser Woche"
        return t
    }

    private val DUE_FILLER = setOf("bis", "zum", "zur", "ende", "anfang", "mitte", "spätestens", "noch", "nach", "der",
        "dem", "den", "des", "möglichst", "etwa", "ca", "am", "im", "in")

    /**
     * Steht die Frist des Modells so im Gespräch (Beleg oder Nachbarbeitrag)? Erfundene oder selbst
     * umgerechnete Angaben ("nach dem Besuch", "26.11.2026") gelten als nicht belegt.
     */
    fun dueSupported(due: String, context: String): Boolean {
        val words = rx("[\\p{L}\\d]+").findAll(normalizeDue(due).lowercase()).map { it.value }
            .filter { it !in DUE_FILLER && it.length >= 2 }.toList()
        if (words.isEmpty()) return true
        val ctx = normalizeOrdinals(context).lowercase()
        return words.any { w -> ctx.contains(if (w.length > 5) w.take(5) else w) }
    }

    /** Eindeutige Frist aus dem Beleg ("bis Freitag", "bis Ende März"), sonst null. */
    fun deadlineFromRef(raw: String, meeting: LocalDate): String? {
        // "20. Dezember": der Punkt beendet hier keinen Satz
        val text = DOTTED_DATE.replace(normalizeOrdinals(raw), "$1 $2")
        val found = DEADLINE.findAll(text).mapNotNull { m ->
            val words = m.groupValues[1].trim().split(rx("\\s+"))
            // kürzester Anfang, den der DateResolver versteht ("bis Freitag drei Angebote" -> "Freitag")
            (1..minOf(4, words.size)).map { words.take(it).joinToString(" ") }
                .firstOrNull { DateResolver.parse(it, meeting) != null || it.lowercase().endsWith("sitzung") }
        }.distinct().toList()
        return found.singleOrNull()?.let { DAY_MONTH.replace(it, "$1. $2") }
    }

    // ------------------------------------------------------------ Erledigte Aufgaben

    private val DONE = rx("\\b(erledigt|liegt vor|liegen vor|ist fertig|sind fertig|abgeschlossen|habe ich gemacht|ist gemacht|ist da|sind da|wurde(n)? (bestellt|verschickt|gemacht|eingereicht))\\b",
        true)
    private val NOT_DONE = rx("\\b(noch offen|nicht erledigt|noch nicht|warte(n)? noch|steht noch aus|fehlt noch|bleibt offen)\\b",
        true)

    /**
     * Offene Aufgaben (Schlüssel -> Text), die laut Transkript erledigt sind: ein Beitrag sagt "erledigt",
     * "liegen vor" o. Ä., verneint nichts und teilt mindestens zwei Wortstämme mit der Aufgabe.
     */
    fun completedFromTranscript(open: Map<String, String>, segments: List<Segment>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for ((i, seg) in segments.withIndex()) {
            if (!DONE.containsMatchIn(seg.text) || NOT_DONE.containsMatchIn(seg.text)) continue
            val ss = stems(seg.text)
            val long = longWords(seg.text)
            // zwei gemeinsame Wortstämme oder ein langes, unverwechselbares Wort ("Gästeliste")
            val hits = open.filter { (_, t) -> (stems(t) intersect ss).size >= 2 || (longNouns(t) intersect long).isNotEmpty() }.keys
            if (hits.size == 1) out.putIfAbsent(hits.first(), i)
        }
        return out
    }

    /** Ein Beitrag zur Aufgabe sagt ausdrücklich, dass sie noch offen ist (und nicht, dass sie erledigt ist). */
    fun stillOpen(taskText: String, segments: List<Segment>): Boolean {
        val ts = stems(taskText)
        if (ts.size < 2) return false
        // Beitrag samt Frage davor ("Wie sieht es mit der Telefonanlage aus?" – "Das ist noch offen.")
        return segments.withIndex().any { (i, seg) ->
            val ctx = stems(seg.text) + (segments.getOrNull(i - 1)?.let { stems(it.text) } ?: emptySet())
            NOT_DONE.containsMatchIn(seg.text) && !DONE.containsMatchIn(seg.text) && (ctx intersect ts).size >= 2
        }
    }

    // ------------------------------------------------------------ Gliederung

    private val MISC = rx("^(verschiedenes|sonstiges|varia|allgemeines)", true)

    /**
     * Ordnet Themen ohne "TOP"-Titel der Tagesordnung zu (gemeinsame Wortstämme im Titel, sonst in den
     * Punkten). Nicht zuordenbare Themen gehen nach "Verschiedenes", wenn es diesen TOP gibt, sonst
     * bleiben sie unverändert. Themen zum gleichen TOP werden zusammengeführt.
     */
    fun alignAgenda(topics: List<Topic>, agenda: List<String>): List<Topic> {
        if (agenda.isEmpty() || topics.isEmpty()) return topics
        val misc = agenda.indexOfFirst { MISC.containsMatchIn(it.trim()) }
        val aStems = agenda.map { stems(it) }
        fun bestIn(text: String): Int? {
            val s = stems(text)
            val scored = aStems.mapIndexed { k, a -> k to (a intersect s).size }.filter { it.second > 0 }
            val top = scored.maxByOrNull { it.second } ?: return null
            return if (scored.count { it.second == top.second } == 1) top.first else null
        }
        val mapped = topics.map { t ->
            val existing = rx("^TOP\\s*(\\d+)").find(t.title)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            val k = existing?.takeIf { it in agenda.indices }
                ?: bestIn(t.title) ?: bestIn(t.points.joinToString(" ")) ?: misc.takeIf { it >= 0 }
            k to t
        }
        val byTop = linkedMapOf<Int, Topic>()
        val rest = mutableListOf<Topic>()
        for ((k, t) in mapped) {
            if (k == null) { rest += t; continue }
            val prev = byTop[k]
            byTop[k] = if (prev == null) Topic("TOP ${k + 1}: ${agenda[k].trim()}", t.points, t.refs)
            else Topic(prev.title, prev.points + t.points,
                prev.points.indices.map { prev.refAt(it) } + t.points.indices.map { t.refAt(it) })
        }
        return byTop.toSortedMap().values.toList() + rest
    }

    // ------------------------------------------------------------ Sensible Daten

    private val STREET = rx("\\b([A-ZÄÖÜ][\\p{L}-]*(?:straße|strasse|str\\.|weg|platz|gasse|allee|ring|damm|ufer))\\s+((?:\\d{1,4}\\s?[a-z]?\\b|(?:ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn|zwanzig)\\b))")
    private val DIGIT_WORDS = "(?:null|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|\\d)"
    private val PHONE = rx("\\b(?:nummer|telefon|telefonnummer|handy|tel\\.?|erreichbar unter)\\s+(?:ist\\s+)?((?:$DIGIT_WORDS[\\s,/-]*){6,})",
        true)

    /** Adressen ("Lindenstraße zwölf") und Telefonnummern im Transkript. */
    fun addressesAndPhones(segments: List<Segment>): List<String> = segments.flatMap { seg ->
        STREET.findAll(seg.text).map { it.value.trim() }.toList() +
            PHONE.findAll(seg.text).map { it.groupValues[1].trim().trimEnd(',', ' ') }.toList()
    }.distinctBy { it.lowercase() }
}
