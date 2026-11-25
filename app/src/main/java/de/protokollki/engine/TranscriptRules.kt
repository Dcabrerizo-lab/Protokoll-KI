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
    private val RESULT = rx("\\b(einstimmig\\s+|mehrheitlich\\s+)?(angenommen|abgelehnt|beschlossen)\\b", true)
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
    private val SUBJECT_MOTION = rx("(?:der antrag lautet|antrag auf|antrag über|antrag zur?|antrag:)\\s*[:,]?\\s*([^.?!]{3,140})", true)
    private val VOTE_QUESTION = rx("(?:wer ist dafür|wer stimmt dafür|alle dafür|wer ist für|dafür\\s*\\?)", true)
    private val LEAD_IN = rx("^(?:dann|also|jetzt|nun|und)?\\s*(?:noch|zu|zum|zur)?\\s*", true)

    /** Antrag als Satz direkt vor der Abstimmungsfrage im selben Beitrag ("Dann noch die Entlastung des Kassenwarts. Alle dafür?") */
    private fun motionBefore(text: String): String? {
        val q = VOTE_QUESTION.find(text) ?: return null
        val before = text.substring(0, q.range.first).trim()
        val sentence = before.split(rx("(?<=[.!?])\\s+")).lastOrNull()?.trim()?.trimEnd('.', ':', ' ') ?: return null
        if (sentence.endsWith("?") || stems(sentence).size < 2 || sentence.length > 140) return null
        return LEAD_IN.replace(sentence, "").trim().takeIf { it.length >= 3 }
    }

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
                ?: SUBJECT_MOTION.findAll(w).lastOrNull()?.groupValues?.get(1)
                ?: motionBefore(seg.text)
            subject = subject?.let(::cleanSubject)?.takeIf { it.length >= 3 }
            // Gegenstand ohne Thema ("Erhöhung auf vierzig Euro"): Tagesordnungspunkt davor ergänzen
            if (subject != null) {
                val ctx = segments.subList(maxOf(0, i - 3), i + 1).joinToString(" ") { it.text }
                AGENDA_ITEM.findAll(ctx).lastOrNull()?.groupValues?.get(1)?.let { item ->
                    if (!subject!!.lowercase().contains(item.lowercase().take(6))) subject = "$item: $subject"
                }
            }
            val unanimous = m.groupValues[1].trim().equals("einstimmig", true)
            votes += Vote(subject, !m.groupValues[2].equals("abgelehnt", true), unanimous,
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
    /** Anrede ohne Komma (Spracherkennung): "Frau Becker können Sie …" */
    private val BARE_VOCATIVE = rx("^\\s+(?:bitte\\s+)?(?i:können|könnten|würden|kümmern|übernehmen|holen|schalten|erstellen|bestellen|klären|laden|melden|schicken|senden|prüfen|bereiten|organisieren|tragen|rufen|schreiben|wie)\\s+(?:Sie|sieht|geht)\\b")
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
    private val SUMMARY_ALSO = rx("^\\s+(?:ist|übernimmt|macht|kümmert sich um)\\s+also\\b")

    fun speakerNames(segments: List<Segment>): Map<Int, String> {
        val votes = mutableMapOf<Int, MutableMap<String, Int>>()
        val notMe = mutableMapOf<Int, MutableSet<String>>()
        for ((i, seg) in segments.withIndex()) {
            for (m in ADDRESS.findAll(seg.text)) {
                val name = formal(m.groupValues[1], m.groupValues[2])
                notMe.getOrPut(seg.speakerId) { mutableSetOf() } += name
                val before = seg.text.substring(0, m.range.first)
                val after = seg.text.substring(m.range.last + 1)
                val vocative = after.isEmpty() || after[0] in ",?!" || (after[0] == '.' && THANKS.containsMatchIn(before)) ||
                    BARE_VOCATIVE.containsMatchIn(after)
                // Zusammenfassung der Zusage davor: "Herr Wagner ist also Praxisanleiter" -> wer zuvor zugesagt hat
                if (!vocative && SUMMARY_ALSO.containsMatchIn(after)) {
                    val prev = segments.getOrNull(i - 1)
                    if (prev != null && prev.speakerId != seg.speakerId && (SELF_COMMIT.containsMatchIn(prev.text) || ANAPHORIC.containsMatchIn(prev.text)))
                        votes.getOrPut(prev.speakerId) { mutableMapOf() }.merge(name, 1, Int::plus)
                    continue
                }
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

    private val SELF_COMMIT = rx("\\b(mache ich|übernehme ich|erledige ich|kläre ich|das kann ich|ich kümmere mich|ich übernehme|ich mache|ich schreibe|ich melde|ich messe|ich rufe|ich kläre|ich erstelle|ich bestelle|ich organisiere|ich trage|ich dokumentiere|ich schicke|ich sende|ich frage|ich gebe|ich bereite|ich prüfe|ich besorge|ich hole|ich buche|ich lade|ich sammle|ich kontrolliere|ich informiere|ich kontaktiere|ich plane|ich bringe|ich kaufe|ich reserviere|ich verschicke|ich sorge|ich aktualisiere|ich überprüfe|ich spreche|ich bereite)\\b",
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
        // Anrede oder Zuweisung im Satz ("Frau Becker, bitte bestellen Sie …", "Frau Schmidt beantragt …")
        assignedName(sentence)?.let { name ->
            if (!name.equals(names[seg.speakerId], true) && !name.equals(label(seg.speakerId), true)) return name
        }
        // Beides im Satz ("Ich sammle das Geld ein, bitte bis März überweisen"): was zuerst kommt, zählt
        val selfAt = SELF_COMMIT.find(sentence)?.range?.first
        val reqAt = listOfNotNull(REQUEST.find(sentence)?.range?.first, PLEASE_DO.find(sentence)?.range?.first).minOrNull()
        val self = selfAt != null && (reqAt == null || selfAt < reqAt)
        val request = reqAt != null && (selfAt == null || reqAt < selfAt)
        if (self && !request) {
            // Bloße Zusage ("Ja, das mache ich.") auf eine Bitte mit Anrede: die angesprochene Person
            if (stems(SELF_COMMIT.replace(sentence, " ")).isEmpty()) {
                val prev = segments.getOrNull(ref - 1)
                if (prev != null && prev.speakerId != seg.speakerId) vocativeName(prev.text)?.let { return it }
            }
            return who(seg.speakerId)
        }
        // Bitte ohne Namen: gemeint ist das Gegenüber (wer direkt antwortet, sonst wer zuvor sprach)
        if (request && !self) {
            val other = listOfNotNull(segments.getOrNull(ref + 1), segments.getOrNull(ref - 1))
                .firstOrNull { it.speakerId != seg.speakerId } ?: return null
            return who(other.speakerId)
        }
        return null
    }

    /**
     * Eindeutige Zuweisung im Belegsatz ("…, Herr Neumann übernimmt das Assessment"), die eine Namensangabe des
     * Modells überstimmt. Selbstverpflichtungen zählen nur, wenn die Sprecherin namentlich bekannt ist.
     */
    fun explicitOwner(ref: Int, segments: List<Segment>, names: Map<Int, String>, taskText: String): String? {
        val seg = segments.getOrNull(ref) ?: return null
        val sentence = sentenceFor(taskText, seg.text)
        if ((stems(sentence) intersect stems(taskText)).size < 2) return null
        assignedName(sentence)?.takeIf { !it.equals(names[seg.speakerId], true) }?.let { return it }
        val selfAt = SELF_COMMIT.find(sentence)?.range?.first ?: return null
        val reqAt = listOfNotNull(REQUEST.find(sentence)?.range?.first, PLEASE_DO.find(sentence)?.range?.first).minOrNull()
        return if (reqAt == null || selfAt < reqAt) names[seg.speakerId] else null
    }

    /** "Frau X, …" (Anrede mit Komma/Fragezeichen) oder "Frau X beantragt …" (Zuweisung), sonst null. */
    fun assignedName(sentence: String): String? {
        for (m in ADDRESS.findAll(sentence)) {
            val after = sentence.substring(m.range.last + 1)
            if (after.isNotEmpty() && after[0] in ",?!" || STAFF_VERB_AFTER.containsMatchIn(after) || BARE_VOCATIVE.containsMatchIn(after))
                return formal(m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    private val VOCATIVE_LEAD = Regex("^\\s*(?:(?i:ja|gut|danke|okay|ok|also|und|richtig|prima|super|genau|schön|gerne|so|dann|bitte|hallo|verstanden|alles klar)[,.!]?\\s+)*")
    private val PREP_BEFORE = rx("(?i:bei|mit|von|vom|für|an|zu|zur|um|über|wegen|nach|aus)\\s+$")

    /** Im Gespräch direkt angesprochene oder beauftragte Personen ("Frau Kaiser, …", "Herr Wolf übernimmt …"): Team. */
    fun staffNames(segments: List<Segment>): Set<String> = segments.flatMap { seg ->
        seg.text.split(Regex("(?<=[.!?])\\s+")).flatMap { sentence ->
            ADDRESS.findAll(sentence).filter { m ->
                val after = sentence.substring(m.range.last + 1)
                val before = sentence.substring(0, m.range.first)
                STAFF_VERB_AFTER.containsMatchIn(after) && !PREP_BEFORE.containsMatchIn(before) ||
                    // Anrede nur am Satzanfang ("Gut, Frau Kaiser, …"), nicht "Fallbesprechung Frau Hoffmann, …"
                    VOCATIVE_LEAD.matches(before) && (after.isNotEmpty() && after[0] in ",?!" || BARE_VOCATIVE.containsMatchIn(after))
            }.map { formal(it.groupValues[1], it.groupValues[2]) }.toList()
        }
    }.toSet()

    private fun vocativeName(text: String): String? = ADDRESS.findAll(text).firstOrNull { m ->
        val after = text.substring(m.range.last + 1)
        (after.isNotEmpty() && after[0] in ",?" || BARE_VOCATIVE.containsMatchIn(after)) &&
            SECOND_PERSON.containsMatchIn(sentenceAround(text, m.range.first))
    }?.let { formal(it.groupValues[1], it.groupValues[2]) }

    /** Hat die Person [spk] im Umfeld des Belegs selbst etwas zugesagt? */
    fun selfCommittedNear(spk: Int, ref: Int, segments: List<Segment>): Boolean =
        (ref - 1..ref + 1).any { i -> segments.getOrNull(i)?.let { it.speakerId == spk && SELF_COMMIT.containsMatchIn(it.text) } == true }

    private fun sentenceFor(taskText: String, text: String): String {
        val sentences = text.split(rx("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
        if (sentences.size <= 1 || taskText.isBlank()) return text
        val w = stems(taskText)
        val best = sentences.maxByOrNull { (stems(it) intersect w).size } ?: return text
        return if ((stems(best) intersect w).isEmpty()) text else best
    }

    enum class Kind { SELF, REQUEST, ASSIGN, PLEASE }

    data class Commitment(val ref: Int, val sentence: String, val kind: Kind = Kind.SELF)

    private const val STAFF_VERBS = "übernimmt|organisiert|beantragt|kümmert sich|klärt|erstellt|bestellt|fordert|ruft|schreibt|meldet|prüft|kontaktiert|informiert|besorgt|holt|plant|dokumentiert|trägt|schickt|lädt"
    private val STAFF_VERB_AFTER = rx("^\\s+(?:$STAFF_VERBS)\\b")
    private val STAFF_DOES = rx("\\b(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?[A-ZÄÖÜ][\\p{L}-]{1,30}\\s+(?:übernimmt|organisiert|beantragt|kümmert sich|klärt|erstellt|bestellt|fordert|ruft|schreibt|meldet|prüft|kontaktiert|informiert|besorgt|holt|plant|dokumentiert|trägt|schickt|lädt)\\b")
    private val PLEASE_DO = rx("\\bbitte\\s+(?:[\\p{L}-]+\\s+){0,5}[\\p{L}]+(?:en|ern|eln)\\s*[.!]?$", true)

    /**
     * Sätze, die eine Aufgabe vergeben oder zusagen: Selbstverpflichtung mit Inhalt ("Ich kümmere mich um die
     * Genehmigung"), Bitte an jemanden ("Können Sie … ?", "laden Sie bitte … ein"), Zuweisung in dritter Person
     * ("Frau Schmidt beantragt …") und Bitten ohne Person ("bitte auf Flüssigkeit achten").
     * Bloße Zusagen ohne Inhalt ("Ja, mache ich.") zählen nicht – dort steht die Aufgabe im Satz davor.
     */
    fun commitments(segments: List<Segment>): List<Commitment> = segments.flatMapIndexed { i, seg ->
        seg.text.split(rx("(?<=[.?!])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { sen ->
            val content = stems(SELF_COMMIT.replace(sen, " ")).size
            val kind = when {
                ANAPHORIC.containsMatchIn(sen) -> null      // "Das kann ich machen" – Inhalt steht im Beitrag davor
                REQUEST.containsMatchIn(sen) -> Kind.REQUEST
                STAFF_DOES.containsMatchIn(sen) -> Kind.ASSIGN
                SELF_COMMIT.containsMatchIn(sen) && content >= 2 -> Kind.SELF
                PLEASE_DO.containsMatchIn(sen) -> Kind.PLEASE
                else -> null
            }
            kind?.let { Commitment(i, sen, it) }
        }
    }

    private val ANAPHORIC = rx("^(?:ja|gut|okay|gerne|klar)?\\s*,?\\s*(?:das|die|den|dies|es)\\s+(?:kann|mache|übernehme|erledige|kläre|machen)\\s+(?:ich|wir)\\b", true)
    private val DONE_BY_ME = rx("^(.{8,}?),\\s*(?:das|die|den|dies)\\s+(?:mache|übernehme|erledige|kläre)\\s+ich\\s*(.*)$", true)

    private val MUST_FIRST = rx("^(muss|müssen|sollte|sollten)\\s+(der|die|das|den|ein|eine)\\s+(\\S+)\\s+(.+)$", true)

    /** Antwort auf eine Bitte ("Ja, mache ich bis Mittwoch.", "In Ordnung, …") */
    fun isConfirmation(text: String): Boolean = CONFIRM.containsMatchIn(text) || SELF_COMMIT.containsMatchIn(text)

    private val CONFIRM = rx("^\\s*(?:ja|jawohl|gerne|gern|mache ich|in ordnung|okay|ok|verstanden|klar|alles klar|wird gemacht|geht klar)\\b", true)

    // ------------------------------------------------------------ Beschlüsse und Gesundheitsdaten

    private val DECIDED = rx("\\b(?:beschließen wir|ist beschlossen|haben wir beschlossen|entscheiden wir|legen wir fest|wird beschlossen|(?:haben wir|wir haben) (?:entschieden|beschlossen|festgelegt)(?:,?\\s*dass)?)\\s*[:,]?\\s*(.*)$", true)

    /** "Dann bestellen wir bei Holzmann." – Festlegung der Runde (keine Verfahrensschritte wie "dann schauen wir") */
    private val WE_DECIDE = rx("^(?:gut|okay|prima|danke)?\\s*[,.]?\\s*(?:dann|also)\\s+(\\p{L}+en)\\s+wir\\s+(.{4,})$", true)
    private val PROCEDURE = setOf("schauen", "kommen", "sprechen", "besprechen", "gehen", "beginnen", "fangen", "klären",
        "sehen", "hören", "stimmen", "schließen", "reden", "starten", "warten", "überlegen", "prüfen", "machen", "treffen",
        "fahren", "hoffen", "müssen", "können", "sollten", "wollen", "haben", "brauchen", "nehmen")

    /** Ausdrücklich gefasste Beschlüsse ("Dann beschließen wir: …", "Dann bestellen wir bei X.") mit Inhalt und Beleg. */
    fun explicitDecisions(segments: List<Segment>): List<Pair<String, Int>> = segments.flatMapIndexed { i, seg ->
        seg.text.split(rx("(?<=[.?!])\\s+")).mapNotNull { sen ->
            val body = DECIDED.find(sen)?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', ' ')
            if (body != null) return@mapNotNull if (stems(body).size < 2) null else body.replaceFirstChar { it.uppercase() } + "." to i
            val we = WE_DECIDE.find(sen.trim().trimEnd('.', '!')) ?: return@mapNotNull null
            val verb = we.groupValues[1].lowercase()
            val rest = we.groupValues[2].trim()
            if (verb in PROCEDURE || rest.startsWith("uns") || rest.contains("?") || stems(rest).isEmpty()) null
            else "Wir $verb $rest." to i
        }
    }

    private val DIAGNOSES = rx("\\bDiagnosen?\\s*:?\\s+([^.;]+)", true)
    private val CARE_LEVEL = rx("\\bPflegegrad\\s+(?:\\d|eins|zwei|drei|vier|fünf)\\b", true)

    /** Genannte Diagnosen ("Diagnosen Diabetes mellitus Typ zwei, Herzinsuffizienz und Demenz") und Pflegegrade. */
    fun healthData(segments: List<Segment>): List<String> = segments.flatMap { seg ->
        DIAGNOSES.findAll(seg.text).flatMap { m ->
            m.groupValues[1].split(rx(",\\s*|\\s+und\\s+")).map { it.trim() }
                .filter { it.length >= 4 }
        }.toList() + CARE_LEVEL.findAll(seg.text).map { it.value }.toList()
    }.distinctBy { it.lowercase() }

    // ------------------------------------------------------------ Aufgabentext aus einem Satz

    private val FILLER = rx("^(?:dann vereinbaren wir|dann beschließen wir|wir vereinbaren|wir beschließen|vereinbaren wir|beschließen wir|noch ein hinweis|unter verschiedenes|in ordnung|sehr gut|vereinbart|allgemein|außerdem|ja|gut|prima|okay|ok|genau|also|und|dann|danke)\\s*[,:]?\\s+", true)
    private val VOCATIVE_START = rx("^(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?[\\p{L}-]+(?:\\s*,\\s*|\\s+(?=(?:bitte\\s+)?\\p{L}+\\s+Sie\\b))")
    private val MODAL_Q = rx("^(?:können|könnten|würden)\\s+Sie\\s+(?:bitte\\s+)?(.+)$", true)
    private val IMPERATIVE = rx("^(?:bitte\\s+)?([\\p{L}]+(?:en|ern|eln))\\s+Sie\\s+(sich\\s+)?(?:bitte\\s+)?(.+)$", true)
    private val FIRST_PERSON = rx("^(?:das\\s+)?ich\\s+([\\p{L}]+e)\\s+(mich\\s+)?(.+)$", true)
    private val AND_FIRST = rx("^([\\p{L}]+e)\\s+(.+)$")
    private val THIRD_PERSON = rx("\\b(?:Frau|Herr)\\s+(?:Dr\\.\\s+)?[A-ZÄÖÜ][\\p{L}-]{1,30}\\s+($STAFF_VERBS)\\s+(.+)$")
    private val AND_THIRD = rx("^($STAFF_VERBS)\\s+(.+)$")
    private val PLEASE_TAIL = rx("^(.*?),?\\s*bitte\\s+(.+)$", true)
    private val PARTICLES = setOf("an", "auf", "aus", "ab", "ein", "zu", "vor", "nach", "mit", "weiter", "fest", "zurück",
        "um", "her", "hin", "weg", "los", "durch", "bereit", "fertig", "vorbei")
    private val IRREGULAR_3SG = mapOf("übernimmt" to "übernehmen", "trägt" to "tragen", "lädt" to "laden",
        "nimmt" to "nehmen", "gibt" to "geben", "hält" to "halten", "kümmert sich" to "kümmern")

    private fun infinitiveFrom1sg(v: String) = when {
        !v.endsWith("iere") && (v.endsWith("ere") || v.endsWith("ele")) -> v.dropLast(1) + "n"   // kümmere -> kümmern
        v.length > 3 && v.endsWith("le") && v[v.length - 3] !in "aeiouäöü" -> v.dropLast(2) + "eln" // sammle -> sammeln
        else -> v + "n"
    }

    private val NOT_VERB = setOf("eine", "keine", "seine", "ihre", "meine", "deine", "unsere", "alle", "beide", "diese",
        "jede", "welche", "einige", "viele", "andere", "dieselbe", "solche", "manche", "jene", "derselbe", "sowie", "ebenfalls")

    private fun infinitiveFrom3sg(v: String): String = IRREGULAR_3SG[v.lowercase()] ?: when {
        v.endsWith("iert") -> v.dropLast(1) + "en"
        v.endsWith("ert") || v.endsWith("elt") -> v.dropLast(1) + "n"
        v.endsWith("det") || v.endsWith("tet") -> v.dropLast(2) + "en"
        v.endsWith("t") -> v.dropLast(1) + "en"
        else -> v
    }

    /** "<Rest> <Verb>" mit abgetrennter Vorsilbe am Ende: "alle bis Freitag ein" + "laden" -> "alle bis Freitag einladen" */
    private fun verbLast(rest: String, verb: String, reflexive: Boolean): String {
        val words = rest.trim().split(rx("\\s+")).toMutableList()
        val particle = words.lastOrNull()?.lowercase()?.takeIf { it in PARTICLES && words.size > 1 }
        if (particle != null) words.removeAt(words.size - 1)
        val v = (particle ?: "") + verb.lowercase()
        return (if (reflexive) "sich " else "") + (words + v).joinToString(" ")
    }

    /**
     * Formuliert eine Zusage oder einen Auftrag als Aufgabe im Infinitiv, ohne Sprachmodell:
     * "Können Sie den Dienstplan bis Mittwoch aushängen?" -> "Den Dienstplan bis Mittwoch aushängen",
     * "Ich melde beide beim Sprachkurs an." -> "Beide beim Sprachkurs anmelden",
     * "Frau Wolf fordert eine Folgeverordnung an" -> "Eine Folgeverordnung anfordern".
     * Unbekannte Satzformen bleiben (ohne Füllwörter) wörtlich stehen.
     */
    fun phraseTask(sentence: String): String {
        var t = sentence.trim().trimEnd('.', '!', '?', ' ')
        repeat(4) { t = FILLER.replace(t, "") }
        t = VOCATIVE_START.replace(t, "")
        repeat(2) { t = FILLER.replace(t, "") }
        val out = DONE_BY_ME.find(t)?.let { m ->
                val tail = m.groupValues[2].trim()
                val body = MUST_FIRST.replace(m.groupValues[1].trim()) { x -> "${x.groupValues[2]} ${x.groupValues[3]} ${x.groupValues[1]} ${x.groupValues[4]}" }
                body + if (tail.isNotEmpty()) " ($tail)" else ""
            }
            ?: MODAL_Q.find(t)?.groupValues?.get(1)
            ?: IMPERATIVE.find(t)?.let { m ->
                val parts = m.groupValues[3].split(rx("\\s+und\\s+"), limit = 2)
                val first = verbLast(parts[0], m.groupValues[1], m.groupValues[2].isNotBlank())
                if (parts.size == 2) IMPERATIVE.find(parts[1].trim())?.let { m2 ->
                    "$first und ${verbLast(m2.groupValues[3], m2.groupValues[1], m2.groupValues[2].isNotBlank())}"
                } ?: "$first und ${parts[1]}" else first
            }
            ?: FIRST_PERSON.find(t)?.let { m ->
                val parts = m.groupValues[3].substringBefore(", bitte ").split(rx("\\s+und\\s+"), limit = 2)
                val first = verbLast(parts[0], infinitiveFrom1sg(m.groupValues[1]), m.groupValues[2].isNotBlank())
                if (parts.size == 2) AND_FIRST.find(parts[1].trim())?.takeIf { it.groupValues[1].length >= 4 &&
                        it.groupValues[1].lowercase() !in NOT_VERB && it.groupValues[1][0].isLowerCase() }?.let { m2 ->
                    "$first und ${verbLast(m2.groupValues[2], infinitiveFrom1sg(m2.groupValues[1]), false)}"
                } ?: "$first und ${parts[1]}" else first
            }
            ?: THIRD_PERSON.find(t)?.let { m ->
                val prefix = t.substring(0, m.range.first).trim().trimEnd(',', ':', ' ')
                val verb = m.groupValues[1]
                val parts = m.groupValues[2].substringBefore(", bitte ").split(rx("\\s+und\\s+"), limit = 2)
                val first = verbLast(parts[0], infinitiveFrom3sg(verb), verb.startsWith("kümmert"))
                val body = if (parts.size == 2) AND_THIRD.find(parts[1].trim())?.let { m2 ->
                    "$first und ${verbLast(m2.groupValues[2], infinitiveFrom3sg(m2.groupValues[1]), false)}"
                } ?: "$first und ${parts[1]}" else first
                if (prefix.isNotEmpty() && !FILLER.containsMatchIn("$prefix ")) "$body ($prefix)" else body
            }
            ?: PLEASE_TAIL.find(t)?.takeIf { PLEASE_DO.containsMatchIn(t) }?.let { m ->
                val ctx = m.groupValues[1].trim().trimEnd(',', ':')
                val todo = m.groupValues[2].trim()
                when {
                    ctx.isEmpty() -> todo
                    todo.contains(' ') -> "$todo ($ctx)"
                    else -> "$ctx: $todo"
                }
            }
            ?: t
        val clean = out.replace(",", "").replace(rx("\\s+"), " ").trim()
        return clean.replaceFirstChar { it.uppercase() }
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
    private val MONTH_END = rx("\\b($MONTHS)ende\\b", true)
    private val DAY_ONLY = rx("^zum\\s+\\d{1,2}\\.?$", true)
    private val DOTTED_DATE = rx("(\\d{1,2})\\.\\s+($MONTHS)\\b", true)
    private val DAY_MONTH = rx("(\\d{1,2})\\s+($MONTHS)\\b", true)
    private val THIS_WEEK = rx("\\b(?:in\\s+)?(?:noch\\s+)?diese[rn]?\\s+woche\\b", true)

    private val ORDINAL_DAY = rx("\\b(zum|am|ab)\\s+(${ORDINALS.sortedByDescending { it.length }.joinToString("|")})(?:e|en|er|es|em)\\b(?!\\s+(?:mal|schritt|punkt|teil|termin))", true)

    private val DAY_PART = rx("\\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)(morgen|vormittag|mittag|nachmittag|abend)\\b", true)

    /** "zum zwanzigsten Dezember" -> "zum 20. Dezember", "bis zum fünfzehnten" -> "bis zum 15.", "Freitagmittag" -> "Freitag mittag" */
    fun normalizeOrdinals(text: String): String {
        val t = ORDINAL_DATE.replace(DAY_PART.replace(text, "$1 $2")) { m -> "${ORDINALS.indexOf(m.groupValues[1].lowercase()) + 1}. ${m.groupValues[2]}" }
        return ORDINAL_DAY.replace(t) { m -> "${m.groupValues[1]} ${ORDINALS.indexOf(m.groupValues[2].lowercase()) + 1}." }
    }

    /** Frist: "zwanzigsten Dezember" -> "20. Dezember", "möglichst diese Woche" -> "Ende dieser Woche". */
    fun normalizeDue(text: String): String {
        var t = MONTH_END.replace(normalizeOrdinals(text)) { m -> "Ende ${m.groupValues[1].replaceFirstChar { it.uppercase() }}" }
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
            .filter { it !in DUE_FILLER && (it.length >= 2 || it[0].isDigit()) }.toList()
        if (words.isEmpty()) return true
        val ctx = cardinals(normalizeOrdinals(DAY_PART.replace(context, "$1 $2"))).lowercase()
        val ctxNumbers = rx("\\d+").findAll(ctx).map { it.value.trimStart('0') }.toSet()
        // Zahlen müssen stimmen ("2. Januar" ist nicht "zehnten Januar")
        val numbers = words.filter { w -> w.all { it.isDigit() } }.map { it.trimStart('0') }.filter { it.isNotEmpty() }
        if (numbers.any { it !in ctxNumbers }) return false
        // Wörter gleich oder nur in der Endung verschieden ("nächste"/"nächsten"), nicht "Sitzungstermin"/"Sitzung"
        val ctxWords = rx("[\\p{L}\\d]+").findAll(ctx).map { it.value }.toSet()
        return words.any { w -> ctxWords.any { x -> x == w ||
            (minOf(x.length, w.length) >= 4 && (x.startsWith(w) || w.startsWith(x)) && kotlin.math.abs(x.length - w.length) <= 2) } }
    }

    private val CARDINAL = rx("\\b(zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn|zwanzig)\\b", true)

    /** "um vierzehn Uhr" -> "um 14 Uhr" (nur für Vergleiche und Uhrzeiten) */
    private fun cardinals(text: String) = CARDINAL.replace(text) { number(it.value)?.toString() ?: it.value }

    private val SOFT_DUE = rx("(?<!guten\\s)\\b(in\\s+(?:einem|einer|zwei|drei|vier|fünf|sechs|acht|zehn|\\d+)\\s+(?:tagen|tag|wochen|woche|monaten|monat)|heute(?:\\s+noch)?|übermorgen|morgen(?:\\s+früh)?|nächste[nr]?\\s+woche|(?:noch\\s+)?diese\\s+woche|täglich|um\\s+(?:\\d{1,2}|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn|zwanzig)\\s+uhr)\\b", true)

    /** Zeitangabe ohne "bis" ("in zwei Wochen", "heute noch", "um vierzehn Uhr", "täglich"), nur wenn eindeutig. */
    fun softDeadline(text: String): String? {
        val found = SOFT_DUE.findAll(text).map { it.value.trim() }.distinctBy { it.lowercase() }.toList()
        val one = found.singleOrNull() ?: return null
        return normalizeDue(if (one.lowercase().endsWith("uhr")) cardinals(one) else one)
    }

    /** Eindeutige Frist aus dem Beleg ("bis Freitag", "bis Ende März"), sonst null. */
    fun deadlineFromRef(raw: String, meeting: LocalDate): String? {
        // "20. Dezember": der Punkt beendet hier keinen Satz
        val text = DOTTED_DATE.replace(normalizeOrdinals(raw), "$1 $2")
        val found = DEADLINE.findAll(text).mapNotNull { m ->
            val words = m.groupValues[1].trim().split(rx("\\s+"))
            // kürzester Anfang, den der DateResolver versteht ("bis Freitag drei Angebote" -> "Freitag")
            val cands = (1..minOf(4, words.size)).map { words.take(it).joinToString(" ") }
            cands.firstOrNull { DateResolver.parse(it, meeting) != null || it.lowercase().endsWith("sitzung") }
                ?: cands.firstOrNull { DAY_ONLY.matches(it) }
        }.distinct().toList()
        return found.singleOrNull()?.let { DAY_MONTH.replace(it, "$1. $2") }
            ?.let { if (DAY_ONLY.matches(it) && !it.endsWith(".")) "$it." else it }
    }

    // ------------------------------------------------------------ Erledigte Aufgaben

    private val DONE = rx("\\b(erledigt|liegt vor|liegen vor|ist fertig|sind fertig|abgeschlossen|habe ich gemacht|ist gemacht|ist da|sind da|wurde(n)? (bestellt|verschickt|gemacht|eingereicht))\\b",
        true)
    private val NOT_DONE = rx("\\b(noch offen|nicht erledigt|noch nicht|warte(n)? noch|steht noch aus|fehlt noch|bleibt offen)\\b",
        true)

    private val STATE_DONE = rx("\\b(?:ist|sind)\\s+(?:schon\\s+|bereits\\s+)?(?:unterschrieben|abgeschlossen|erledigt|bestellt|verschickt|eingereicht|ge\\p{L}{2,}(?:t|en))\\s*(?:[.!;]|$)", true)
    private val REPORTED = rx("\\b(?:habe|haben)\\s+(?:ich|wir)\\b(?!.*\\b(?:bis|noch nicht|nicht)\\b).*\\b(?:ge\\p{L}{2,}t|\\p{L}{3,}iert|abgeschlossen|erledigt)\\s*[.!]?$", true)

    /**
     * Der Belegsatz berichtet nur über bereits Geschehenes ("Für die Fortbildung habe ich den Kurs organisiert."):
     * daraus folgt keine neue Aufgabe.
     */
    fun reportedDone(taskText: String, text: String): Boolean {
        val sentence = sentenceFor(taskText, text)
        return (REPORTED.containsMatchIn(sentence) || STATE_DONE.containsMatchIn(sentence)) && !SELF_COMMIT.containsMatchIn(sentence) &&
            !REQUEST.containsMatchIn(sentence) && !PLEASE_DO.containsMatchIn(sentence)
    }

    private val PROCEDURAL = rx("^(?:\\p{L}+\\s+){0,3}?(?:eröffne\\p{L}*|begrüße\\p{L}*|schließe\\p{L}*|beende\\p{L}*)\\s+(?:\\p{L}+\\s+){0,3}(?:sitzung|besprechung|elternabend|versammlung|treffen|runde|teambesprechung|qualitätszirkel|abend)\\b", true)

    /** Ablauf der Sitzung selbst ("Den Elternabend eröffnen") ist keine Aufgabe. */
    fun procedural(taskText: String): Boolean = PROCEDURAL.containsMatchIn(taskText.trim())

    /** Gemeinsame längere Hauptwörter ("Wäschewagen") */
    fun sharedNouns(a: String, b: String): Set<String> = longNouns(a) intersect longNouns(b)

    /** Name aus einer Zuweisung im Satz ("Frau Albers übernimmt …"), sonst null */
    fun assignee(sentence: String): String? = STAFF_DOES.find(sentence)?.let { ADDRESS.find(it.value) }
        ?.let { formal(it.groupValues[1], it.groupValues[2]) }

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

    private val CARE_CONTEXT = rx("\\b(zimmer|sturz|gestürzt|fieber|diagnose|pflegegrad|medikament\\p{L}*|blutdruck|blutzucker|schmerz\\p{L}*|wunde|dekubitus|demenz|klinik|krankenhaus|hausarzt|ärztin|arzt|bewohner\\p{L}*|patient\\p{L}*|klient\\p{L}*|getrunken|gegessen|insulin|rollator|pflegekasse|betreuung)\\b", true)
    private val PERSON_REF = rx("\\b(?:Frau|Herrn?)\\s+(?:Dr\\.\\s+)?([A-ZÄÖÜ][\\p{L}-]{1,30})")

    /**
     * Personen, über die im Pflege-/Gesundheitszusammenhang gesprochen wird ("Frau Lehmann in Zimmer drei",
     * "Herr Becker hatte Fieber") – in jeder Besprechungsart. Mitarbeitende ([staff], klein geschrieben) nie.
     */
    fun clientsByContext(segments: List<Segment>, staff: Set<String>): List<String> = segments.flatMap { seg ->
        seg.text.split(rx("(?<=[.?!])\\s+")).filter { STREET.containsMatchIn(it) || CARE_CONTEXT.containsMatchIn(it) }
            .flatMap { sen -> PERSON_REF.findAll(sen).map { it.groupValues[1] }.toList() }
    }.filter { it.lowercase() !in staff }.distinctBy { it.lowercase() }


    private val STREET = rx("\\b([A-ZÄÖÜ][\\p{L}-]*(?:straße|strasse|str\\.|weg|platz|gasse|allee|ring|damm|ufer)|[A-ZÄÖÜ][\\p{L}-]+\\s+(?:Straße|Strasse|Weg|Platz|Gasse|Allee))\\s+((?:\\d{1,4}\\s?[a-z]?\\b|(?:ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|dreizehn|vierzehn|fünfzehn|sechzehn|siebzehn|achtzehn|neunzehn|zwanzig)\\b))")
    private val DIGIT_WORDS = "(?:null|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|\\d)"
    private val PHONE = rx("\\b(?:nummer|telefon|telefonnummer|handy|tel\\.?|erreichbar unter)\\s+(?:ist\\s+)?((?:$DIGIT_WORDS[\\s,/-]*){6,})",
        true)

    /** Adressen ("Lindenstraße zwölf") und Telefonnummern im Transkript. */
    fun addressesAndPhones(segments: List<Segment>): List<String> = segments.flatMap { seg ->
        STREET.findAll(seg.text).map { it.value.trim() }.toList() +
            PHONE.findAll(seg.text).map { it.groupValues[1].trim().trimEnd(',', ' ') }.toList()
    }.distinctBy { it.lowercase() }
}
