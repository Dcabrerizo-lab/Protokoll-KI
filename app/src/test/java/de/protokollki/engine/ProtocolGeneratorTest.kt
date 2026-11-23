package de.protokollki.engine

import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testet den Ablauf mit einem gefälschten Sprachmodell (prüft Prompts und Zusammenführung). */
class ProtocolGeneratorTest {

    private class FakeModel(
        override val contextSize: Int = 16384,
        val tokensPerChar: Double = 0.3,
        val answer: (user: String, grammar: String?) -> String,
    ) : TextModel {
        val prompts = mutableListOf<String>()
        override fun countTokens(text: String) = (text.length * tokensPerChar).toInt() + 1
        override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                              grammar: String?, progress: LlmProgress?): String {
            prompts += user
            progress?.onProgress(0, 1, 1); progress?.onProgress(1, 1, 1)
            return answer(user, grammar)
        }
    }

    private val segments = listOf(
        Segment(0, 0f, 5f, "Frau Becker, holen Sie bitte bis Freitag drei Angebote ein?"),
        Segment(1, 5f, 8f, "Ja, mache ich. Die Telefonliste habe ich übrigens schon erledigt."),
        Segment(0, 8f, 12f, "Gut. Bei Frau Schulz in der Lindenstraße 4 gab es einen Sturz."),
    )
    private val speakers = listOf(Speaker(0, "Herr Wagner"), Speaker(1, "Frau Becker"))
    private val monday = 1795431600000L // Montag, 23.11.2026

    private val summaryJson = """{"zusammenfassung": "Kurz.", "themen": [{"titel": "TOP 1: Umzug", "punkte": ["Angebote werden eingeholt. [0]"]}],
        "beschluesse": [], "offene_punkte": [], "aufgaben": [{"aufgabe": "Drei Angebote einholen [0]", "verantwortlich": "Frau Becker", "frist": "Freitag"}],
        "erledigte_aufgaben": ["A1 [1]"], "sensible_daten": ["Frau Schulz", "Lindenstraße 4", "Frau Becker"]}"""

    @Test
    fun singlePassProducesEverything() {
        val model = FakeModel { _, grammar ->
            assertTrue(grammar!!.contains("erledigte_aufgaben"))
            summaryJson
        }
        val r = ProtocolGenerator(model).generate(
            "Team", monday, segments, speakers, wantSummary = true,
            agenda = listOf("Umzug", "Sonstiges"),
            openTasks = listOf(ProtocolGenerator.OpenTask("A1", "t1", "m0", "Telefonliste erstellen", "Frau Becker")),
        ) { _, _ -> true }

        assertEquals(1, model.prompts.size)                       // nur ein LLM-Durchgang
        val prompt = model.prompts[0]
        assertTrue(prompt.contains("TOP 1: Umzug"))
        assertTrue(prompt.contains("A1: Telefonliste erstellen (Frau Becker)"))
        assertTrue(prompt.contains("[2] Herr Wagner: Gut."))      // Zeilennummern für Quellen
        assertEquals(listOf(0), r.summary.topics[0].refs)
        assertEquals("Fr., 27.11.2026", r.tasks[0].due)           // Frist exakt berechnet
        assertEquals(0, r.tasks[0].ref)
        assertEquals(listOf("t1"), r.completed.map { it.taskId })
        assertEquals(1, r.completedRefs["t1"])
        assertEquals(listOf("Frau Schulz", "Lindenstraße 4"), r.sensitiveTerms) // Teilnehmende nicht
    }

    @Test
    fun verbatimModeOnlyExtractsTasks() {
        val model = FakeModel { _, _ ->
            """{"aufgaben": [{"aufgabe": "X erledigen [1]", "verantwortlich": "offen", "frist": "morgen"}], "erledigte_aufgaben": [], "sensible_daten": []}"""
        }
        val r = ProtocolGenerator(model).generate("T", monday, segments, speakers, wantSummary = false) { _, _ -> true }
        assertTrue(r.summary.isEmpty)
        assertEquals("Di., 24.11.2026", r.tasks.single().due)
        assertTrue(model.prompts[0].contains("immer leere Liste"))
    }

    @Test
    fun longMeetingUsesMapReduceAndKeepsGlobalLineNumbers() {
        val many = (0 until 400).map { Segment(it % 2, it.toFloat(), it + 1f, "Beitrag Nummer $it mit etwas Inhalt zur Planung.") }
        val model = FakeModel(contextSize = 4096) { user, grammar ->
            if (grammar == null) "- Notiz [${Regex("\\[(\\d+)]").find(user.substringAfter("Transkript:"))!!.groupValues[1]}]"
            else summaryJson
        }
        val r = ProtocolGenerator(model).generate("Lang", monday, many, speakers, wantSummary = true) { _, _ -> true }
        val notes = model.prompts.dropLast(1)
        assertTrue("mehrere Abschnitte erwartet", notes.size >= 2)
        // jeder Abschnitt beginnt mit einer höheren globalen Zeilennummer
        val firstLines = notes.map { Regex("\\[(\\d+)] ").find(it.substringAfter("Transkript:"))!!.groupValues[1].toInt() }
        assertEquals(firstLines.sorted(), firstLines)
        assertTrue(firstLines.last() > 0)
        assertTrue(model.prompts.last().contains("Notizen zu Teil 2"))
        assertEquals("TOP 1: Umzug", r.summary.topics[0].title)
    }

    @Test
    fun cancelStopsProcessing() {
        val model = FakeModel { _, _ -> summaryJson }
        try {
            ProtocolGenerator(model).generate("T", monday, segments, speakers, true) { _, _ -> false }
            throw AssertionError("Abbruch erwartet")
        } catch (e: LlmException) {
            assertEquals(0, model.prompts.size)
        }
    }
}
