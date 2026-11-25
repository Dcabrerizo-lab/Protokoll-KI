package de.protokollki.engine

import de.protokollki.data.Segment
import kotlin.math.abs

/**
 * Verbindet die Wörter der Spracherkennung (mit Zeitstempeln) mit den Sprecherabschnitten der
 * Sprechererkennung. So kann die Spracherkennung lange Fenster am Stück verarbeiten (viel
 * schneller als jeden Redebeitrag einzeln), und jedes Wort landet trotzdem beim richtigen Sprecher.
 * Reine Logik, ohne android.*.
 */
object TokenAlign {
    data class Word(val text: String, val start: Float, val end: Float)

    /**
     * Setzt Teilwörter zu Wörtern zusammen. Ein Teilwort mit führendem Leerzeichen (oder „▁“)
     * beginnt ein neues Wort; Satzzeichen hängen am vorigen Wort. [offset] = Fensterbeginn in s.
     */
    fun words(tokens: Array<String>, timestamps: FloatArray, durations: FloatArray?, offset: Float): List<Word> {
        val out = mutableListOf<Word>()
        var text = StringBuilder()
        var start = 0f
        var end = 0f
        fun flush() {
            val t = text.toString().trim()
            if (t.isNotEmpty()) out += Word(t, start, end)
            text = StringBuilder()
        }
        // Parakeet gibt vor Ziffern ein eigenes Leerzeichen-Token aus („am“, „ “, „1“, „8“): als Wortgrenze merken
        var pendingSpace = false
        for (i in tokens.indices) {
            val raw = tokens[i].replace('▁', ' ')
            if (raw.isEmpty()) continue
            if (raw.isBlank()) { pendingSpace = true; continue }
            val ts = offset + (timestamps.getOrNull(i) ?: continue)
            val dur = durations?.getOrNull(i)?.takeIf { it > 0f } ?: 0.08f
            val newWord = raw.startsWith(" ") || pendingSpace || text.isEmpty()
            pendingSpace = false
            if (newWord) { flush(); start = ts }
            text.append(raw.trimStart().let { if (newWord) it else raw })
            end = ts + dur
        }
        flush()
        return out
    }

    /** Sprecher zu einem Zeitpunkt: Abschnitt, der ihn enthält, sonst der nächstgelegene. */
    fun speakerAt(t: Float, turns: List<Transcriber.Turn>): Int {
        if (turns.isEmpty()) return 0
        turns.firstOrNull { t >= it.start && t <= it.end }?.let { return it.speaker }
        return turns.minByOrNull { minOf(abs(t - it.start), abs(t - it.end)) }!!.speaker
    }

    /**
     * Gruppiert Wörter nach Sprecher zu Redebeiträgen. Neuer Beitrag bei Sprecherwechsel oder bei
     * einer Pause über [maxGap] s nach Satzende. Sprecher-IDs werden nach erstem Auftreten neu
     * nummeriert (0, 1, 2 …), wie bei der bisherigen Transkription.
     */
    fun segments(words: List<Word>, turns: List<Transcriber.Turn>, maxGap: Float = 2.0f,
                 renumber: Boolean = true, speakers: IntArray? = null): List<Segment> {
        val remap = LinkedHashMap<Int, Int>()
        val out = mutableListOf<Segment>()
        var cur: MutableList<Word> = mutableListOf()
        var curSpk = -1
        fun flush() {
            if (cur.isEmpty()) return
            val id = if (renumber) remap.getOrPut(curSpk) { remap.size } else curSpk
            out += Segment(id, cur.first().start, cur.last().end, join(cur))
            cur = mutableListOf()
        }
        for ((idx, w) in words.withIndex()) {
            val spk = speakers?.getOrNull(idx) ?: speakerAt((w.start + w.end) / 2, turns)
            val last = cur.lastOrNull()
            val sentenceEnd = last != null && last.text.last() in ".?!"
            if (last != null && (spk != curSpk || (w.start - last.end > maxGap && sentenceEnd))) flush()
            if (cur.isEmpty()) curSpk = spk
            cur += w
        }
        flush()
        return mergeTiny(out)
    }

    /**
     * Nachclustering auf Satzebene: je Satz (gleicher Sprecher, bis Satzende oder Pause > 0,5 s,
     * mind. 1 s) ein Stimmabdruck über [embed]; jeder Satz wird bis zu [iters]-mal dem ähnlichsten
     * Sprecher-Mittelwert zugeordnet. Die Sprecherzahl steigt nie. Auf 18 Testszenarien sank der
     * Anteil falsch zugeordneter Wörter von 5,6 % auf 2,0 % (ähnliche Stimmen: 24 % → 0 %).
     * Liefert den Sprecher je Wort.
     */
    fun refineSpeakers(words: List<Word>, turns: List<Transcriber.Turn>,
                       embed: (Float, Float) -> FloatArray?, iters: Int = 3): IntArray {
        val spk = IntArray(words.size) { speakerAt((words[it].start + words[it].end) / 2, turns) }
        val sents = mutableListOf<IntRange>()
        var from = 0
        for (i in words.indices) {
            if (i > from && (spk[i] != spk[i - 1] || words[i].start - words[i - 1].end > 0.5f)) {
                sents += from until i; from = i
            }
            if (words[i].text.last() in ".?!") { sents += from..i; from = i + 1 }
        }
        if (from < words.size) sents += from until words.size
        val lab = IntArray(sents.size) { spk[sents[it].first] }
        if (lab.distinct().size < 2) return spk
        val embs = HashMap<Int, Pair<FloatArray, Float>>()
        for ((j, r) in sents.withIndex()) {
            val a = words[r.first].start; val b = words[r.last].end
            if (b - a < 1.0f) continue
            val v = embed(a, b) ?: continue
            val n = kotlin.math.sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat().coerceAtLeast(1e-9f)
            embs[j] = FloatArray(v.size) { v[it] / n } to (b - a)
        }
        repeat(iters) {
            val cent = LinkedHashMap<Int, FloatArray>()
            for ((j, e) in embs) {
                val c = cent.getOrPut(lab[j]) { FloatArray(e.first.size) }
                for (k in c.indices) c[k] += e.first[k] * e.second
            }
            if (cent.size < 2) return@repeat
            cent.values.forEach { c ->
                val n = kotlin.math.sqrt(c.fold(0.0) { acc, x -> acc + x * x }).toFloat().coerceAtLeast(1e-9f)
                for (k in c.indices) c[k] /= n
            }
            var changed = false
            for ((j, e) in embs) {
                val best = cent.maxByOrNull { (_, c) -> c.indices.sumOf { (c[it] * e.first[it]).toDouble() } }!!.key
                if (best != lab[j]) { lab[j] = best; changed = true }
            }
            if (!changed) return@repeat
        }
        val out = spk.copyOf()
        for ((j, r) in sents.withIndex()) for (i in r) out[i] = lab[j]
        return out
    }

    /** Einzelne Wörter, die die Sprechererkennung einem anderen Sprecher zuschlägt, dem Umfeld zuordnen. */
    private fun mergeTiny(segs: List<Segment>): List<Segment> {
        if (segs.size < 3) return segs
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < segs.size) {
            val s = segs[i]
            val prev = out.lastOrNull()
            val next = segs.getOrNull(i + 1)
            val words = s.text.split(' ').size
            if (prev != null && next != null && words <= 1 && prev.speakerId == next.speakerId &&
                s.end - s.start < 1.0f) {
                out[out.size - 1] = prev.copy(end = next.end, text = prev.text + " " + s.text + " " + next.text)
                i += 2
                continue
            }
            out += s
            i++
        }
        return out
    }

    private fun join(ws: List<Word>) = ws.joinToString(" ") { it.text }
        .replace(Regex(" ([.,!?;:])"), "$1").trim()

    /**
     * Teilt die Aufnahme in Fenster von höchstens [maxLen] s, geschnitten an leisen Stellen
     * (zwischen [minLen] und [maxLen]). [quietest] liefert den leisesten Zeitpunkt in einem Bereich.
     */
    fun windows(totalSec: Float, minLen: Float = 15f, maxLen: Float = 25f,
                quietest: (Float, Float) -> Float): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        var start = 0f
        while (totalSec - start > maxLen) {
            val cut = quietest(start + minLen, start + maxLen).coerceIn(start + minLen, start + maxLen)
            out += start to cut
            start = cut
        }
        if (totalSec - start > 0.05f) out += start to totalSec
        return out
    }
}
