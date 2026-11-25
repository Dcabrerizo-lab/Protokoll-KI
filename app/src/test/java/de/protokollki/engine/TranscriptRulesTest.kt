package de.protokollki.engine

import de.protokollki.data.Segment
import de.protokollki.data.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TranscriptRulesTest {
    private fun segs(vararg s: Pair<Int, String>) = s.mapIndexed { i, (spk, t) -> Segment(spk, i * 5f, i * 5f + 4f, t) }
    private val label = { id: Int -> "Person ${id + 1}" }
    private val monday = LocalDate.of(2026, 11, 23)

    // ------------------------------------------------------------ Abstimmungen

    @Test
    fun detectsVoteWithCountsAndSubject() {
        val s = segs(
            0 to "Wir kommen zur Abstimmung über den Antrag: Anschaffung eines neuen Dienstwagens. Wer ist dafür? Acht. Wer ist dagegen? Zwei. Enthaltungen? Keine. Der Antrag ist angenommen.",
        )
        val v = TranscriptRules.detectVotes(s).single()
        assertTrue(v.accepted)
        assertEquals(8, v.yes); assertEquals(2, v.no); assertEquals(0, v.abstain)
        assertEquals(0, v.ref)
    }

    @Test
    fun voteSubjectFromStimmenAbAndCountsAcrossSegments() {
        val s = segs(
            0 to "Dann stimmen wir ab: Neue Öffnungszeiten ab Januar. Wer ist dafür?",
            1 to "Drei Stimmen.",
            0 to "Wer ist dagegen? Vier. Damit ist der Vorschlag abgelehnt.",
        )
        val v = TranscriptRules.detectVotes(s).single()
        assertEquals("Neue Öffnungszeiten ab Januar", v.subject)
        assertEquals(false, v.accepted)
        assertEquals(4, v.no)
        assertEquals(3, v.yes)
        assertEquals("Neue Öffnungszeiten ab Januar – Abstimmung: 3 Ja, 4 Nein, abgelehnt.", v.text())
    }

    @Test
    fun voteSubjectGetsAgendaItemAndUnanimous() {
        val s = segs(
            0 to "Nächster Punkt, die Satzungsänderung. Wer ist für die neue Fassung von Paragraf drei?",
            1 to "Alle.",
            0 to "Dann ist das einstimmig angenommen.",
        )
        val v = TranscriptRules.detectVotes(s).single()
        assertTrue(v.unanimous)
        assertEquals("Satzungsänderung: Neue Fassung von Paragraf drei – einstimmig angenommen.", v.text())
    }

    @Test
    fun angenommenOutsideVoteIsIgnored() {
        val s = segs(0 to "Ich hatte angenommen, dass die Lieferung schon da ist.",
            1 to "Das Paket wurde gestern angenommen.")
        assertTrue(TranscriptRules.detectVotes(s).isEmpty())
    }

    @Test
    fun mergeAddsResultToMatchingDecisionOrAppends() {
        val votes = listOf(
            TranscriptRules.Vote("Sommerfest am vierzehnten Juni mit Budget von zweitausend Euro", true, false, 5, 1, 1, 4),
            TranscriptRules.Vote("Mitgliedsbeitrag: Erhöhung auf vierzig Euro", false, false, 2, 4, 1, 8),
        )
        val (d, r) = TranscriptRules.mergeVotes(listOf("Das Sommerfest findet am 14. Juni im Stadtpark statt, Budget 2000 Euro."), listOf(4), votes)
        assertEquals("Das Sommerfest findet am 14. Juni im Stadtpark statt, Budget 2000 Euro – Abstimmung: 5 Ja, 1 Nein, 1 Enthaltung, angenommen.", d[0])
        assertEquals("Mitgliedsbeitrag: Erhöhung auf vierzig Euro – Abstimmung: 2 Ja, 4 Nein, 1 Enthaltung, abgelehnt.", d[1])
        assertEquals(listOf(4, 8), r)
    }

    @Test
    fun mergeCorrectsWrongResultAndKeepsCompleteOnes() {
        val v = listOf(TranscriptRules.Vote("Erhöhung des Beitrags", false, false, 2, 4, null, 3))
        val (d, _) = TranscriptRules.mergeVotes(listOf("Erhöhung des Beitrags angenommen."), listOf(3), v)
        assertEquals("Erhöhung des Beitrags – Abstimmung: 2 Ja, 4 Nein, abgelehnt.", d.single())
        val ok = "Erhöhung des Beitrags – Abstimmung: 2 Ja, 4 Nein, abgelehnt."
        assertEquals(ok, TranscriptRules.mergeVotes(listOf(ok), listOf(3), v).first.single())
    }

    // ------------------------------------------------------------ Wer ist wer

    @Test
    fun speakerNamesFromDirectAddress() {
        val s = segs(
            0 to "Frau Brandt, können Sie bitte die Liste aktualisieren?",
            1 to "Ja, mache ich.",
            2 to "Ich übernehme die Bestellung bis Freitag.",
            0 to "Danke, Herr Okafor. Dann zum nächsten Punkt.",
        )
        assertEquals(mapOf(1 to "Frau Brandt", 2 to "Herr Okafor"), TranscriptRules.speakerNames(s))
    }

    @Test
    fun thirdPersonMentionsDoNotNameSpeakers() {
        val s = segs(
            0 to "Wir besprechen heute Herrn Krüger aus der Lindenstraße.",
            1 to "Frau Schmidt beantragt die Höherstufung.",
            0 to "Danke. Frau Lange, holen Sie bitte Angebote ein.",
            2 to "Ja, das übernehme ich.",
        )
        // "Danke." mit Punkt gehört nicht zur Anrede: Frau Lange ist, wer danach spricht
        assertEquals(mapOf(2 to "Frau Lange"), TranscriptRules.speakerNames(s))
    }

    @Test
    fun conflictingEvidenceGivesNoName() {
        val s = segs(
            0 to "Frau Kaiser, übernehmen Sie das?", 1 to "Ja.",
            0 to "Frau Becker, können Sie das prüfen?", 1 to "Ja.",
        )
        assertTrue(TranscriptRules.speakerNames(s).isEmpty())
    }

    @Test
    fun ownerFromSelfCommitmentRequestAndSentence() {
        val s = segs(
            0 to "Die Nachtdienste übernehme ich selbst. Können Sie den Dienstplan bis Mittwoch aushängen?",
            1 to "Ja, mache ich.",
            2 to "Ich kümmere mich um die Genehmigung beim Ordnungsamt.",
        )
        val names = mapOf(1 to "Frau Kaiser")
        assertEquals("Frau Kaiser", TranscriptRules.ownerFromRef(0, s, names, label, "Dienstplan aushängen"))
        assertEquals("Person 1", TranscriptRules.ownerFromRef(0, s, names, label, "Nachtdienste übernehmen"))
        assertEquals("Person 3", TranscriptRules.ownerFromRef(2, s, names, label, "Genehmigung beim Ordnungsamt einholen"))
        assertNull(TranscriptRules.ownerFromRef(5, s, names, label, "x"))
    }

    // ------------------------------------------------------------ Fristen, erledigt

    @Test
    fun deadlineOnlyWhenUnambiguous() {
        assertEquals("Freitag", TranscriptRules.deadlineFromRef("Können Sie bis Freitag drei Angebote einholen?", monday))
        assertEquals("Ende Januar", TranscriptRules.deadlineFromRef("Klären Sie das bis Ende Januar mit dem Anbieter.", monday))
        assertEquals("zur nächsten Sitzung", TranscriptRules.deadlineFromRef("Er erstellt bis zur nächsten Sitzung einen Entwurf.", monday))
        assertNull(TranscriptRules.deadlineFromRef("Bis Freitag die Angebote und bis Montag die Liste.", monday))
        assertNull(TranscriptRules.deadlineFromRef("Das dauert bis auf Weiteres.", monday))
    }

    @Test
    fun completedAndStillOpen() {
        val open = mapOf("A1" to "Drei Angebote für die Möbel einholen", "A2" to "Telefonanlage mit dem Anbieter klären")
        val s = segs(
            1 to "Die drei Angebote für die Möbel liegen vor, das ist erledigt.",
            0 to "Herr Wagner, wie sieht es mit der Telefonanlage beim Anbieter aus?",
            2 to "Da warte ich noch auf den Rückruf. Das ist noch offen.",
        )
        assertEquals(mapOf("A1" to 0), TranscriptRules.completedFromTranscript(open, s))
        assertTrue(TranscriptRules.stillOpen(open.getValue("A2"), s))
        assertEquals(false, TranscriptRules.stillOpen(open.getValue("A1"), s))
    }

    // ------------------------------------------------------------ Gliederung, sensible Daten

    @Test
    fun alignsTopicsToAgenda() {
        val agenda = listOf("Begrüßung und Formalien", "Sommerfest", "Mitgliedsbeitrag", "Verschiedenes")
        val topics = listOf(
            Topic("Eröffnung und Formalien", listOf("Beschlussfähig."), listOf(0)),
            Topic("Planung Sommerfest", listOf("Budget 2000 Euro."), listOf(2)),
            Topic("Erhöhung des Mitgliedsbeitrags", listOf("Abgelehnt."), listOf(8)),
            Topic("Vereinszeitung", listOf("Artikel bis Januar."), listOf(9)),
            Topic("Sommerfest Genehmigung", listOf("Ordnungsamt."), listOf(5)),
        )
        val out = TranscriptRules.alignAgenda(topics, agenda)
        assertEquals(listOf("TOP 1: Begrüßung und Formalien", "TOP 2: Sommerfest", "TOP 3: Mitgliedsbeitrag", "TOP 4: Verschiedenes"),
            out.map { it.title })
        assertEquals(listOf("Budget 2000 Euro.", "Ordnungsamt."), out[1].points)
        assertEquals(listOf(2, 5), out[1].refs)
    }

    @Test
    fun agendaWithoutMiscKeepsUnmatchedTopics() {
        val out = TranscriptRules.alignAgenda(listOf(Topic("Urlaub", listOf("x"))), listOf("Umzug", "Touren"))
        assertEquals("Urlaub", out.single().title)
    }

    @Test
    fun findsAddressesAndPhoneNumbers() {
        val s = segs(
            0 to "Herr Krüger wohnt in der Lindenstraße zwölf, seine Tochter ist unter der Nummer null eins sieben null, eins zwei drei vier fünf sechs sieben erreichbar.",
            1 to "Das neue Büro ist in der Hauptstraße. Das war ein Hering.",
        )
        val found = TranscriptRules.addressesAndPhones(s)
        assertTrue(found.toString(), found.contains("Lindenstraße zwölf"))
        assertTrue(found.toString(), found.any { it.startsWith("null eins sieben null") })
        assertEquals(2, found.size)
    }

    @Test
    fun dueNormalizationAndSupport() {
        assertEquals("20. Dezember", TranscriptRules.normalizeDue("zwanzigsten Dezember"))
        assertEquals("bis zum 10. Januar", TranscriptRules.normalizeDue("bis zum zehnten Januar"))
        assertEquals("Ende dieser Woche", TranscriptRules.normalizeDue("möglichst in dieser Woche"))
        assertEquals("zum ersten Mal", TranscriptRules.normalizeDue("zum ersten Mal"))
        val ctx = "Frau Schmidt beantragt bis zum zwanzigsten Dezember die Höherstufung."
        assertTrue(TranscriptRules.dueSupported("20. Dezember", ctx))
        assertTrue(TranscriptRules.dueSupported("bis zum zwanzigsten Dezember", ctx))
        assertEquals(false, TranscriptRules.dueSupported("nach dem Hausbesuch", ctx))
        assertEquals(false, TranscriptRules.dueSupported("26.11.2026", ctx))
        assertEquals("zum 20. Dezember", TranscriptRules.deadlineFromRef(ctx, monday))
    }

    @Test
    fun commitmentsFindPromisesAndRequestsOnly() {
        val s = segs(
            0 to "Frau Lange, können Sie bis Montag die Raumbuchung erledigen?",
            1 to "Ja, mache ich. Mache ich, danke euch.",
            2 to "Ich finde das Budget zu hoch. Ich schreibe bis Freitag den Bericht für den Vorstand.",
            0 to "Herr Brandt organisiert die Getränke. Die Lieferung kommt morgen, bitte an der Pforte annehmen.",
            1 to "Ich habe die Liste gestern verschickt.",
        )
        assertEquals(listOf(0, 2, 3, 3), TranscriptRules.commitments(s).map { it.ref })
        assertEquals("Ich schreibe bis Freitag den Bericht für den Vorstand.", TranscriptRules.commitments(s)[1].sentence)
    }

    @Test
    fun phrasesTasksFromSentences() {
        val cases = mapOf(
            "Können Sie den Wochenplan bis Mittwoch fertig machen und aushängen?" to "Den Wochenplan bis Mittwoch fertig machen und aushängen",
            "Prima, dann laden Sie bitte alle bis Ende der Woche ein." to "Alle bis Ende der Woche einladen",
            "Herr Brandt, kümmern Sie sich bitte um den Drucker und klären Sie das mit dem Händler." to "Sich um den Drucker kümmern und das mit dem Händler klären",
            "Gut, ich messe um zehn Uhr noch einmal den Blutdruck und gebe der Ärztin den Wert." to "Um zehn Uhr noch einmal den Blutdruck messen und der Ärztin den Wert geben",
            "Ich melde beide bis zum Monatsende beim Kurs an." to "Beide bis zum Monatsende beim Kurs anmelden",
            "Ich kümmere mich um die Genehmigung beim Bauamt, bis Ende Mai." to "Sich um die Genehmigung beim Bauamt bis Ende Mai kümmern",
            "Dann vereinbaren wir: Frau Wolf fordert beim Hausarzt ein Rezept an und bestellt das Pflegebett." to "Beim Hausarzt ein Rezept anfordern und das Pflegebett bestellen",
            "Kontrolle in zwei Wochen, Herr Neumann übernimmt das Assessment." to "Das Assessment übernehmen (Kontrolle in zwei Wochen)",
            "Die Lieferung kommt morgen früh, bitte annehmen." to "Die Lieferung kommt morgen früh: annehmen",
            "Herr Lange hat heute wenig gegessen, bitte auf die Mahlzeiten achten." to "Auf die Mahlzeiten achten (Herr Lange hat heute wenig gegessen)",
        )
        cases.forEach { (sentence, expected) -> assertEquals(sentence, expected, TranscriptRules.phraseTask(sentence)) }
    }

    @Test
    fun softDeadlinesAndDecisionsAndHealthData() {
        assertEquals("in zwei Wochen", TranscriptRules.softDeadline("Evaluation in zwei Wochen, Herr Neumann übernimmt."))
        assertEquals("um 14 Uhr", TranscriptRules.softDeadline("Ich messe um vierzehn Uhr noch einmal Fieber."))
        assertEquals("Ende dieser Woche", TranscriptRules.softDeadline("Er organisiert das, möglichst diese Woche."))
        assertNull(TranscriptRules.softDeadline("Guten Morgen, wir beginnen."))
        assertNull(TranscriptRules.softDeadline("Heute oder morgen."))
        val s = segs(0 to "Dann beschließen wir: Die Schulung findet online statt. Danke, dann ist das beschlossen.",
            1 to "Diagnosen Diabetes mellitus Typ zwei, Herzinsuffizienz und beginnende Demenz. Pflegegrad drei.")
        assertEquals(listOf("Die Schulung findet online statt." to 0), TranscriptRules.explicitDecisions(s))
        assertEquals(listOf("Diabetes mellitus Typ zwei", "Herzinsuffizienz", "beginnende Demenz", "Pflegegrad drei"),
            TranscriptRules.healthData(s))
        // Zahlen müssen stimmen
        assertEquals(false, TranscriptRules.dueSupported("2. Januar", "Ich schreibe bis zum zehnten Januar einen Artikel."))
        assertTrue(TranscriptRules.dueSupported("14:00 Uhr", "Ich messe um vierzehn Uhr Fieber."))
    }

    @Test
    fun clientsByCareContextExceptStaff() {
        val s = segs(
            0 to "Frau Okafor in Zimmer fünf hat starke Schmerzen. Herr Brandt übernimmt heute die Tour.",
            1 to "Bei Herrn Vogel war der Blutzucker zu hoch, Frau Lange sagt dem Hausarzt Bescheid.",
            0 to "Herr Kühn wohnt jetzt in der Linden Straße acht.",
        )
        assertEquals(listOf("Okafor", "Vogel", "Kühn"), TranscriptRules.clientsByContext(s, setOf("lange")))
        assertTrue(TranscriptRules.addressesAndPhones(s).contains("Linden Straße acht"))
        assertEquals("Ende Januar", TranscriptRules.normalizeDue("Januarende"))
        assertEquals(false, TranscriptRules.dueSupported("nach dem Sitzungstermin", "Ich schließe die Sitzung um acht."))
        assertTrue(TranscriptRules.dueSupported("bis zur nächsten Sitzung", "Er erstellt bis zur nächsten Sitzung einen Entwurf."))
    }

    @Test
    fun weDecideButNotProcedure() {
        val s = segs(0 to "Danke. Dann bestellen wir bei der Firma Lindner.", 1 to "Dann schauen wir zuerst auf die Liste.",
            0 to "Gut, dann klären wir das nächste Woche. Dann treffen wir uns in vier Wochen wieder.")
        assertEquals(listOf("Wir bestellen bei der Firma Lindner." to 0), TranscriptRules.explicitDecisions(s))
    }

    @Test
    fun staffOnlyFromDirectAddressOrAssignment() {
        val s = segs(
            0 to "Fallbesprechung Frau Hoffmann, vierundachtzig Jahre, Pflegegrad drei.",
            1 to "Gut, Frau Kaiser, können Sie das übernehmen? Bei Herrn Krüger, der gestürzt ist, waren wir gestern.",
            0 to "Herr Neumann übernimmt das Assessment.",
        )
        assertEquals(setOf("Frau Kaiser", "Herr Neumann"), TranscriptRules.staffNames(s))
    }

    @Test
    fun explicitAssignmentBeatsModelOwner() {
        val s = segs(1 to "Ich dokumentiere die Vitalzeichen täglich.", 0 to "Evaluation der Pflegeplanung in zwei Wochen, Herr Neumann übernimmt das Assessment.")
        assertEquals("Herr Neumann", TranscriptRules.explicitOwner(1, s, emptyMap(), "Evaluation der Pflegeplanung in zwei Wochen"))
        // Selbstverpflichtung nur mit bekanntem Namen
        assertNull(TranscriptRules.explicitOwner(0, s, emptyMap(), "Vitalzeichen täglich dokumentieren"))
        assertEquals("Frau Wolf", TranscriptRules.explicitOwner(0, s, mapOf(1 to "Frau Wolf"), "Vitalzeichen täglich dokumentieren"))
    }

    @Test
    fun reportsOfDoneWorkAreNoTasks() {
        assertTrue(TranscriptRules.reportedDone("Erste-Hilfe-Kurs organisieren", "Für die Fortbildung habe ich den Erste-Hilfe-Kurs am zwölften Februar organisiert."))
        assertEquals(false, TranscriptRules.reportedDone("Plan erstellen", "Den Plan habe ich noch nicht erstellt."))
        assertEquals(false, TranscriptRules.reportedDone("Plan erstellen", "Ich erstelle den Plan bis Freitag."))
        assertEquals("Frau Albers", TranscriptRules.assignee("Frau Albers übernimmt die Elternbriefe."))
        assertEquals(setOf("wäschewagen"), TranscriptRules.sharedNouns("Wäschewagen organisieren", "Zwei Angebote für Wäschewagen einholen"))
    }

    @Test
    fun summaryWithAlsoNamesPreviousVolunteer() {
        val s = segs(2 to "Wer übernimmt die Praxisanleitung?", 3 to "Das kann ich machen, ich habe die Weiterbildung.",
            0 to "Sehr gut. Herr Wagner ist also Praxisanleiter für Herrn Okafor.")
        assertEquals(mapOf(3 to "Herr Wagner"), TranscriptRules.speakerNames(s))
    }

    @Test
    fun procedureAndFinishedStateAreNoTasks() {
        assertTrue(TranscriptRules.procedural("Eröffne den Elternabend der Klasse vier b."))
        assertTrue(TranscriptRules.procedural("Dann schließe ich die Besprechung"))
        assertEquals(false, TranscriptRules.procedural("Raum für die nächste Sitzung buchen"))
        assertTrue(TranscriptRules.reportedDone("Mietvertrag erledigen", "Der Mietvertrag für das neue Büro ist unterschrieben. Einzug ist am ersten Februar."))
    }
}
