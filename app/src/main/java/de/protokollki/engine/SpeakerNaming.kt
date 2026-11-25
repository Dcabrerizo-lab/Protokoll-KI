package de.protokollki.engine

import de.protokollki.data.Segment

/**
 * Benennt Sprecher automatisch aus dem Gespräch, ohne dass jemand „Person 1“ von Hand umbenennen muss:
 *  - Selbstvorstellung: „Mein Name ist Anna Becker“, „Ich heiße …“, „Hier spricht Frau Schmidt“,
 *    „Ich bin Herr Yilmaz“ (stark),
 *  - Anrede: „Frau Schmidt, wie sehen Sie das?“ → die nächste andere Stimme ist vermutlich Frau Schmidt;
 *    „Danke, Herr Kaiser.“ → die vorige andere Stimme war vermutlich Herr Kaiser (schwach).
 * Wer einen Namen ausspricht, ist selbst nicht diese Person. Ein Name wird nur vergeben, wenn die
 * Hinweise eindeutig sind (lieber „Person 2“ als ein falscher Name). Reine Logik, ohne android.*.
 */
object SpeakerNaming {
    private const val NAME = "[A-ZÄÖÜ][a-zäöüß]+(?:-[A-ZÄÖÜ][a-zäöüß]+)?"
    private val TITLED = Regex("\\b(Frau|Herr|Herrn|Dr\\.|Doktor)\\s+(?:Dr\\.\\s+)?($NAME)")
    private val INTRO_STRONG = Regex(
        "\\b(?:[Mm]ein Name ist|[Ii]ch heiße|[Hh]ier spricht)\\s+(?:(?:Frau|Herr|Dr\\.)\\s+)?($NAME(?:\\s+$NAME)?)")
    private val INTRO_BIN = Regex("\\b[Ii]ch bin (?:(Frau|Herr|Dr\\.)\\s+)?($NAME(?:\\s+$NAME)?)")
    private val THANKS = Regex("^\\W*(?:[Dd]anke(?: schön)?|[Vv]ielen Dank|[Gg]enau|[Rr]ichtig|[Ss]uper),?\\s+(?:(Frau|Herr|Herrn)\\s+)?($NAME)\\b")

    /** Wörter nach „ich bin“, die kein Name sind (großgeschriebene Nomen am Satzanfang usw.). */
    private val NOT_NAMES = setOf(
        "Meinung", "Ansicht", "Auffassung", "Pflegekraft", "Leitung", "Teamleitung", "Leiterin", "Leiter",
        "Mitglied", "Kassenwart", "Vorsitzende", "Vorsitzender", "Sozialarbeiterin", "Sozialarbeiter",
        "Ärztin", "Arzt", "Praktikantin", "Praktikant", "Ich", "Wir", "Sie", "Die", "Der", "Das", "Und",
        "Also", "Ja", "Nein", "Gut", "Danke", "Okay", "Hallo", "Morgen", "Tag", "Abend",
    )

    fun guess(segments: List<Segment>, participants: List<String> = emptyList()): Map<Int, String> {
        val known = participants.associateBy { surname(it) }
        val score = HashMap<Int, HashMap<String, Int>>()
        val sayers = HashMap<String, MutableSet<Int>>()   // wer welchen Namen ausspricht
        fun vote(spk: Int, raw: String, w: Int) {
            val key = surname(raw)
            if (key.isEmpty() || key in NOT_NAMES) return
            score.getOrPut(spk) { HashMap() }.merge(key, w, Int::plus)
        }

        val titles = HashMap<String, String>()
        val fullNames = HashMap<String, String>()   // „Anna Becker“ aus einer Selbstvorstellung
        fun display(key: String, title: String?): String = known[key] ?: fullNames[key]
            ?: if (title != null) "${title.removeSuffix("n").let { if (it == "Doktor") "Dr." else it }} $key" else key
        for ((i, s) in segments.withIndex()) {
            val text = s.text
            INTRO_STRONG.findAll(text).forEach { m ->
                vote(s.speakerId, m.groupValues[1], 3)
                if (' ' in m.groupValues[1]) fullNames[surname(m.groupValues[1])] = m.groupValues[1]
            }
            INTRO_BIN.findAll(text).forEach { m ->
                val name = m.groupValues[2]; val title = m.groupValues[1].ifEmpty { null }
                // „Ich bin Becker“ nur mit Titel oder wenn der Name auf der Teilnehmerliste steht
                if (title != null || surname(name) in known) {
                    vote(s.speakerId, name, 3)
                    if (title != null) titles[surname(name)] = title
                    if (' ' in name) fullNames[surname(name)] = name
                }
            }
            for (m in TITLED.findAll(text)) {
                val key = surname(m.groupValues[2])
                titles.putIfAbsent(key, m.groupValues[1])
                // Selbstvorstellung mit Titel nicht als Anrede werten
                val before = text.substring(0, m.range.first).lowercase()
                if (before.endsWith("ich bin ") || before.endsWith("hier spricht ") || before.endsWith("name ist ")) continue
                sayers.getOrPut(key) { mutableSetOf() } += s.speakerId
                // Anrede: die nächste andere Stimme antwortet
                val next = segments.drop(i + 1).firstOrNull { it.speakerId != s.speakerId }
                if (next != null && (m.range.first < 3 || isVocative(text, m.range))) {
                    vote(next.speakerId, key, 1)
                }
            }
            // „Danke, Frau Schmidt“ am Anfang → bezieht sich auf die vorige andere Stimme
            THANKS.find(text)?.let { m ->
                val key = surname(m.groupValues[2])
                sayers.getOrPut(key) { mutableSetOf() } += s.speakerId
                val prev = segments.take(i).lastOrNull { it.speakerId != s.speakerId }
                if (prev != null && (m.groupValues[1].isNotEmpty() || key in known)) vote(prev.speakerId, key, 1)
            }
            // Namen von der Teilnehmerliste ohne Titel als Anrede am Satzanfang/-ende
            for (key in known.keys) {
                val vocative = Regex("(^\\W*$key,|,\\s*$key\\s*[?!.]?\\s*$)").containsMatchIn(text)
                if (vocative) {
                    sayers.getOrPut(key) { mutableSetOf() } += s.speakerId
                    val next = segments.drop(i + 1).firstOrNull { it.speakerId != s.speakerId }
                    if (next != null) vote(next.speakerId, key, 1)
                }
            }
        }
        // Wer einen Namen anspricht, ist nicht diese Person
        for ((key, spks) in sayers) for (spk in spks) score[spk]?.remove(key)

        // Eindeutig vergeben: höchste Punktzahl zuerst, mind. 2 Punkte, klarer Abstand zum Zweitbesten
        val candidates = score.flatMap { (spk, m) ->
            val sorted = m.entries.sortedByDescending { it.value }
            val best = sorted.firstOrNull() ?: return@flatMap emptyList()
            val second = sorted.getOrNull(1)?.value ?: 0
            if (best.value >= 2 && best.value >= 2 * second) listOf(Triple(spk, best.key, best.value)) else emptyList()
        }.sortedByDescending { it.third }
        val out = LinkedHashMap<Int, String>()
        val used = mutableSetOf<String>()
        for ((spk, key, _) in candidates) {
            if (spk in out || key in used) continue
            out[spk] = display(key, titles[key])
            used += key
        }
        return out
    }

    /** Name steht als Anrede: direkt gefolgt von Komma/Fragezeichen oder am Ende des Beitrags. */
    private fun isVocative(text: String, range: IntRange): Boolean {
        val after = text.substring(range.last + 1).trimStart()
        return after.isEmpty() || after.first() in ",?!" || after.startsWith(".") && after.length <= 1
    }

    /** „Frau Dr. Anna Becker“ → „Becker“ (Schlüssel zum Vergleichen). */
    internal fun surname(name: String): String =
        name.split(' ', ' ').map { it.trim().trimEnd('.', ',') }
            .filterNot { it.isEmpty() || it.lowercase() in setOf("frau", "herr", "herrn", "dr", "prof", "doktor") }
            .lastOrNull().orEmpty()
}
