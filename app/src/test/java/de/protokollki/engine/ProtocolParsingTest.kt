package de.protokollki.engine

import de.protokollki.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParsingTest {
    @Test
    fun parsesSummaryWithRefs() {
        val s = ProtocolGenerator.parseSummary(
            """{"zusammenfassung": "Kurz. [3]", "themen": [{"titel": "Umzug", "punkte": ["A ist so. [2]", "B ohne Beleg", ""]}], "beschluesse": ["Ab März neu. [7, 8]"], "offene_punkte": ["Unklar [99]"]}""",
            segmentCount = 10)
        assertEquals("Kurz.", s.overview)
        assertEquals(listOf("A ist so.", "B ohne Beleg"), s.topics[0].points)
        assertEquals(listOf(2, -1), s.topics[0].refs)
        assertEquals(listOf("Ab März neu."), s.decisions)
        assertEquals(listOf(7), s.decisionRefs)
        assertEquals(listOf(-1), s.openPointRefs) // 99 außerhalb des Transkripts
    }

    @Test
    fun parsesTasksAndNormalizes() {
        val t = ProtocolGenerator.parseTasks(
            """{"aufgaben": [{"aufgabe": "Angebote einholen [4]", "verantwortlich": "", "frist": "unbekannt"}]}""", 10)
        assertEquals("Angebote einholen", t[0].text)
        assertEquals("offen", t[0].owner)
        assertEquals("offen", t[0].due)
        assertEquals(4, t[0].ref)
    }

    @Test
    fun splitRefVariants() {
        assertEquals("Text." to 5, ProtocolGenerator.splitRef("Text. [5]", 10))
        assertEquals("Text." to 5, ProtocolGenerator.splitRef("Text [5].", 10))
        assertEquals("Text." to 1, ProtocolGenerator.splitRef("Text. [1–3]", 10))
        assertEquals("Text" to -1, ProtocolGenerator.splitRef("Text", 10))
        assertEquals("Text" to -1, ProtocolGenerator.splitRef("Text [12]", 10))
    }

    @Test
    fun parsesCompletedAndSensitive() {
        val raw = """{"aufgaben": [], "erledigte_aufgaben": ["A2 [14]", "A10", "Quatsch"], "sensible_daten": ["Frau Schulz [3]", "Person 2", "Herr Wagner", "ab"]}"""
        assertEquals(listOf("A2" to 14, "A10" to -1), ProtocolGenerator.parseCompleted(raw, 20))
        assertEquals(listOf("Frau Schulz"), ProtocolGenerator.parseSensitive(raw, listOf("Herr Wagner")))
    }

    @Test
    fun dedupesSimilarTasksKeepingOwner() {
        val t = ProtocolGenerator.dedupe(listOf(
            TaskItem("Drei Angebote für Möbel einholen", "offen", id = "1"),
            TaskItem("Drei Angebote für die Möbel einholen", "Frau Becker", id = "2"),
            TaskItem("Telefonanlage umziehen", "Herr Wagner", id = "3"),
        ))
        assertEquals(2, t.size)
        assertEquals("Frau Becker", t[0].owner)
    }

    @Test
    fun malformedJsonThrowsLlmException() {
        try {
            ProtocolGenerator.parseSummary("keine Antwort"); throw AssertionError("erwartet Exception")
        } catch (e: LlmException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
