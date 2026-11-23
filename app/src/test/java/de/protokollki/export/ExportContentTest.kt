package de.protokollki.export

import de.protokollki.data.FollowUp
import de.protokollki.data.Meeting
import de.protokollki.data.ProtocolMode
import de.protokollki.data.ProtocolTemplate
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportContentTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun meeting(mode: ProtocolMode) = Meeting(
        id = "x",
        title = "Team <Jour> & Fixe",
        createdAt = 0L,
        durationSec = 3725f,
        audioFile = "/dev/null",
        mode = mode,
        segments = listOf(
            Segment(1, 0f, 3f, "Guten Morgen."),
            Segment(0, 65f, 70f, "Hallo zusammen."),
            Segment(1, 3700f, 3710f, "Danke."),
        ),
        speakers = listOf(Speaker(0, "Anna"), Speaker(1, "Ben")),
        summary = Summary(
            overview = "Kurz.",
            topics = listOf(Topic("Budget", listOf("Freigabe", "Q3"))),
            decisions = listOf("Budget steht"),
            openPoints = emptyList(),
        ),
        tasks = listOf(TaskItem("Bericht | schreiben", "Anna", "Freitag", done = true)),
    )

    @Test
    fun formatting() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&apos;", xmlEscape("<a href=\"x\">&'"))
        assertEquals("ab", xmlEscape("a\u0001b"))
        assertEquals("Team _Jour_ & Fixe", sanitizeFileName("Team <Jour> & Fixe"))
        assertEquals("Protokoll", sanitizeFileName("  ///  "))
        assertEquals("01:05", formatTimestamp(65.9f))
        assertEquals("1:01:40", formatTimestamp(3700f))
        assertEquals("45 Sek.", formatDuration(45f))
        assertEquals("12 Min.", formatDuration(725f))
        assertEquals("1 Std. 2 Min.", formatDuration(3725f))
        assertEquals("01.01.1970, 00:00 Uhr", formatDate(0L, utc))
    }

    @Test
    fun participantsInOrderOfFirstAppearance() {
        assertEquals(listOf("Ben", "Anna"), participants(meeting(ProtocolMode.WORT)))
    }

    @Test
    fun plainTextAndMarkdown() {
        val txt = renderPlainText(buildBlocks(meeting(ProtocolMode.WORT), utc))
        assertTrue(txt.startsWith("WORTPROTOKOLL"))
        assertTrue(txt.contains("Ben [00:00]: Guten Morgen."))
        assertTrue(txt.contains("Teilnehmende: Ben, Anna"))
        assertTrue(txt.contains(FOOTER_NOTE))

        val md = renderMarkdown(buildBlocks(meeting(ProtocolMode.ERGEBNIS), utc))
        assertTrue(md.contains("## Beschlüsse"))
        assertTrue(md.contains("1. Budget steht"))
        assertTrue(md.contains("Bericht \\| schreiben"))
        assertTrue(md.contains("## Offene Punkte\n\nKeine"))
    }

    @Test
    fun docxIsWellFormedZip() {
        val out = ByteArrayOutputStream()
        DocxWriter.write(buildBlocks(meeting(ProtocolMode.ERGEBNIS), utc), out)
        val names = mutableListOf<String>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                val bytes = zip.readBytes()
                // jede XML-Datei muss wohlgeformt sein
                factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            }
        }
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels",
                "word/document.xml", "word/styles.xml"),
            names,
        )
        val doc = DocxWriter.document(buildBlocks(meeting(ProtocolMode.ERGEBNIS), utc))
        assertTrue(doc.contains("Team &lt;Jour&gt; &amp; Fixe"))
        assertTrue(doc.contains("<w:tbl>"))
    }

    private fun withAgendaAndFollowUps(mode: ProtocolMode) = meeting(mode).copy(
        agenda = listOf("Begrüßung", " ", "Budget 2027"),
        followUps = listOf(
            FollowUp("t1", "m0", "Angebote einholen", "Becker", done = true),
            FollowUp("t2", "m0", "Raum klären", "", done = false),
            FollowUp("t3", "m0", " ", "X", done = false),
        ),
    )

    @Test
    fun agendaComesFirstAndFollowUpsAfterTasks() {
        for (mode in ProtocolMode.values()) {
            val blocks = buildBlocks(withAgendaAndFollowUps(mode), utc)
            val headings = blocks.filterIsInstance<Block.Heading>().map { it.text }
            assertEquals(AGENDA_HEADING, headings.first())
            assertEquals(headings.indexOf("Aufgaben") + 1, headings.indexOf(FOLLOWUP_HEADING))
            // leere Einträge fallen weg, Nummerierung lückenlos
            val agenda = blocks.dropWhile { it != Block.Heading(AGENDA_HEADING) }.drop(1)
                .takeWhile { it is Block.Numbered }.map { it as Block.Numbered }
            assertEquals(listOf(1 to "Begrüßung", 2 to "Budget 2027"), agenda.map { it.number to it.text })
            val table = blocks.dropWhile { it != Block.Heading(FOLLOWUP_HEADING) }[1] as Block.Table
            assertEquals(FOLLOWUP_HEADER, table.header)
            assertEquals(
                listOf(listOf("Angebote einholen", "Becker", "erledigt"), listOf("Raum klären", "offen", "offen")),
                table.rows,
            )
        }
    }

    @Test
    fun agendaAndFollowUpsInAllFormats() {
        val m = withAgendaAndFollowUps(ProtocolMode.ERGEBNIS)
        val txt = renderPlainText(buildBlocks(m, utc))
        assertTrue(txt.contains("TAGESORDNUNG\n------------\n1. Begrüßung\n2. Budget 2027"))
        assertTrue(txt.contains("• Angebote einholen · Verantwortlich: Becker · Stand: erledigt"))
        assertTrue(txt.contains("• Raum klären · Verantwortlich: offen · Stand: offen"))
        // Aufgaben-Tabelle behält ihr eigenes Format
        assertTrue(txt.contains("1. Bericht | schreiben\n   Verantwortlich: Anna · Frist: Freitag · erledigt ✓"))

        val md = renderMarkdown(buildBlocks(m, utc))
        assertTrue(md.contains("## Tagesordnung\n\n1. Begrüßung\n2. Budget 2027\n"))
        assertTrue(md.contains("## $FOLLOWUP_HEADING\n\n| Aufgabe | Verantwortlich | Stand |"))
        assertTrue(md.contains("| Angebote einholen | Becker | erledigt |"))

        val doc = DocxWriter.document(buildBlocks(m, utc))
        assertTrue(doc.contains("Tagesordnung"))
        assertTrue(doc.contains(FOLLOWUP_HEADING))
        assertEquals(2, Regex("<w:tbl>").findAll(doc).count())
    }

    @Test
    fun noAgendaOrFollowUpSectionsWhenEmpty() {
        val txt = renderPlainText(buildBlocks(meeting(ProtocolMode.ERGEBNIS), utc))
        assertFalse(txt.contains("TAGESORDNUNG"))
        assertFalse(txt.contains(FOLLOWUP_HEADING.uppercase()))
    }

    // ------------------------------------------------------------------ Vorlagen

    private fun formal(template: ProtocolTemplate = ProtocolTemplate.VEREINSSITZUNG) =
        meeting(ProtocolMode.ERGEBNIS).copy(
            template = template,
            location = "Vereinsheim",
            chair = "Clara Vogt",
            minuteTaker = "",
        )

    @Test
    fun generalTemplateKeepsClassicHeader() {
        val m = meeting(ProtocolMode.ERGEBNIS).copy(location = "Raum 1", chair = "X", minuteTaker = "Y")
        val blocks = buildBlocks(m, utc, organization = "Verein e. V.")
        assertEquals(Block.Title("Ergebnisprotokoll", "Team <Jour> & Fixe"), blocks.first())
        val meta = blocks.filterIsInstance<Block.Meta>().map { it.label }
        assertEquals(listOf("Datum", "Dauer", "Teilnehmende"), meta)
        assertTrue(blocks.none { it is Block.Signatures })
        assertFalse(renderPlainText(blocks).contains(SIGNATURE_LINE))
    }

    @Test
    fun formalHeaderListsOnlyFilledFields() {
        val blocks = buildBlocks(formal(), utc, organization = "Sportverein Musterstadt e. V.")
        assertEquals(Block.Title("Sitzungsprotokoll", "Team <Jour> & Fixe"), blocks.first())
        val meta = blocks.filterIsInstance<Block.Meta>().map { it.label to it.value }
        assertEquals(
            listOf(
                "Einrichtung" to "Sportverein Musterstadt e. V.",
                "Datum" to "01.01.1970",
                "Uhrzeit" to "00:00 – 01:02 Uhr",
                "Ort" to "Vereinsheim",
                "Sitzungsleitung" to "Clara Vogt",
                "Teilnehmende" to "Ben, Anna",
            ),
            meta,
        )
        // ohne Einrichtung entfällt die Zeile
        val noOrg = buildBlocks(formal(), utc).filterIsInstance<Block.Meta>().map { it.label }
        assertFalse(noOrg.contains("Einrichtung"))
        assertEquals("Leitung", chairRole(ProtocolTemplate.DIENSTBESPRECHUNG))
        assertEquals("Sitzungsleitung", chairRole(ProtocolTemplate.VEREINSSITZUNG))
    }

    @Test
    fun signatureBlockAtTheEnd() {
        val blocks = buildBlocks(formal().copy(minuteTaker = "Dana Roth"), utc)
        // Unterschriften direkt vor dem Hinweis am Ende
        assertEquals(Block.Note(FOOTER_NOTE), blocks.last())
        val sig = blocks[blocks.size - 2] as Block.Signatures
        assertEquals(
            listOf(SignatureLine("Sitzungsleitung", "Clara Vogt"), SignatureLine("Protokollführung", "Dana Roth")),
            sig.lines,
        )

        val txt = renderPlainText(blocks)
        assertTrue(txt.startsWith("SITZUNGSPROTOKOLL"))
        assertTrue(txt.contains("Ort: Vereinsheim\nSitzungsleitung: Clara Vogt\nProtokollführung: Dana Roth"))
        assertTrue(txt.contains("$SIGNATURE_LINE\nOrt, Datum\nSitzungsleitung (Clara Vogt)\n"))
        assertTrue(txt.contains("$SIGNATURE_LINE\nOrt, Datum\nProtokollführung (Dana Roth)\n"))
        assertTrue(txt.indexOf(SIGNATURE_LINE) < txt.indexOf(FOOTER_NOTE))

        val md = renderMarkdown(blocks)
        assertTrue(md.startsWith("# Sitzungsprotokoll: "))
        assertTrue(md.contains("**Ort:** Vereinsheim  \n"))
        // Unterstriche maskiert, damit keine Trennlinie entsteht
        assertTrue(md.contains("\\_".repeat(SIGNATURE_LINE.length) + "  \nOrt, Datum  \n**Sitzungsleitung** (Clara Vogt)"))
        assertTrue(md.contains("**Protokollführung** (Dana Roth)"))
    }

    @Test
    fun signatureNamesOnlyWhereKnown() {
        val lines = signatureLines(formal(ProtocolTemplate.UEBERGABE).copy(chair = "", minuteTaker = "Max"))
        assertEquals(listOf("Übergebende Fachkraft", "Übernehmende Fachkraft"), lines.map { it.role })
        assertTrue(lines.all { it.name.isEmpty() })
        assertEquals("Übergebende Fachkraft", lines.first().label)
    }

    @Test
    fun formalDocxHasBorderlessSignatureTable() {
        val blocks = buildBlocks(formal(ProtocolTemplate.DIENSTBESPRECHUNG), utc, organization = "Jugendhilfe & Co")
        val out = ByteArrayOutputStream()
        DocxWriter.write(blocks, out)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                zip.nextEntry ?: break
                factory.newDocumentBuilder().parse(ByteArrayInputStream(zip.readBytes()))
            }
        }
        val doc = DocxWriter.document(blocks)
        assertTrue(doc.contains("Protokoll der Dienstbesprechung"))
        assertTrue(doc.contains("Jugendhilfe &amp; Co"))
        assertTrue(doc.contains("w:val=\"nil\""))
        assertTrue(doc.contains("Ort, Datum"))
        assertTrue(doc.contains("Leitung (Clara Vogt)"))
        assertTrue(doc.contains(">Protokollführung<"))
        // Aufgaben-Tabelle + Unterschriften-Tabelle
        assertEquals(2, Regex("<w:tbl>").findAll(doc).count())
    }

    @Test
    fun timeRangeWithoutDuration() {
        assertEquals("00:00 Uhr", formatTimeRange(0L, 0f, utc))
        assertEquals("00:00 – 00:45 Uhr", formatTimeRange(0L, 2700f, utc))
    }
}
