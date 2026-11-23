package de.protokollki.engine

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingSearchTest {

    private fun meeting(id: String, title: String, day: Int, vararg lines: Pair<Int, String>,
                        summary: Summary = Summary(), tasks: List<TaskItem> = emptyList()) = Meeting(
        id = id, title = title, createdAt = 1_700_000_000_000L + day * 86_400_000L, audioFile = "",
        status = MeetingStatus.DONE,
        segments = lines.mapIndexed { i, (s, t) -> Segment(s, i * 10f, i * 10f + 9f, t) },
        speakers = listOf(Speaker(0, "Frau Becker"), Speaker(1, "Herr Yilmaz")),
        summary = summary, tasks = tasks,
    )

    private val team = meeting("m1", "Teamsitzung", 1,
        0 to "Guten Morgen zusammen.",
        1 to "Das Budget für die Möbel liegt bei 4000 Euro.",
        0 to "Dann holen wir drei Angebote ein.",
        1 to "Die Tour Nord wird ab März geteilt.",
        summary = Summary(topics = listOf(Topic("Ausstattung", listOf("Budgetrahmen 4000 Euro"), listOf(1)))),
        tasks = listOf(TaskItem("Angebote einholen", "Frau Becker", "Fr., 27.11.2026", ref = 2)))
    private val fall = meeting("m2", "Fallbesprechung", 5,
        0 to "Herr Schulz braucht einen neuen Pflegegrad-Antrag.",
        1 to "Die Budgetplanung für 2027 besprechen wir nächste Woche.")

    @Test fun `findet passende stelle und springt zum segment`() {
        val hits = MeetingSearch.search(listOf(team, fall), "Möbel Budget")
        val top = hits.first()
        assertEquals("m1", top.meetingId)
        assertEquals(1, top.segment)
        assertEquals(10f, top.start)
        assertEquals("Herr Yilmaz", top.speaker)
    }

    @Test fun `stammformen und komposita`() {
        // "Angeboten" -> "Angebot", "Budget" findet auch "Budgetplanung"
        assertTrue(MeetingSearch.search(listOf(team), "Angeboten").any { it.segment == 2 })
        assertTrue(MeetingSearch.search(listOf(fall), "Budget").any { it.meetingId == "m2" && it.segment == 1 })
    }

    @Test fun `umlaute und gross klein egal`() {
        assertTrue(MeetingSearch.search(listOf(team), "MOEBEL").isNotEmpty())
        assertTrue(MeetingSearch.search(listOf(team), "möbel").isNotEmpty())
    }

    @Test fun `aufgaben sind durchsuchbar mit beleg`() {
        val hits = MeetingSearch.search(listOf(team), "Aufgabe Angebote Becker")
        assertTrue(hits.any { it.segment == 2 })
    }

    @Test fun `nur stoppwoerter oder leer liefert nichts`() {
        assertTrue(MeetingSearch.search(listOf(team), "").isEmpty())
        assertTrue(MeetingSearch.search(listOf(team), "was wurde da besprochen?").isEmpty())
        assertTrue(MeetingSearch.search(emptyList(), "Budget").isEmpty())
    }

    @Test fun `unbekannter begriff liefert nichts`() {
        assertTrue(MeetingSearch.search(listOf(team, fall), "Weihnachtsfeier").isEmpty())
    }

    @Test fun `unverarbeitete besprechungen ohne transkript werden ignoriert`() {
        val raw = Meeting("m3", "Neu", 0L, audioFile = "", status = MeetingStatus.RECORDED)
        assertTrue(MeetingSearch.search(listOf(raw), "Neu").isEmpty())
    }

    @Test fun `limit und keine doppelten segmente`() {
        val hits = MeetingSearch.search(listOf(team, fall), "Budget", limit = 2)
        assertTrue(hits.size <= 2)
        val all = MeetingSearch.search(listOf(team, fall), "Budget")
        assertEquals(all.size, all.map { it.meetingId to (if (it.segment >= 0) "${it.segment}" else it.text) }.distinct().size)
    }

    @Test fun stem() {
        assertEquals("angebot", MeetingSearch.stem("angeboten"))
        assertEquals("planung", MeetingSearch.stem("planungen"))
        assertEquals("tour", MeetingSearch.stem("tour"))
    }
}
