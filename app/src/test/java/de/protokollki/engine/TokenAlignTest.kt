package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenAlignTest {
    private val tokens = arrayOf(" G", "uten", " Mor", "gen", ".", " Ja", ",", " gern", "e", ".")
    private val ts = floatArrayOf(0.1f, 0.2f, 0.4f, 0.5f, 0.6f, 3.0f, 3.1f, 3.3f, 3.4f, 3.5f)

    @Test
    fun tokensBecomeWordsWithOffset() {
        val w = TokenAlign.words(tokens, ts, null, 10f)
        assertEquals(listOf("Guten", "Morgen.", "Ja,", "gerne."), w.map { it.text })
        assertEquals(10.1f, w[0].start, 0.001f)
        assertEquals(13.0f, w[2].start, 0.001f)
    }

    @Test
    fun wordsAreGroupedBySpeakerAndRenumbered() {
        val w = TokenAlign.words(tokens, ts, null, 0f)
        val turns = listOf(Transcriber.Turn(0f, 1f, 5), Transcriber.Turn(2.8f, 4f, 2))
        val s = TokenAlign.segments(w, turns)
        assertEquals(2, s.size)
        assertEquals("Guten Morgen.", s[0].text)
        assertEquals(0, s[0].speakerId)
        assertEquals("Ja, gerne.", s[1].text)
        assertEquals(1, s[1].speakerId)
    }

    @Test
    fun wordsBetweenTurnsGoToNearestSpeaker() {
        val turns = listOf(Transcriber.Turn(0f, 1f, 0), Transcriber.Turn(5f, 6f, 1))
        assertEquals(0, TokenAlign.speakerAt(1.5f, turns))
        assertEquals(1, TokenAlign.speakerAt(4.2f, turns))
        assertEquals(0, TokenAlign.speakerAt(3f, emptyList()))
    }

    @Test
    fun singleStrayWordIsMergedIntoSurroundingSpeaker() {
        val w = listOf(
            TokenAlign.Word("Wir", 0f, 0.3f), TokenAlign.Word("teilen", 0.3f, 0.6f),
            TokenAlign.Word("die", 0.6f, 0.8f), TokenAlign.Word("Tour.", 0.8f, 1.2f),
        )
        // "die" fällt in einen winzigen Abschnitt eines anderen Sprechers
        val turns = listOf(Transcriber.Turn(0f, 0.6f, 0), Transcriber.Turn(0.6f, 0.8f, 1), Transcriber.Turn(0.8f, 2f, 0))
        val s = TokenAlign.segments(w, turns)
        assertEquals(1, s.size)
        assertEquals("Wir teilen die Tour.", s[0].text)
    }

    @Test
    fun windowsCutAtQuietPoints() {
        val win = TokenAlign.windows(60f) { from, _ -> from + 2f }
        assertEquals(listOf(0f to 17f, 17f to 34f, 34f to 51f, 51f to 60f), win)
        assertEquals(listOf(0f to 10f), TokenAlign.windows(10f) { a, _ -> a })
    }

    @Test
    fun separateSpaceTokenBeforeDigitsStartsNewWord() {
        val t = arrayOf(" am", " ", "1", "8", ".", " Dez", "ember", " um", " ", "1", "6", " Uhr")
        val ts = FloatArray(t.size) { it * 0.1f }
        assertEquals(listOf("am", "18.", "Dezember", "um", "16", "Uhr"), TokenAlign.words(t, ts, null, 0f).map { it.text })
    }

    @Test
    fun sortedLookupMatchesLinearSearch_onLongRecordings() {
        val rnd = kotlin.random.Random(3)
        // 3000 Abschnitte mit Lücken und direkt anschließenden Grenzen (wie nach planChunks)
        val turns = mutableListOf<Transcriber.Turn>()
        var t = 0.5f
        repeat(3000) {
            val len = 0.6f + rnd.nextFloat() * 20f
            turns += Transcriber.Turn(t, t + len, rnd.nextInt(6))
            t += len + if (rnd.nextBoolean()) 0f else rnd.nextFloat() * 3f
        }
        val lookup = TokenAlign.SortedTurns.of(turns)!!
        val probes = List(20_000) { rnd.nextFloat() * (t + 5f) } + turns.flatMap { listOf(it.start, it.end) } + listOf(0f, -1f)
        for (p in probes) assertEquals("t=$p", TokenAlign.speakerAt(p, turns), lookup.speakerAt(p))
        // überlappende/unsortierte Abschnitte: kein Schnellweg (Ergebnis wie bisher)
        assertEquals(null, TokenAlign.SortedTurns.of(listOf(Transcriber.Turn(0f, 5f, 0), Transcriber.Turn(3f, 6f, 1))))
    }

    @Test
    fun refinementMovesSentenceToSpeakerWithMatchingVoice() {
        // Drei Sätze; die Sprechererkennung schlägt den zweiten (Stimme A) fälschlich Sprecher 1 zu
        val w = listOf(
            TokenAlign.Word("Ich", 0f, 0.5f), TokenAlign.Word("beginne.", 0.5f, 1.5f),
            TokenAlign.Word("Und", 2f, 2.5f), TokenAlign.Word("weiter.", 2.5f, 3.5f),
            TokenAlign.Word("Gut", 4f, 4.5f), TokenAlign.Word("so.", 4.5f, 5.5f),
            TokenAlign.Word("Danke", 6f, 6.5f), TokenAlign.Word("auch.", 6.5f, 7.5f),
        )
        val turns = listOf(Transcriber.Turn(0f, 1.8f, 0), Transcriber.Turn(1.9f, 7.6f, 1))
        val voiceA = floatArrayOf(1f, 0f); val voiceB = floatArrayOf(0f, 1f)
        val spk = TokenAlign.refineSpeakers(w, turns, embed = { a, _ ->
            if (a < 3f) voiceA else voiceB
        })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), spk.toList())
        val s = TokenAlign.segments(w, turns, renumber = false, speakers = spk)
        assertEquals(listOf("Ich beginne. Und weiter.", "Gut so. Danke auch."), s.map { it.text })
    }

    @Test
    fun refinementKeepsSingleSpeakerUntouched() {
        val w = listOf(TokenAlign.Word("Hallo.", 0f, 1.5f))
        val spk = TokenAlign.refineSpeakers(w, listOf(Transcriber.Turn(0f, 2f, 3)), embed = { _, _ -> floatArrayOf(1f) })
        assertEquals(listOf(3), spk.toList())
    }
}
