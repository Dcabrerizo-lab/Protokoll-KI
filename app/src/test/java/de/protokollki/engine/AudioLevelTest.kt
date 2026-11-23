package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioLevelTest {
    private fun tone(amp: Float, sec: Int = 2) =
        FloatArray(16000 * sec) { (amp * sin(2 * PI * 220 * it / 16000)).toFloat() }

    private fun rms(a: FloatArray) = sqrt(a.sumOf { (it * it).toDouble() } / a.size).toFloat()

    @Test
    fun quietSpeechIsRaisedToTarget() {
        val out = AudioLevel.normalize(tone(0.01f))
        assertEquals(0.1f, rms(out), 0.01f)
    }

    @Test
    fun loudSpeechIsLoweredAndClipped() {
        val out = AudioLevel.normalize(tone(0.9f))
        assertEquals(0.1f, rms(out), 0.01f)
        assertTrue(out.all { it in -1f..1f })
    }

    @Test
    fun pausesDoNotInflateGain() {
        // Hälfte Stille, Hälfte Sprache: Pegel wird an der Sprache gemessen
        val a = tone(0.05f, 1) + FloatArray(16000)
        assertEquals(2.83f, AudioLevel.gain(a), 0.15f)
    }

    @Test
    fun silenceAndEmptyInputAreSafe() {
        assertEquals(1f, AudioLevel.gain(FloatArray(10)), 0f)
        assertTrue(AudioLevel.gain(FloatArray(16000)) <= 100f)
        val ok = tone(0.141f)
        assertSame(ok, AudioLevel.normalize(ok))
    }
}
