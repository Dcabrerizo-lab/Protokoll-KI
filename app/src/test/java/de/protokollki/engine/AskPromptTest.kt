package de.protokollki.engine

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskPromptTest {

    private val m = Meeting("m1", "Teamsitzung", 1_764_000_000_000L, audioFile = "", status = MeetingStatus.DONE,
        segments = listOf(Segment(0, 0f, 5f, "Wie hoch ist das Budget?"),
            Segment(1, 5f, 9f, "4000 Euro."), Segment(0, 9f, 12f, "Gut, so machen wir es.")),
        speakers = listOf(Speaker(0, "Frau Becker"), Speaker(1, "Herr Yilmaz")))

    private fun hit(seg: Int) = SearchHit("m1", "Teamsitzung", m.createdAt, seg, 0f, "", m.segments[seg].text, 1.0)

    @Test fun `prompt nummeriert fundstellen mit kontext und datum`() {
        val p = AskPrompt.build("Wie hoch ist das Budget?", listOf(hit(1)), listOf(m))
        assertTrue(p.contains("[1] Besprechung „Teamsitzung“ vom"))
        // Satz davor und danach als Kontext
        assertTrue(p.contains("Frau Becker: Wie hoch ist das Budget? / Herr Yilmaz: 4000 Euro. / Frau Becker: Gut"))
        assertTrue(p.contains(AskPrompt.NOTHING_FOUND))
    }

    @Test fun `bereinigt antwort und ungueltige marker`() {
        val raw = "<think>x</think>Antwort: Das Budget beträgt 4000 Euro [1, 2] . Später mehr [7]."
        assertEquals("Das Budget beträgt 4000 Euro [1][2]. Später mehr.", AskPrompt.clean(raw, 2))
    }

    @Test fun `leere antwort wird zu nichts gefunden`() {
        assertEquals(AskPrompt.NOTHING_FOUND, AskPrompt.clean("  ", 3))
    }

    @Test fun `zitate in reihenfolge ohne doppelte`() {
        assertEquals(listOf(2, 1), AskPrompt.citations("A [2]. B [1][2]."))
        assertEquals(emptyList<Int>(), AskPrompt.citations("keine"))
    }

    @Test
    fun relaxedPromptHasNoEscapeClause() {
        val hit = SearchHit("m1", "Teamsitzung", 0L, -1, 0f, "", "Tour Nord wird geteilt", 1.0)
        val p = AskPrompt.build("Was wurde zur Tour Nord beschlossen?", listOf(hit), emptyList(), strict = false)
        assertTrue(!p.contains(AskPrompt.NOTHING_FOUND))
        assertTrue(p.contains("[1]") && p.contains("Tour Nord wird geteilt"))
    }
}
