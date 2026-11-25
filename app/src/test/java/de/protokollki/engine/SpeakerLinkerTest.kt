package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class SpeakerLinkerTest {
    private val rnd = Random(7)

    /** Zufälliger "Stimm-Vektor" je Person, plus leichtes Rauschen je Abschnitt. */
    private val voices = List(5) { FloatArray(64) { rnd.nextFloat() * 2 - 1 } }
    private fun voice(p: Int, noise: Float = 0.25f) =
        FloatArray(64) { voices[p][it] + (rnd.nextFloat() * 2 - 1) * noise }

    @Test
    fun samePeopleAcrossWindows_getSameIds() {
        // Abschnitt 1: A=0, B=1; Abschnitt 2: B=0, C=1, A=2 (lokale IDs vertauscht)
        val w1 = mapOf(0 to voice(0), 1 to voice(1))
        val w2 = mapOf(0 to voice(1), 1 to voice(2), 2 to voice(0))
        val w3 = mapOf(5 to voice(2))
        val links = SpeakerLinker.link(listOf(w1, w2, w3), maxSpeakers = 0)
        val a = links[0].getValue(0); val b = links[0].getValue(1)
        assertNotEquals(a, b)
        assertEquals(b, links[1][0])
        assertEquals(a, links[1][2])
        val c = links[1].getValue(1)
        assertNotEquals(a, c); assertNotEquals(b, c)
        assertEquals(c, links[2][5])
        assertEquals(setOf(0, 1, 2), links.flatMap { it.values }.toSet())
    }

    @Test
    fun twoLocalSpeakersInOneWindow_neverMergedWithoutLimit() {
        // zwei sehr ähnliche Stimmen im selben Abschnitt bleiben getrennt
        val w1 = mapOf(0 to voice(0, 0.01f), 1 to voice(0, 0.01f))
        val links = SpeakerLinker.link(listOf(w1), maxSpeakers = 0)
        assertNotEquals(links[0][0], links[0][1])
    }

    @Test
    fun maxSpeakers_mergesMostSimilar() {
        val w1 = mapOf(0 to voice(0), 1 to voice(1))
        val w2 = mapOf(0 to voice(3), 1 to voice(4))   // zwei neue Personen
        val free = SpeakerLinker.link(listOf(w1, w2), maxSpeakers = 0)
        assertEquals(4, free.flatMap { it.values }.toSet().size)
        val limited = SpeakerLinker.link(listOf(w1, w2), maxSpeakers = 2)
        assertEquals(2, limited.flatMap { it.values }.toSet().size)
        assertEquals(setOf(0, 1), limited.flatMap { it.values }.toSet())
    }

    @Test
    fun emptyWindowsAreHarmless() {
        val links = SpeakerLinker.link(listOf(emptyMap(), mapOf(3 to voice(1)), emptyMap()), maxSpeakers = 3)
        assertEquals(3, links.size)
        assertEquals(0, links[1][3])
        assertEquals(emptyMap<Int, Int>(), links[0])
    }
}
