package de.protokollki.importdoc

import de.protokollki.data.MeetingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextTest {

    private val sample = """
        Protokoll der Teamsitzung
        vom 12.03.2025

        TOP 1: Umzug
        Der Mietvertrag ist unterschrieben. Frau Becker holt drei Ange-
        bote für Möbel ein.

        Aufgaben:
        - Angebote einholen (Frau Becker, bis 20.03.)
        - Telefonanlage klären (Herr Wagner)
    """.trimIndent()

    @Test fun `absaetze silbentrennung und aufzaehlungen`() {
        val p = DocumentText.paragraphs(sample)
        assertEquals("Protokoll der Teamsitzung", p[0])
        assertTrue(p.any { it == "Der Mietvertrag ist unterschrieben. Frau Becker holt drei Angebote für Möbel ein." })
        assertTrue(p.contains("- Angebote einholen (Frau Becker, bis 20.03.)"))
        assertTrue(p.contains("- Telefonanlage klären (Herr Wagner)"))
        assertTrue(p.contains("TOP 1: Umzug"))
    }

    @Test fun `datum aus dem kopf`() {
        assertEquals(LocalDate.of(2025, 3, 12), DocumentText.findDate(sample))
        assertEquals(LocalDate.of(2024, 11, 5), DocumentText.findDate("Sitzung am 5. November 2024 in Köln"))
        assertEquals(LocalDate.of(2026, 1, 7), DocumentText.findDate("Datum: 7.1.26"))
        assertNull(DocumentText.findDate("Kein Datum hier, nur 4000 Euro."))
        assertNull(DocumentText.findDate("Ungültig: 31.02.2025"))
    }

    @Test fun `besprechung aus dokument`() {
        val zone = ZoneId.of("Europe/Berlin")
        val m = DocumentText.toMeeting("id1", "teamsitzung_maerz.pdf", sample, now = 42L, zone = zone)
        assertEquals("Protokoll der Teamsitzung", m.title)
        assertEquals(LocalDate.of(2025, 3, 12), java.time.Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate())
        assertEquals("teamsitzung_maerz.pdf", m.importedFrom)
        assertTrue(m.audioDeleted)
        assertEquals(MeetingStatus.RECORDED, m.status)
        assertEquals(setOf(0), m.segments.map { it.speakerId }.toSet())
        assertEquals("Protokoll", m.speakerName(0))
    }

    @Test fun `ohne datum gilt importzeitpunkt und dateiname als titel`() {
        val m = DocumentText.toMeeting("id2", "Notizen_Vorstand.txt", "ok.\n\nnoch ein satz.", now = 42L)
        assertEquals(42L, m.createdAt)
        assertEquals("Notizen Vorstand", m.title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `leerer text wirft`() { DocumentText.toMeeting("x", "a.txt", "  \n \n", 0L) }

    @Test fun `lange absaetze werden an satzgrenzen geteilt`() {
        val long = (1..60).joinToString(" ") { "Das ist Satz Nummer $it mit etwas Inhalt." }
        val p = DocumentText.paragraphs(long)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.length <= 760 })
        assertEquals(long, p.joinToString(" "))
    }

    @Test fun `word docx wird gelesen`() {
        val xml = """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Protokoll &amp; Beschlüsse</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Frau Becker </w:t></w:r><w:r><w:t>holt Angebote ein.</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Zelle</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("x".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val text = DocumentText.fromDocx(ByteArrayInputStream(bytes))
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(listOf("Protokoll & Beschlüsse", "Frau Becker holt Angebote ein.", "Zelle"), lines)
    }

    @Test fun `windows 1252 text wird erkannt`() {
        val bytes = "Möbel für Bürger".toByteArray(charset("windows-1252"))
        assertEquals("Möbel für Bürger", DocumentText.decode(bytes))
        assertEquals("Möbel", DocumentText.decode("﻿Möbel".toByteArray()))
    }
}
