package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMathTest {
    private fun v(vararg x: Float) = VoiceMath.normalize(floatArrayOf(*x))

    @Test
    fun assignsBestUniqueMatches() {
        val profiles = listOf("anna" to v(1f, 0f, 0f), "ben" to v(0f, 1f, 0f))
        val speakers = mapOf(0 to v(0.95f, 0.1f, 0f), 1 to v(0.1f, 0.97f, 0.05f), 2 to v(0f, 0f, 1f))
        assertEquals(mapOf(0 to "anna", 1 to "ben"), VoiceMath.assign(speakers, profiles))
    }

    @Test
    fun ambiguousMatchIsRejected() {
        val profiles = listOf("a" to v(1f, 1f, 0f), "b" to v(1f, 0.95f, 0.02f))
        assertTrue(VoiceMath.assign(mapOf(0 to v(1f, 0.98f, 0.01f)), profiles).isEmpty())
    }

    @Test
    fun oneProfileNotUsedTwice() {
        val profiles = listOf("a" to v(1f, 0f))
        val r = VoiceMath.assign(mapOf(0 to v(1f, 0.05f), 1 to v(0.99f, 0.1f)), profiles)
        assertEquals(mapOf(0 to "a"), r)
    }

    @Test
    fun collectAudioTakesLongestSpansUpToLimit() {
        val samples = FloatArray(16000 * 10) { it.toFloat() }
        val out = VoiceMath.collectAudio(samples, listOf(0f to 1f, 2f to 6f, 7f to 9f), maxSeconds = 5f)
        assertEquals(16000 * 5, out.size)
        assertEquals(2f * 16000, out[0])          // längster Abschnitt zuerst
    }

    @Test
    fun speechSecondsIgnoresSilence() {
        val s = FloatArray(16000 * 4)
        for (i in 0 until 16000 * 2) s[i] = if (i % 2 == 0) 0.2f else -0.2f
        val sec = VoiceMath.speechSeconds(s)
        assertTrue(sec in 1.9f..2.1f)
    }
}
