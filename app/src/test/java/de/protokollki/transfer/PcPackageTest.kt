package de.protokollki.transfer

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.ProtocolTemplate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PcPackageTest {
    private val base = Meeting(id = "m1", title = "Dienstbesprechung", createdAt = 1_795_392_000_000,
        audioFile = "/data/user/0/de.protokollki/files/meetings/m1/audio.wav", template = ProtocolTemplate.FALLBESPRECHUNG,
        agenda = listOf("Umzug"))

    private fun roundTrip(m: Meeting, audio: ByteArray?): Pair<PcPackage.Contents, ByteArray?> {
        val out = ByteArrayOutputStream()
        val info = PcPackage.infoJson(m, "de", listOf("SGB XI"), "Pflegedienst Nord",
            listOf(PcPackage.OpenTaskRef("t9", "m0", "Angebote einholen", "Frau Becker")))
        PcPackage.write(out, info, audio?.let { ByteArrayInputStream(it) })
        var got: ByteArray? = null
        val c = PcPackage.read(ByteArrayInputStream(out.toByteArray())) { got = it.readBytes() }
        return c to got
    }

    @Test fun `paket enthaelt besprechung einstellungen und aufnahme`() {
        val audio = ByteArray(5000) { (it % 251).toByte() }
        val (c, got) = roundTrip(base, audio)
        assertEquals("m1", c.meeting.id)
        assertEquals(ProtocolTemplate.FALLBESPRECHUNG, c.meeting.template)
        assertEquals(listOf("Umzug"), c.meeting.agenda)
        assertEquals("audio.wav", c.meeting.audioFile)      // kein Handy-Pfad in der Datei
        assertEquals(listOf("SGB XI"), c.vocabulary)
        assertEquals("android", c.createdBy)
        assertArrayEquals(audio, got)
    }

    @Test fun `ohne aufnahme lesbar`() {
        val (c, got) = roundTrip(base, null)
        assertEquals("Dienstbesprechung", c.meeting.title)
        assertEquals(null, got)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fremde zip wird abgelehnt`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("x.txt")); z.write(1); z.closeEntry()
        }
        PcPackage.read(ByteArrayInputStream(out.toByteArray())) {}
    }

    @Test fun `ergebnis vom pc wird uebernommen, haekchen bleiben`() {
        val local = base.copy(status = MeetingStatus.RECORDED, tasks = listOf(TaskItem("Angebote einholen", done = true)),
            sensitiveTerms = listOf("Herr Krüger"))
        val pc = base.copy(audioFile = "audio.wav", status = MeetingStatus.DONE,
            segments = listOf(Segment(0, 0f, 2f, "Hallo")), speakers = listOf(Speaker(0, "Anna")),
            summary = Summary(overview = "Kurz."), tasks = listOf(TaskItem("Angebote einholen"), TaskItem("Telefon klären")),
            sensitiveTerms = listOf("herr krüger", "Frau Lehmann"))
        val m = PcPackage.merge(local, pc)
        assertEquals(MeetingStatus.DONE, m.status)
        assertEquals(local.audioFile, m.audioFile)            // lokale Aufnahme bleibt
        assertEquals("Kurz.", m.summary.overview)
        assertTrue(m.tasks.first { it.text == "Angebote einholen" }.done)
        assertEquals(listOf("Herr Krüger", "Frau Lehmann"), m.sensitiveTerms)
    }

    @Test fun `am pc aufgenommene besprechung wird neu angelegt`() {
        val pcOnly = base.copy(status = MeetingStatus.RECORDED)
        assertEquals(MeetingStatus.RECORDED, PcPackage.asNew(pcOnly, "/x/audio.wav", hasAudio = true).status)
        val done = pcOnly.copy(status = MeetingStatus.DONE, segments = listOf(Segment(0, 0f, 1f, "a")), summary = Summary("ok"))
        assertEquals(MeetingStatus.DONE, PcPackage.asNew(done, "/x/audio.wav", hasAudio = false).status)
    }
}
