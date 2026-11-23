package de.protokollki.privacy

import de.protokollki.data.FollowUp
import de.protokollki.data.Meeting
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private fun r(text: String, vararg terms: String) = Redactor.redactText(text, terms.toList())

    private fun assertUnchanged(text: String, vararg terms: String) = assertEquals(text, r(text, *terms))

    // ------------------------------------------------------------------ Begriffe

    @Test fun personGetsLetterLabel() =
        assertEquals("Person A kommt morgen.", r("Anna Müller kommt morgen.", "Anna Müller"))

    @Test fun caseInsensitive() {
        assertEquals("Person A und Person A und Person A", r("Müller und MÜLLER und müller", "Müller"))
    }

    @Test fun genitiveS() {
        assertEquals("Person As Bericht liegt vor.", r("Müllers Bericht liegt vor.", "Müller"))
        assertEquals("Person As Antrag", r("Anna Müllers Antrag", "Anna Müller"))
        assertEquals("Person As Akte", r("MÜLLERS Akte", "Müller"))
    }

    @Test fun wordBoundaries() {
        assertUnchanged("Die Annahme ist falsch, Hannah stimmt zu.", "Anna")
        assertUnchanged("Der Müllereibetrieb", "Müller")
        assertEquals("Person A, die Annahme", r("Anna, die Annahme", "Anna"))
        assertEquals("(Person A)", r("(Anna)", "Anna"))
        assertEquals("Person A-Bericht", r("Müller-Bericht", "Müller"))
    }

    @Test fun overlappingTermsLongestFirst() {
        // "Anna Müller-Schmidt" darf nicht als "Anna Müller" + Rest ersetzt werden
        val out = r("Anna Müller-Schmidt und Anna Müller", "Anna Müller", "Anna Müller-Schmidt")
        assertEquals("Person B und Person A", out)
        // einzelner Vorname gehört eindeutig zu "Anna Müller" → dieselbe Person
        assertEquals("Person A und Person A Schmidt", r("Anna Müller und Anna Schmidt", "Anna", "Anna Müller"))
        // Begriff, der einen anderen enthält
        assertEquals("[geschwärzt] und [geschwärzt]",
            r("St. Josef Klinikum und Klinikum", "Klinikum", "St. Josef Klinikum"))
    }

    @Test fun whitespaceInsideTermIsFlexible() =
        assertEquals("Person A sagt", r("Anna\n  Müller sagt", "Anna Müller"))

    @Test fun honorificIsAbsorbed() {
        assertEquals("Person A meldet sich.", r("Frau Müller meldet sich.", "Müller"))
        assertEquals("Person A meldet sich.", r("Herr Dr. Müller meldet sich.", "Müller"))
        assertEquals("Person A meldet sich.", r("Hr. Müller meldet sich.", "Herr Müller"))
    }

    @Test fun derivedFirstAndLastNameMapToSamePerson() {
        val out = r("Anna Müller war da. Frau Müller hat zugesagt, Anna bringt Kuchen.", "Anna Müller")
        assertEquals("Person A war da. Person A hat zugesagt, Person A bringt Kuchen.", out)
    }

    @Test fun derivedNamesNeedCapitalLetter() {
        // abgeleitet aus "Herbert Lange": "lange" als Adjektiv bleibt stehen
        assertEquals("Das dauert lange, sagt Person A.", r("Das dauert lange, sagt Lange.", "Herbert Lange"))
        // ausdrücklich gelisteter Begriff dagegen in jeder Schreibweise
        assertEquals("Das dauert [geschwärzt]", r("Das dauert lange", "lange"))
    }

    @Test fun nonPersonTermsAreBlackedOut() {
        assertEquals("Termin im [geschwärzt] wegen [geschwärzt].",
            r("Termin im Sankt-Josef-Krankenhaus wegen Pflegegrad 3.", "Sankt-Josef-Krankenhaus", "Pflegegrad 3"))
        assertEquals("[geschwärzt] zahlt", r("AOK zahlt", "AOK"))
    }

    @Test fun personClassification() {
        assertTrue(RedactionPlan.looksLikePerson("Anna Müller"))
        assertTrue(RedactionPlan.looksLikePerson("Müller"))
        assertTrue(RedactionPlan.looksLikePerson("Ludwig van Beethoven"))
        assertTrue(RedactionPlan.looksLikePerson("Hans-Peter Müller-Lüdenscheidt"))
        assertTrue(RedactionPlan.looksLikePerson("Neuhaus"))
        assertFalse(RedactionPlan.looksLikePerson("Pflegegrad 3"))
        assertFalse(RedactionPlan.looksLikePerson("Sankt-Josef-Krankenhaus"))
        assertFalse(RedactionPlan.looksLikePerson("Jugendamt Köln"))
        assertFalse(RedactionPlan.looksLikePerson("Diabetes"))
        assertFalse(RedactionPlan.looksLikePerson("Depression"))
        assertFalse(RedactionPlan.looksLikePerson("AOK"))
        assertFalse(RedactionPlan.looksLikePerson("geheimes projekt"))
    }

    @Test fun labelsBeyondZ() {
        assertEquals("A", RedactionPlan.letters(0))
        assertEquals("Z", RedactionPlan.letters(25))
        assertEquals("AA", RedactionPlan.letters(26))
        assertEquals("AB", RedactionPlan.letters(27))
        assertEquals("BA", RedactionPlan.letters(52))
    }

    @Test fun emptyAndJunkTermsAreIgnored() {
        assertUnchanged("Ein ganz normaler Satz.", "", " ", "a", "\"\"")
        assertUnchanged("")
        assertEquals("Person A kommt", r("Müller kommt", " „Müller.“ "))
    }

    @Test fun regexMetaCharactersInTermsAreLiteral() {
        assertEquals("Projekt [geschwärzt] läuft", r("Projekt X(1)+ läuft", "X(1)+"))
        assertUnchanged("Projekt X11 läuft", "X(1)+")
    }

    // ------------------------------------------------------------------ Muster

    @Test fun phoneNumbers() {
        assertEquals("Ruf an: [Telefon].", r("Ruf an: 0171 1234567."))
        assertEquals("Tel. [Telefon]", r("Tel. +49 30 1234567"))
        assertEquals("Büro [Telefon], danke", r("Büro 030/123456, danke"))
        assertEquals("[Telefon]", r("(030) 123456"))
        assertEquals("[Telefon]", r("+49 (0) 30 12345-67"))
        assertEquals("[Telefon]", r("0049 171 1234567"))
        assertEquals("[Telefon]", r("0171-1234567"))
        assertEquals("[Telefon]", r("+491711234567"))
    }

    @Test fun noFalsePositivesOnDatesTimesAndAmounts() {
        assertUnchanged("Abgabe bis 27.11.2026, Treffen um 10:30 Uhr.")
        assertUnchanged("Am 07.11.2026 von 09:00 bis 10:30.")
        assertUnchanged("Kosten: 1.500 Euro, 2026 geplant, 12 Teilnehmende, 0,50 € pro Stück.")
        assertUnchanged("PLZ 01067 Dresden, Zimmer 0815.")
        assertUnchanged("Zeitraum 2025-2026, Version 1.2.3, Seite 12/13.")
        assertUnchanged("Am 01.02.1950 wurde der Verein gegründet.")
    }

    @Test fun emails() {
        assertEquals("Schreib an [E-Mail] bitte.", r("Schreib an anna.mueller@example.org bitte."))
        assertEquals("[E-Mail], [E-Mail]", r("a+b@sub.firma.de, jörg@straße.de"))
        assertUnchanged("Termin @ 10 Uhr")
    }

    @Test fun iban() {
        assertEquals("IBAN: [IBAN].", r("IBAN: DE89 3704 0044 0532 0130 00."))
        assertEquals("[IBAN]", r("DE89370400440532013000"))
    }

    @Test fun birthdates() {
        assertEquals("Herr X, geb. [Geburtsdatum], wohnt", r("Herr X, geb. 01.02.1950, wohnt"))
        assertEquals("geboren am [Geburtsdatum].", r("geboren am 1. Februar 1950."))
        assertEquals("Geboren am [Geburtsdatum]", r("Geboren am 3.4.52"))
        assertEquals("Geburtsdatum: [Geburtsdatum]", r("Geburtsdatum: 12.12.1980"))
        assertEquals("geb. [Geburtsdatum]", r("geb. 1950"))
    }

    @Test fun addresses() {
        assertEquals("Sie wohnt [Adresse].", r("Sie wohnt Hauptstraße 5."))
        assertEquals("[Adresse] ist neu", r("Berliner Str. 12a ist neu"))
        assertEquals("Treffpunkt [Adresse]", r("Treffpunkt Am Weg 3"))
        assertEquals("[Adresse]", r("Lindenallee 7-9"))
        assertEquals("[Adresse].", r("Goethestr. 10, 10115 Berlin."))
        assertEquals("[Adresse]", r("Marktplatz 1"))
        assertEquals("[Adresse]", r("St.-Georg-Straße 3"))
    }

    @Test fun addressNoFalsePositives() {
        assertUnchanged("Wir sind auf Platz 3 gelandet.")
        assertUnchanged("Der Rückweg 30 Minuten, die Straße war voll.")
        assertUnchanged("Der Weg ist das Ziel.")
    }

    // ------------------------------------------------------------------ ganze Besprechung

    private fun meeting() = Meeting(
        id = "m1",
        title = "Fallbesprechung Müller",
        createdAt = 0L,
        audioFile = "/dev/null",
        segments = listOf(
            Segment(0, 0f, 4f, "Frau Müller hat angerufen, Nummer 0171 1234567."),
            Segment(1, 4f, 9f, "Ich habe mit Jonas Becker gesprochen, Müllers Antrag läuft."),
        ),
        speakers = listOf(Speaker(0, "Sabine"), Speaker(1, "Jonas Becker")),
        summary = Summary(
            overview = "Anna Müller braucht Unterstützung.",
            topics = listOf(Topic("Antrag Müller", listOf("Becker prüft", "E-Mail an anna@example.de"))),
            decisions = listOf("Müller erhält Hilfe"),
            openPoints = listOf("Wohnung Hauptstraße 5"),
        ),
        tasks = listOf(TaskItem("Müller zurückrufen", owner = "Sabine", due = "Fr., 27.11.2026", id = "t1",
            doneNote = "Erledigt laut Müller")),
        followUps = listOf(FollowUp("t0", "m0", "Antrag für Anna stellen", "Becker", done = true)),
        agenda = listOf("Situation Müller"),
        sensitiveTerms = listOf("Anna Müller"),
    )

    @Test fun redactsAllFieldsConsistently() {
        val out = Redactor.redact(meeting(), listOf("Becker"))
        assertEquals("Fallbesprechung Person A", out.title)
        assertEquals("Person A hat angerufen, Nummer [Telefon].", out.segments[0].text)
        assertEquals("Ich habe mit Person B gesprochen, Person As Antrag läuft.", out.segments[1].text)
        assertEquals("Person A braucht Unterstützung.", out.summary.overview)
        assertEquals("Antrag Person A", out.summary.topics[0].title)
        assertEquals(listOf("Person B prüft", "E-Mail an [E-Mail]"), out.summary.topics[0].points)
        assertEquals(listOf("Person A erhält Hilfe"), out.summary.decisions)
        assertEquals(listOf("Wohnung [Adresse]"), out.summary.openPoints)
        assertEquals("Person A zurückrufen", out.tasks[0].text)
        assertEquals("Sabine", out.tasks[0].owner)
        assertEquals("Fr., 27.11.2026", out.tasks[0].due)
        assertEquals("Erledigt laut Person A", out.tasks[0].doneNote)
        assertEquals("Antrag für Person A stellen", out.followUps[0].text)
        assertEquals("Person B", out.followUps[0].owner)
        assertEquals(listOf("Situation Person A"), out.agenda)
        assertTrue(out.sensitiveTerms.isEmpty())
    }

    @Test fun keepsIdsRefsAndStructure() {
        val m = meeting()
        val out = Redactor.redact(m, emptyList())
        assertEquals(m.id, out.id)
        assertEquals("t1", out.tasks[0].id)
        assertEquals(m.segments.map { it.start to it.speakerId }, out.segments.map { it.start to it.speakerId })
        assertEquals(m.followUps[0].taskId, out.followUps[0].taskId)
        assertTrue(out.followUps[0].done)
    }

    @Test fun speakerNamesStayUnlessListed() {
        val kept = Redactor.redact(meeting(), emptyList())
        assertEquals(listOf("Sabine", "Jonas Becker"), kept.speakers.map { it.name })

        val listed = Redactor.redact(meeting(), listOf("Becker"))
        assertEquals(listOf("Sabine", "Person B"), listed.speakers.map { it.name })
    }

    @Test fun derivedNamesDoNotHitOtherParticipants() {
        // Teilnehmerin "Anna Schulz" – abgeleitetes "Anna" aus "Anna Müller" darf sie nicht treffen
        val m = meeting().copy(
            speakers = listOf(Speaker(0, "Anna Schulz")),
            segments = listOf(Segment(0, 0f, 1f, "Anna fasst zusammen: Frau Müller kommt.")),
        )
        val out = Redactor.redact(m, emptyList())
        assertEquals("Anna fasst zusammen: Person A kommt.", out.segments[0].text)
        assertEquals("Anna Schulz", out.speakers[0].name)
    }

    @Test fun ambiguousSurnameIsBlackedOutNeutrally() {
        val out = Redactor.redactText("Frau Müller und Herr Müller, Eva und Hans", listOf("Eva Müller", "Hans Müller"))
        // "Müller" allein ist mehrdeutig → neutral, Vornamen eindeutig
        assertEquals("Frau [geschwärzt] und Herr [geschwärzt], Person A und Person B", out)
    }

    @Test fun mappingIsStableAcrossCalls() {
        val a = Redactor.redact(meeting(), listOf("Becker"))
        val b = Redactor.redact(meeting(), listOf("Becker"))
        assertEquals(a, b.copy(tasks = a.tasks))
        assertEquals(a.tasks.map { it.text }, b.tasks.map { it.text })
    }

    @Test fun previewListsFindingsInOrderOnce() {
        val p = Redactor.preview(meeting(), listOf("Becker"))
        val map = p.toMap()
        assertEquals("Person A", map["Müller"])
        assertEquals("[Telefon]", map["0171 1234567"])
        assertEquals("Person B", map["Jonas Becker"])
        assertEquals("[E-Mail]", map["anna@example.de"])
        assertEquals("[Adresse]", map["Hauptstraße 5"])
        // jede Fundstelle nur einmal (Groß-/Kleinschreibung egal)
        assertEquals(p.size, p.map { it.first.lowercase() }.distinct().size)
        // Titel zuerst
        assertEquals("Müller" to "Person A", p.first())
    }

    @Test fun previewEmptyWithoutFindings() {
        val m = Meeting(id = "x", title = "Teamrunde", createdAt = 0L, audioFile = "",
            segments = listOf(Segment(0, 0f, 1f, "Alles gut, bis 27.11.2026 um 10:30.")))
        assertTrue(Redactor.preview(m, emptyList()).isEmpty())
        assertEquals(m.copy(progressText = ""), Redactor.redact(m, emptyList()))
    }
}
