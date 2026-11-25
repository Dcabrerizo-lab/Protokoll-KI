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
                 renumber: Boolean = true): List<Segment> {
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
        for (w in words) {
            val spk = speakerAt((w.start + w.end) / 2, turns)
            val last = cur.lastOrNull()
            val sentenceEnd = last != null && last.text.last() in ".?!"
            if (last != null && (spk != curSpk || (w.start - last.end > maxGap && sentenceEnd))) flush()
            if (cur.isEmpty()) curSpk = spk
            cur += w
        }
        flush()
        return mergeTiny(out)
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
