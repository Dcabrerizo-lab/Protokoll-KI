package de.protokollki.engine

import de.protokollki.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolParsingTest {
    @Test
    fun parsesSummary() {
        val s = ProtocolGenerator.parseSummary(
            """{"zusammenfassung": "Kurz.", "themen": [{"titel": "Umzug", "punkte": ["A", ""]}], "beschluesse": ["B"], "offene_punkte": []}""")
        assertEquals("Kurz.", s.overview)
        assertEquals(listOf("A"), s.topics[0].points)
        assertEquals(listOf("B"), s.decisions)
    }

    @Test
    fun parsesTasksAndNormalizes() {
        val t = ProtocolGenerator.parseTasks(
            """{"aufgaben": [{"aufgabe": "Angebote einholen", "verantwortlich": "", "frist": "unbekannt"}]}""")
        assertEquals(TaskItem("Angebote einholen", "offen", "offen"), t[0])
    }

    @Test
    fun dedupesSimilarTasks() {
        val t = ProtocolGenerator.dedupe(listOf(
            TaskItem("Drei Angebote für Möbel einholen", "offen"),
            TaskItem("Drei Angebote für die Möbel einholen", "Frau Becker"),
            TaskItem("Telefonanlage umziehen", "Herr Wagner"),
        ))
        assertEquals(2, t.size)
        assertEquals("Frau Becker", t[0].owner)
    }
}
