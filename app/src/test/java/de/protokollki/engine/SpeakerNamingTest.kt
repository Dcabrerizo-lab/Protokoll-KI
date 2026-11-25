package de.protokollki.engine

import de.protokollki.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerNamingTest {
    private fun seg(s: Int, t: String) = Segment(s, 0f, 1f, t)

    @Test
    fun namesFromIntroductionAndAddress() {
        val segs = listOf(
            seg(0, "Guten Morgen, mein Name ist Anna Becker, ich leite heute die Runde."),
            seg(0, "Frau Schmidt, wie war das Wochenende auf Station?"),
            seg(1, "Ruhig, bis auf den Sturz am Sonntag."),
            seg(0, "Danke. Herr Yilmaz, haben Sie den Arzt erreicht?"),
            seg(2, "Ja, der kommt morgen."),
            seg(1, "Danke, Herr Yilmaz."),
        )
        val n = SpeakerNaming.guess(segs)
        assertEquals("Anna Becker", n[0])
        assertEquals("Herr Yilmaz", n[2])
        // Frau Schmidt nur einmal angesprochen: zu wenig Hinweise → lieber kein Name
        assertTrue(1 !in n || n[1] == "Frau Schmidt")
    }

    @Test
    fun participantListConfirmsSingleAddress() {
        val segs = listOf(
            seg(0, "Frau Schmidt, wie sehen Sie das?"),
            seg(1, "Ich bin dafür."),
            seg(0, "Gut. Und Sie, Frau Schmidt? Noch Einwände?"),
            seg(1, "Nein."),
        )
        assertEquals("Frau Schmidt", SpeakerNaming.guess(segs)[1])
        assertEquals(emptyMap<Int, String>(), SpeakerNaming.guess(segs.take(2)))
    }

    @Test
    fun speakerWhoSaysTheNameIsNotThatPerson() {
        val segs = listOf(
            seg(0, "Ich bin Herr Kaiser."),
            seg(1, "Herr Kaiser, schön, dass Sie da sind."),
            seg(0, "Danke."),
        )
        val n = SpeakerNaming.guess(segs)
        assertEquals("Herr Kaiser", n[0])
        assertTrue(n[1] == null)
    }

    @Test
    fun opinionPhrasesAreNotNames() {
        val segs = listOf(seg(0, "Ich bin Meinung, dass wir warten."), seg(0, "Ich bin Pflegekraft hier."))
        assertEquals(emptyMap<Int, String>(), SpeakerNaming.guess(segs))
    }

    @Test
    fun mentionOfThirdPersonIsNoAddress() {
        val segs = listOf(
            seg(0, "Hat Frau Krüger ihre Tabletten genommen?"),
            seg(1, "Ja, heute früh."),
            seg(0, "Hat Frau Krüger auch gegessen?"),
            seg(1, "Ja."),
        )
        assertEquals(emptyMap<Int, String>(), SpeakerNaming.guess(segs))
    }

    @Test
    fun surnameKey() {
        assertEquals("Becker", SpeakerNaming.surname("Frau Dr. Anna Becker"))
        assertEquals("Yilmaz", SpeakerNaming.surname("Herr Yilmaz"))
    }
}
