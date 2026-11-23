package de.protokollki.privacy

import de.protokollki.data.Meeting

/**
 * Schwärzt personenbezogene Angaben in einer Besprechung (reine Logik, ohne android.*).
 *
 * 1. Muster (immer): Telefonnummern → „[Telefon]“, E-Mail → „[E-Mail]“, IBAN → „[IBAN]“,
 *    Geburtsdaten („geb. 01.02.1950“, „geboren am …“) → „[Geburtsdatum]“,
 *    Straße + Hausnummer → „[Adresse]“. Normale Datums-/Zeitangaben bleiben stehen.
 * 2. Begriffe aus `m.sensitiveTerms + extraTerms`: Namen werden konsistent in allen Feldern
 *    zu „Person A“, „Person B“ … ; andere Begriffe zu „[geschwärzt]“.
 *    - Groß-/Kleinschreibung egal, nur ganze Wörter („Anna“ trifft nicht „Annahme“),
 *      Genitiv-s („Müllers“ → „Person As“), längere Begriffe vor kürzeren.
 *    - Anrede/Titel davor wird mitersetzt („Frau Dr. Müller“ → „Person A“), damit auch das
 *      Geschlecht nicht verraten wird.
 *    - Aus „Anna Müller“ werden zusätzlich „Anna“ und „Müller“ einzeln erkannt (dieselbe Person),
 *      sofern sie nicht zum Namen einer anderen teilnehmenden Person gehören. Diese abgeleiteten
 *      Formen treffen nur mit großem Anfangsbuchstaben („lange“ ≠ „Lange“).
 *    - Teilnehmernamen (Sprecher) bleiben, außer sie stehen selbst in der Liste.
 */
object Redactor {
    const val REDACTED = "[geschwärzt]"
    const val PHONE = "[Telefon]"
    const val EMAIL = "[E-Mail]"
    const val IBAN = "[IBAN]"
    const val BIRTHDATE = "[Geburtsdatum]"
    const val ADDRESS = "[Adresse]"

    /** Kopie der Besprechung mit geschwärzten Texten; `sensitiveTerms` wird geleert. */
    fun redact(m: Meeting, extraTerms: List<String>): Meeting =
        applyPlan(m, planFor(m, extraTerms), null)

    /**
     * Was beim Schwärzen ersetzt würde: (Original → Ersatz), je Fundstelle einmal
     * (ohne Unterscheidung der Groß-/Kleinschreibung), in Reihenfolge des Auftretens.
     */
    fun preview(m: Meeting, extraTerms: List<String>): List<Pair<String, String>> {
        val found = LinkedHashMap<String, Pair<String, String>>()
        applyPlan(m, planFor(m, extraTerms)) { original, replacement ->
            val key = normalize(original)
            if (key.isNotEmpty() && !found.containsKey(key)) found[key] = original to replacement
        }
        return found.values.toList()
    }

    /** Schwärzt einen einzelnen Text (z. B. für eine Live-Vorschau). */
    fun redactText(text: String, terms: List<String>, participantNames: List<String> = emptyList()): String =
        RedactionPlan.build(terms, participantNames).apply(text, null)

    private fun planFor(m: Meeting, extraTerms: List<String>): RedactionPlan =
        RedactionPlan.build(m.sensitiveTerms + extraTerms, m.speakers.map { it.name })

    private fun applyPlan(m: Meeting, plan: RedactionPlan, sink: ((String, String) -> Unit)?): Meeting {
        fun r(s: String): String = plan.apply(s, sink)
        return m.copy(
            title = r(m.title),
            agenda = m.agenda.map(::r),
            speakers = m.speakers.map { it.copy(name = plan.speakerName(it.name, sink)) },
            summary = m.summary.copy(
                overview = r(m.summary.overview),
                topics = m.summary.topics.map { t -> t.copy(title = r(t.title), points = t.points.map(::r)) },
                decisions = m.summary.decisions.map(::r),
                openPoints = m.summary.openPoints.map(::r),
            ),
            tasks = m.tasks.map {
                it.copy(text = r(it.text), owner = r(it.owner), due = r(it.due), doneNote = it.doneNote?.let(::r))
            },
            followUps = m.followUps.map { it.copy(text = r(it.text), owner = r(it.owner)) },
            segments = m.segments.map { it.copy(text = r(it.text)) },
            error = m.error?.let(::r),
            progressText = "",
            sensitiveTerms = emptyList(),
        )
    }
}

private val WHITESPACE = Regex("\\s+")

/** Vergleichsschlüssel: Leerraum vereinheitlicht, klein geschrieben. */
internal fun normalize(s: String): String = s.replace(WHITESPACE, " ").trim().lowercase()

// ------------------------------------------------------------------ feste Muster

internal object PiiPatterns {
    private const val NB = "(?<![\\p{L}\\p{N}])"   // kein Buchstabe/Ziffer davor
    private const val NA = "(?![\\p{L}\\p{N}])"    // kein Buchstabe/Ziffer danach

    val email = Regex("(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}")

    /** IBAN, mit oder ohne Leerzeichen in Vierergruppen (z. B. DE89 3704 0044 0532 0130 00). */
    val iban = Regex("$NB(?:[A-Z]{2}|de|De)\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?$NA")

    private const val MONTHS = "januar|februar|märz|maerz|april|mai|juni|juli|august|september|oktober|november|dezember"

    /** Gruppe 1 = Einleitung (bleibt stehen), Gruppe 2 = das Datum. */
    val birthdate = Regex(
        "(?iu)$NB((?:geb\\.|geboren(?:\\s+am)?|geburtsdatum:?|geb\\.-datum:?)\\s*)" +
            "(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?(?:\\d{4}|\\d{2})|\\d{1,2}\\.\\s*(?:$MONTHS)\\s+\\d{4}|(?:19|20)\\d{2})" +
            "(?!\\p{N})",
    )

    private const val STREET_SUFFIX = "straße|strasse|str\\.|weg|platz|allee|gasse|damm|ring|ufer|chaussee|steig|pfad|markt"
    private const val STREET_WORD = "Straße|Strasse|Str\\.|Weg|Platz|Allee|Gasse|Damm|Ring|Ufer|Chaussee|Steig|Pfad|Markt"
    private const val HOUSE_NO = "\\d{1,4}(?:\\s?[a-zA-Z](?!\\p{L}))?"
    private const val UNITS = "Stunden|Std\\.?|Minuten|Min\\.?|Tage|Tagen|Wochen|Monate|Monaten|Jahre|Jahren|" +
        "km|m|Meter|Mal|mal|Prozent|%|Euro|€|Uhr|Personen|Plätze|x"

    /** „Hauptstraße 5“, „Berliner Str. 12a“, „Am Weg 3–5“, optional mit „, 10115 Berlin“. */
    val address = Regex(
        "$NB(?:\\p{Lu}[\\p{L}.-]*(?iu:$STREET_SUFFIX)|\\p{Lu}[\\p{L}.-]*\\s+(?:$STREET_WORD))" +
            "\\s*$HOUSE_NO(?:\\s?[-–/]\\s?$HOUSE_NO)?(?!\\p{N})" +
            "(?!\\s*(?:$UNITS)(?!\\p{L}))" +
            "(?:,?\\s*\\d{5}\\s+\\p{Lu}[\\p{L}-]+)?",
    )

    /**
     * Deutsche/internationale Telefonnummern: „0171 1234567“, „+49 30 1234567“, „030/123456“,
     * „(030) 123456“, „+49 (0) 30 12345-67“, „0049 171 1234567“. Trennzeichen nur Leerzeichen,
     * „/“ und „-“ – Punkte (Datum) und Doppelpunkte (Uhrzeit) nie. Nationale Nummern beginnen
     * mit 0 und einer mindestens dreistelligen Vorwahl, daher kein Treffer auf „07.11.2026“.
     */
    val phone = Regex(
        "(?<![\\p{L}\\p{N}+])" +
            "(?:(?:\\+|00)[1-9]\\d{0,2}[ /-]*(?:\\(0\\)[ /-]*)?\\(?\\d{1,5}\\)?|\\(?0[1-9]\\d{1,4}\\)?)" +
            "(?:[ /-]*\\d){4,12}(?!\\p{N})",
    )

    fun apply(text: String, sink: ((String, String) -> Unit)?): String {
        var t = text
        t = email.replace(t) { sink?.invoke(it.value, Redactor.EMAIL); Redactor.EMAIL }
        t = iban.replace(t) { sink?.invoke(it.value, Redactor.IBAN); Redactor.IBAN }
        t = birthdate.replace(t) {
            sink?.invoke(it.groupValues[2], Redactor.BIRTHDATE)
            it.groupValues[1] + Redactor.BIRTHDATE
        }
        t = address.replace(t) { sink?.invoke(it.value.trim(), Redactor.ADDRESS); Redactor.ADDRESS }
        t = phone.replace(t) { sink?.invoke(it.value.trim(), Redactor.PHONE); Redactor.PHONE }
        return t
    }
}

// ------------------------------------------------------------------ Begriffe

internal class RedactionPlan private constructor(
    private val termRegex: Regex?,
    private val targets: Map<String, Target>,
) {
    data class Target(val replacement: String, val person: Boolean)

    fun apply(text: String, sink: ((String, String) -> Unit)?): String {
        if (text.isBlank()) return text
        val t = PiiPatterns.apply(text, sink)
        val re = termRegex ?: return t
        return re.replace(t) { mr ->
            val prefix = mr.groupValues[1]
            val core = mr.groupValues[2]
            val genitive = mr.groupValues[3].isNotEmpty()
            val target = targets[normalize(core)]
            if (target == null) {
                mr.value
            } else {
                sink?.invoke(core.replace(WHITESPACE, " "), target.replacement)
                if (target.person) target.replacement + (if (genitive) "s" else "")
                else prefix + target.replacement
            }
        }
    }

    /** Teilnehmername: bleibt, außer er enthält einen Begriff – dann komplett ersetzen. */
    fun speakerName(name: String, sink: ((String, String) -> Unit)?): String {
        val re = termRegex ?: return name
        val mr = re.find(name) ?: return name
        val target = targets[normalize(mr.groupValues[2])] ?: return name
        sink?.invoke(name.trim(), target.replacement)
        return target.replacement
    }

    private class Entry(val index: Int, val term: String, val core: String, val person: Boolean)
    private class Variant(val text: String, val explicit: Boolean, val target: Target)

    companion object {
        private val HONORIFIC = Regex("^(?:(?:frau|herrn?)\\s+|(?:fr|hr|dr|prof|med)\\.\\s*)+", RegexOption.IGNORE_CASE)
        private const val PREFIX = "((?:(?iu:frau|herrn?)\\s+|(?iu:fr|hr|dr|prof|med)\\.\\s*)*)"
        private const val TRIM_CHARS = "\"'„“”‚‘’,;:!?()[]{}<>«»"

        private val PARTICLES = setOf(
            "von", "van", "vom", "de", "der", "den", "zu", "zur", "di", "da", "du", "le", "la", "ten", "ter", "y",
        )
        private val NOT_NAME_WORDS = PARTICLES + setOf("frau", "herr", "herrn", "dr.", "prof.", "fr.", "hr.")
        private val ORG_WORDS = setOf(
            "gmbh", "ggmbh", "ag", "kg", "ohg", "e.v.", "ev", "stadt", "gemeinde", "landkreis", "kreis",
            "jugendamt", "sozialamt", "jobcenter", "finanzamt", "polizei", "caritas", "diakonie", "awo", "drk",
            "diabetes", "demenz", "depression", "krebs", "asthma", "adhs", "autismus", "epilepsie", "sucht",
            "hiv", "aids", "schizophrenie", "borderline", "alzheimer",
        )
        private val ORG_SUFFIXES = listOf(
            "straße", "strasse", "weg", "platz", "allee", "gasse", "klinik", "klinikum", "krankenhaus",
            "pflegeheim", "altenheim", "wohnheim", "schule", "kita", "kindergarten", "praxis", "zentrum",
            "amt", "gericht", "bank", "verein", "stiftung", "verband", "station", "abteilung", "kasse",
            "versicherung", "gruppe", "tion", "sion", "ität", "ismus", "schaft", "heit", "keit", "itis", "ose",
        )

        /**
         * @param terms zu schwärzende Begriffe (Reihenfolge bestimmt „Person A, B, …“)
         * @param participantNames Sprechernamen; deren Wörter werden nicht automatisch abgeleitet
         */
        fun build(terms: List<String>, participantNames: List<String>): RedactionPlan {
            // 1) bereinigen, doppelte entfernen
            val entries = mutableListOf<Entry>()
            val seen = HashSet<String>()
            for (raw in terms) {
                val term = raw.replace(WHITESPACE, " ").trim().trim { it in TRIM_CHARS }.trim()
                    // Satzpunkt am Ende weg („Müller.“), Abkürzungen behalten („e.V.“, „Str.“)
                    .let { if (it.endsWith('.') && it.substringAfterLast(' ').length > 5) it.dropLast(1) else it }
                if (term.length < 2 || !seen.add(normalize(term))) continue
                val stripped = term.replace(HONORIFIC, "").trim()
                val hadHonorific = stripped.length < term.length && stripped.length >= 2
                val core = if (hadHonorific) stripped else term
                entries += Entry(entries.size, term, core, hadHonorific || looksLikePerson(core))
            }
            if (entries.isEmpty()) return RedactionPlan(null, emptyMap())

            // 2) Personen gruppieren: „Müller“ / „Frau Müller“ gehören zu „Anna Müller“, wenn eindeutig
            val multiKeys = entries.filter { it.person && it.core.contains(' ') }
                .map { "p:" + normalize(it.core) }.distinct()
            fun keyOf(e: Entry): String {
                if (!e.person) return "x:" + normalize(e.core)
                val n = normalize(e.core)
                if (e.core.contains(' ')) return "p:$n"
                val matches = multiKeys.filter { k ->
                    val words = k.removePrefix("p:").split(' ')
                    words.first() == n || words.last() == n
                }
                return if (matches.size == 1) matches[0] else "p:$n"
            }
            val keys = entries.associateWith(::keyOf)
            val labels = HashMap<String, String>()
            entries.sortedBy { it.index }.forEach { e ->
                val k = keys.getValue(e)
                if (e.person && k !in labels) labels[k] = "Person " + letters(labels.size)
            }
            fun targetOf(e: Entry) =
                if (e.person) Target(labels.getValue(keys.getValue(e)), true) else Target(Redactor.REDACTED, false)

            // 3) explizite Varianten (Begriff wie eingegeben + ohne Anrede)
            val variants = LinkedHashMap<String, Variant>()
            for (e in entries) {
                val t = targetOf(e)
                for (v in listOf(e.term, e.core)) {
                    val n = normalize(v)
                    if (n.length >= 2 && !variants.containsKey(n)) variants[n] = Variant(v, true, t)
                }
            }

            // 4) Teilnehmernamen schützen, sofern sie nicht selbst in der Liste stehen
            val explicitRegex = regexFor(variants.values)
            val protectedWords = HashSet<String>()
            participantNames.filter { it.isNotBlank() && !explicitRegex.containsMatchIn(it) }.forEach { name ->
                name.split(WHITESPACE).forEach { w -> protectedWords += normalize(w) }
            }

            // 4b) Steht ein Teilnehmer in der Liste (z. B. „Becker“), wird sein voller Name im Text
            //     ebenfalls ersetzt („Jonas Becker“ → „Person B“, nicht „Jonas Person B“)
            participantNames.filter { it.isNotBlank() }.forEach { name ->
                val mr = explicitRegex.find(name) ?: return@forEach
                val target = variants[normalize(mr.groupValues[2])]?.target ?: return@forEach
                val clean = name.replace(WHITESPACE, " ").trim()
                val n = normalize(clean)
                if (n.length >= 2 && !variants.containsKey(n)) variants[n] = Variant(clean, true, target)
            }

            // 5) Vor- und Nachnamen einzeln ableiten
            val derived = LinkedHashMap<String, Pair<String, MutableSet<String>>>()
            for (e in entries) {
                if (!e.person || !e.core.contains(' ')) continue
                val words = e.core.split(' ')
                for (w in listOf(words.first(), words.last()).distinct()) {
                    val n = normalize(w)
                    if (!isNameWord(w) || n in protectedWords || variants.containsKey(n)) continue
                    derived.getOrPut(n) { w to HashSet() }.second += keys.getValue(e)
                }
            }
            for ((n, pair) in derived) {
                val owners = pair.second
                val target = if (owners.size == 1) Target(labels.getValue(owners.first()), true)
                else Target(Redactor.REDACTED, false) // mehrdeutig: lieber neutral schwärzen
                variants[n] = Variant(pair.first, false, target)
            }

            return RedactionPlan(regexFor(variants.values), variants.mapValues { it.value.target })
        }

        private fun regexFor(variants: Collection<Variant>): Regex {
            val alts = variants.sortedByDescending { it.text.length }.joinToString("|") { v ->
                if (v.explicit) {
                    "(?iu:" + v.text.split(' ').joinToString("\\s+") { Regex.escape(it) } + ")"
                } else {
                    // erster Buchstabe exakt (groß), Rest beliebig
                    Regex.escape(v.text.substring(0, 1)) + "(?iu:" + Regex.escape(v.text.substring(1)) + ")"
                }
            }
            return Regex("(?<![\\p{L}\\p{N}])$PREFIX($alts)((?:['’][sS]|[sS])?)(?![\\p{L}\\p{N}])")
        }

        /** A … Z, AA, AB, … */
        internal fun letters(i: Int): String {
            var n = i
            val sb = StringBuilder()
            do {
                sb.insert(0, 'A' + n % 26)
                n = n / 26 - 1
            } while (n >= 0)
            return sb.toString()
        }

        private fun isNameWord(w: String): Boolean =
            w.length >= 3 && w[0].isUpperCase() && normalize(w) !in NOT_NAME_WORDS &&
                w.all { it.isLetter() || it == '-' || it == '\'' || it == '’' }

        /** Heuristik: sieht der Begriff wie ein Personenname aus (statt Einrichtung, Diagnose …)? */
        internal fun looksLikePerson(core: String): Boolean {
            if (core.any { it.isDigit() }) return false
            val words = core.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 4) return false
            for ((i, w) in words.withIndex()) {
                val lw = w.lowercase()
                if (lw in ORG_WORDS) return false
                if (ORG_SUFFIXES.any { (lw.endsWith(it) && lw.length >= it.length + 3) || (lw == it && it.length >= 5) }) {
                    return false
                }
                if (i in 1 until words.lastIndex && lw in PARTICLES) continue
                if (!w[0].isUpperCase()) return false
                if (w.length >= 2 && w.all { it.isUpperCase() || !it.isLetter() }) return false // Abkürzung (AOK)
                if (!w.all { it.isLetter() || it == '-' || it == '\'' || it == '’' || it == '.' }) return false
            }
            return true
        }
    }
}
