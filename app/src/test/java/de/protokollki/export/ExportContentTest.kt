package de.protokollki.export

import de.protokollki.data.Meeting
import de.protokollki.data.ProtocolMode
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import org.junit.Assert.assertEquals
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
}
