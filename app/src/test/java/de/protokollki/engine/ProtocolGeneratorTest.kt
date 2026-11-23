package de.protokollki.engine

import de.protokollki.data.ProtocolTemplate
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

    @Test
    fun templateAndVocabularyReachThePrompt() {
        val model = FakeModel { _, _ -> summaryJson }
        ProtocolGenerator(model).generate(
            "Vorstand", monday, segments, speakers, wantSummary = true,
            template = ProtocolTemplate.VEREINSSITZUNG,
            vocabulary = listOf("Kowalczyk", "SGB XI"),
        ) { _, _ -> true }
        val prompt = model.prompts.single()
        assertTrue(prompt.contains("Art: Vereinssitzung"))
        assertTrue(prompt.contains("Abstimmung: X Ja, Y Nein, Z Enthaltungen"))
        assertTrue(prompt.contains("Richtige Schreibweise von Namen und Fachbegriffen: Kowalczyk, SGB XI"))
    }

    @Test
    fun everyTemplateExceptGeneralHasRules() {
        ProtocolTemplate.entries.forEach { t ->
            val rule = ProtocolGenerator.templateRule(t)
            if (t == ProtocolTemplate.ALLGEMEIN) assertEquals(null, rule)
            else assertTrue("Regel fehlt: $t", !rule.isNullOrBlank())
        }
        val general = ProtocolGenerator.extraRules(false, false)
        assertTrue(!general.contains("Vorlage"))
    }

    @Test
    fun generalTemplateKeepsPromptUnchanged() {
        val model = FakeModel { _, _ -> summaryJson }
        ProtocolGenerator(model).generate("Team", monday, segments, speakers, wantSummary = true) { _, _ -> true }
        assertTrue(!model.prompts.single().contains("Art:"))
        assertTrue(!model.prompts.single().contains("Richtige Schreibweise"))
    }

    @Test
    fun missingTasksTriggerDedicatedTaskPass() {
        val noTasks = summaryJson.replace(
            """[{"aufgabe": "Drei Angebote einholen [0]", "verantwortlich": "Frau Becker", "frist": "Freitag"}]""", "[]")
        assertTrue(noTasks != summaryJson)
        val model = FakeModel { _, grammar ->
            if (grammar!!.contains("zusammenfassung")) noTasks
            else """{"aufgaben": [{"aufgabe": "Drei Angebote einholen [0]", "verantwortlich": "Frau Becker", "frist": "Freitag"}], "erledigte_aufgaben": [], "sensible_daten": []}"""
        }
        val r = ProtocolGenerator(model).generate("Team", monday, segments, speakers, wantSummary = true) { _, _ -> true }
        assertEquals(2, model.prompts.size)
        assertEquals("Kurz.", r.summary.overview)
        assertEquals(listOf("Drei Angebote einholen"), r.tasks.map { it.text })
    }

    @Test
    fun genericOwnerIsResolvedFromAddress() {
        val segs = listOf(
            Segment(0, 0f, 3f, "Frau Becker, können Sie bis Freitag drei Angebote einholen?"),
            Segment(1, 3f, 5f, "Ja, das mache ich."),
            Segment(0, 5f, 8f, "Herrn Dr. Wagner bitten wir um den Entwurf."),
        )
        val name = { id: Int -> "Person ${id + 1}" }
        assertEquals("Frau Becker", ProtocolGenerator.resolveOwner("Person 2", 1, segs, name))
        assertEquals("Frau Becker", ProtocolGenerator.resolveOwner("Person 2", 0, segs, name))
        assertEquals("Herr Wagner", ProtocolGenerator.resolveOwner("offen", 2, segs, name))
        // Konkreter Name bleibt, ungültiger Beleg ändert nichts
        assertEquals("Herr Schulz", ProtocolGenerator.resolveOwner("Herr Schulz", 1, segs, name))
        assertEquals("Person 2", ProtocolGenerator.resolveOwner("Person 2", 9, segs, name))
        // Spricht die angesprochene Person selbst, wird sie nicht übernommen
        val self = { id: Int -> if (id == 0) "Frau Becker" else "Person 2" }
        assertEquals("Person 2", ProtocolGenerator.resolveOwner("Person 2", 0, segs.take(1), self))
    }

    @Test
    fun addressedClientsExcludeTaskOwners() {
        val segs = listOf(
            Segment(0, 0f, 3f, "Wir besprechen heute Herrn Krüger aus der Lindenstraße."),
            Segment(1, 3f, 5f, "Frau Schmidt beantragt die Höherstufung, Herr Krüger braucht einen Rollator."),
        )
        val tasks = listOf(de.protokollki.data.TaskItem("Höherstufung beantragen", "Frau Schmidt", "", ref = 1))
        assertEquals(listOf("Krüger"), ProtocolGenerator.addressedClients(segs, tasks))
        val name = { id: Int -> "Person ${id + 1}" }
        assertEquals("Frau Schmidt", ProtocolGenerator.resolveOwner("Sie", 1, segs, name))
    }

    @Test
    fun missingRefIsLocatedAndOwnerTakenFromTaskText() {
        val segs = listOf(
            Segment(0, 0f, 3f, "Guten Morgen, wir sprechen über den Umzug."),
            Segment(0, 3f, 6f, "Frau Becker, können Sie bis Freitag drei Angebote für die Möbel einholen?"),
            Segment(1, 6f, 8f, "Ja, das mache ich."),
        )
        assertEquals(1, ProtocolGenerator.locateRef("Drei Angebote für Möbel einholen", segs))
        assertEquals(-1, ProtocolGenerator.locateRef("Kaffee kaufen", segs))
        val name = { id: Int -> "Person ${id + 1}" }
        assertEquals("Herr Wagner", ProtocolGenerator.resolveOwner("Person 1", -1, segs, name,
            "Herr Wagner Telefonanlage klären mit Anbieter"))
        assertEquals("Frau Becker", ProtocolGenerator.resolveOwner("Person 3", 1, segs, name, "Angebote einholen"))
    }
}
