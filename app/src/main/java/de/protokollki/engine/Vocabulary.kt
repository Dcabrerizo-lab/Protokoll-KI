package de.protokollki.engine

import de.protokollki.data.AppSettings

/**
 * Eigenes Wörterbuch: Namen und Fachbegriffe, die die Spracherkennung oft falsch schreibt
 * (z. B. "Meier-Lüdenscheidt", "SGB XI", "Pflegegrad"). Whisper bietet in sherpa-onnx keinen
 * Prompt, daher wird das Transkript nachträglich phonetisch korrigiert (Kölner Phonetik +
 * Editierdistanz) und das Sprachmodell bekommt die richtige Schreibweise mitgeliefert.
 */
object Vocabulary {

    /** Wörterbuch aus den Einstellungen plus Namen der Stimmprofile. */
    fun terms(): List<String> =
        (AppSettings.state.value.vocabulary + VoiceProfiles.profiles.value.map { it.name })
            .map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }

    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

    /** Häufige Wörter, die nie ersetzt werden (Schutz vor Fehlkorrekturen). */
    private val PROTECTED = setOf(
        "aber", "alle", "also", "auch", "dann", "dass", "denn", "dies", "diese", "dieser", "doch",
        "eine", "einen", "einer", "haben", "heute", "hier", "jetzt", "kann", "können", "machen",
        "mehr", "noch", "oder", "schon", "sein", "sind", "unser", "wenn", "werden", "wieder", "wird",
        "woche", "morgen", "gerne", "genau", "danke", "bitte", "termin", "frage", "fragen", "gut",
        "mache", "habe", "hatte", "würde", "sollte", "müssen", "nicht", "nichts", "etwas", "ganz",
    )

    /**
     * Ersetzt Wörter bzw. Wortgruppen, die wie ein Wörterbucheintrag klingen und fast gleich
     * geschrieben sind, durch die richtige Schreibweise. Satzzeichen bleiben erhalten.
     */
    fun correct(text: String, terms: List<String>): String {
        if (text.isBlank() || terms.isEmpty()) return text
        val entries = terms.mapNotNull { t ->
            val words = WORD.findAll(t).map { it.value }.toList()
            if (words.isEmpty() || t.length < 4) null
            else Entry(t, words.size, norm(t), colognePhonetic(t))
        }.sortedByDescending { it.words }
        if (entries.isEmpty()) return text

        val tokens = WORD.findAll(text).toList()
        val out = StringBuilder()
        var pos = 0
        var i = 0
        while (i < tokens.size) {
            var replaced = false
            for (e in entries) {
                if (i + e.words > tokens.size) continue
                val first = tokens[i]
                val last = tokens[i + e.words - 1]
                val span = text.substring(first.range.first, last.range.last + 1)
                // Nur Wörter dazwischen, keine Satzzeichen über die Gruppe hinweg
                if (e.words > 1 && span.any { it in ".,;:!?" }) continue
                val m = match(span, e)
                if (m != NO) {
                    out.append(text, pos, first.range.first)
                    out.append(e.term)
                    if (m == GENITIVE) out.append('s')   // "Schmidts" -> "Schmitts"
                    pos = last.range.last + 1
                    i += e.words
                    replaced = true
                    break
                }
            }
            if (!replaced) i++
        }
        out.append(text, pos, text.length)
        return out.toString()
    }

    private data class Entry(val term: String, val words: Int, val norm: String, val code: String)

    private const val NO = 0
    private const val PLAIN = 1
    private const val GENITIVE = 2

    private fun match(span: String, e: Entry): Int {
        val n = norm(span)
        if (n == e.norm) return if (span != e.term) PLAIN else NO   // nur Schreibweise
        if (e.words == 1 && n in PROTECTED) return NO
        if (fuzzy(span, n, e)) return PLAIN
        // Genitiv-s: "Schmidts" ~ "Schmitt" + s
        if (n.endsWith("s") && !e.norm.endsWith("s") && n.length > 4) {
            val base = n.dropLast(1)
            if (base == e.norm) return if (span.dropLast(1) != e.term) GENITIVE else NO
            if (fuzzy(span.dropLast(1), base, e)) return GENITIVE
        }
        return NO
    }

    private fun fuzzy(span: String, n: String, e: Entry): Boolean {
        val dist = levenshtein(n, e.norm)
        val maxLen = maxOf(n.length, e.norm.length)
        val ratio = dist.toDouble() / maxLen
        val sameSound = colognePhonetic(span) == e.code
        if (dist > 4) return false
        return if (sameSound) {
            // gleicher Klang: kurze Wörter max. 2 Abweichungen (Maier/Meyer), sonst bis ein Drittel
            if (e.norm.length < 5) dist <= 1 else ratio <= 0.4
        } else {
            // anderer Klang nur bei sehr ähnlicher Schreibung langer Wörter (Tippfehler)
            e.norm.length >= 8 && ratio <= 0.15
        }
    }

    internal fun norm(s: String): String =
        s.lowercase().replace("ß", "ss").filter { it.isLetterOrDigit() }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Kölner Phonetik (Postel 1969) für deutsche Wörter; mehrere Wörter werden verbunden. */
    internal fun colognePhonetic(input: String): String {
        val s = input.lowercase()
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "s")
            .filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""
        val codes = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i < s.length - 1) s[i + 1] else ' '
            val code = when (c) {
                'a', 'e', 'i', 'j', 'o', 'u', 'y' -> "0"
                'h' -> ""
                'b' -> "1"
                'p' -> if (next == 'h') "3" else "1"
                'd', 't' -> if (next in "csz") "8" else "2"
                'f', 'v', 'w' -> "3"
                'g', 'k', 'q' -> "4"
                'c' -> when {
                    i == 0 -> if (next in "ahkloqrux") "4" else "8"
                    prev in "sz" -> "8"
                    next in "ahkoqux" -> "4"
                    else -> "8"
                }
                'x' -> if (prev in "ckq") "8" else "48"
                'l' -> "5"
                'm', 'n' -> "6"
                'r' -> "7"
                's', 'z' -> "8"
                else -> ""
            }
            codes.append(code)
        }
        // Doppelte entfernen, dann alle "0" außer am Anfang
        val dedup = StringBuilder()
        for (ch in codes) if (dedup.isEmpty() || dedup.last() != ch) dedup.append(ch)
        val first = dedup.firstOrNull() ?: return ""
        return first.toString() + dedup.drop(1).filter { it != '0' }
    }
}
