package de.protokollki.engine

/**
 * Verbindet die Sprecher mehrerer Abschnitte einer langen Aufnahme (Sprechererkennung je
 * Abschnitt, siehe [Transcriber.diarize]) zu durchgehenden Personen. Grundlage ist je Abschnitt
 * und Sprecher ein Stimm-Vektor (WeSpeaker). Reine Rechenlogik ohne Android, testbar.
 */
object SpeakerLinker {
    /**
     * Mindestähnlichkeit, ab der zwei Abschnittssprecher als dieselbe Person gelten. Etwas lockerer
     * als die Profil-Zuordnung ([VoiceMath.THRESHOLD]), weil hier Mikrofon und Raum gleich sind.
     */
    const val LINK_THRESHOLD = 0.5f

    /**
     * @param windows je Abschnitt: lokale Sprecher-ID → Stimm-Vektor
     * @param maxSpeakers vorgegebene Teilnehmerzahl (≤ 0 = frei); bei mehr gefundenen Personen
     *   werden die ähnlichsten zusammengelegt
     * @return je Abschnitt: lokale Sprecher-ID → globale ID (fortlaufend ab 0, nach erstem Auftreten)
     */
    fun link(
        windows: List<Map<Int, FloatArray>>,
        maxSpeakers: Int,
        threshold: Float = LINK_THRESHOLD,
    ): List<Map<Int, Int>> {
        val sums = mutableListOf<FloatArray>()      // Summe der normierten Vektoren je Person
        val result = mutableListOf<MutableMap<Int, Int>>()
        for (w in windows) {
            val local = w.mapValues { VoiceMath.normalize(it.value) }
            data class Cand(val local: Int, val global: Int, val score: Float)
            val cands = mutableListOf<Cand>()
            for ((l, e) in local) for ((g, s) in sums.withIndex()) cands += Cand(l, g, VoiceMath.cosine(e, s))
            val map = mutableMapOf<Int, Int>()
            val used = mutableSetOf<Int>()
            for (c in cands.sortedByDescending { it.score }) {
                if (c.score < threshold) break
                if (c.local in map || c.global in used) continue
                map[c.local] = c.global; used += c.global
            }
            // Übrige: neue Person (größere Sprecher-IDs zuletzt, damit die Reihenfolge stabil ist)
            for (l in local.keys.sorted()) if (l !in map) { map[l] = sums.size; sums += FloatArray(local.getValue(l).size) }
            for ((l, g) in map) {
                val e = local.getValue(l)
                val s = sums[g]
                if (s.size == e.size) for (i in s.indices) s[i] += e[i]
            }
            result += map
        }
        // Auf die vorgegebene Teilnehmerzahl zusammenlegen (ähnlichstes Paar zuerst)
        val alias = IntArray(sums.size) { it }
        fun root(i: Int): Int { var r = i; while (alias[r] != r) r = alias[r]; return r }
        if (maxSpeakers > 0) {
            var alive = sums.indices.toMutableList()
            while (alive.size > maxSpeakers) {
                var best = -2f; var bi = -1; var bj = -1
                for (a in alive.indices) for (b in a + 1 until alive.size) {
                    val sc = VoiceMath.cosine(sums[alive[a]], sums[alive[b]])
                    if (sc > best) { best = sc; bi = alive[a]; bj = alive[b] }
                }
                if (bi < 0) break
                val s = sums[bi]; val t = sums[bj]
                if (s.size == t.size) for (i in s.indices) s[i] += t[i]
                alias[bj] = bi
                alive = alive.filter { it != bj }.toMutableList()
            }
        }
        // Fortlaufend nach erstem Auftreten nummerieren
        val renumber = LinkedHashMap<Int, Int>()
        return result.map { m ->
            m.keys.sorted().associateWith { l -> renumber.getOrPut(root(m.getValue(l))) { renumber.size } }
        }
    }
}
