package de.protokollki.tts

import de.protokollki.data.Meeting
import de.protokollki.data.ProtocolMode
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SpeechTextTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    /** Montag, 23.11.2026, 10:30 Uhr in Berlin */
    private val created = ZonedDateTime.of(2026, 11, 23, 10, 30, 0, 0, berlin).toInstant().toEpochMilli()

    private fun meeting(mode: ProtocolMode = ProtocolMode.ERGEBNIS, summary: Summary = Summary(), tasks: List<TaskItem> = emptyList()) =
        Meeting(
            id = "m", title = "Teamrunde", createdAt = created, durationSec = 3900f, audioFile = "",
            mode = mode, summary = summary, tasks = tasks,
        )

    // ------------------------------------------------------------------ speakable

    @Test fun weekdayAndDate() =
        assertEquals("Freitag, 27. November 2026", speakable("Fr., 27.11.2026"))

    @Test fun allWeekdayAbbreviations() {
        val expected = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
        listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEachIndexed { i, abbr ->
            assertEquals("${expected[i]}, 1. Dezember 2026", speakable("$abbr., 01.12.2026"))
        }
    }

    @Test fun yearOmittedWhenCurrent() {
        assertEquals("Freitag, 27. November", speakable("Fr., 27.11.2026", omitYear = 2026))
        assertEquals("Montag, 4. Januar 2027", speakable("Mo., 04.01.2027", omitYear = 2026))
    }

    @Test fun dateVariants() {
        assertEquals("bis 4. Dezember", speakable("bis 04.12."))
        assertEquals("am 1. Februar 1950", speakable("am 01.02.1950"))
        assertEquals("am 3. April 2052", speakable("am 3.4.52"))
        assertEquals("am 3. April 1975", speakable("am 3.4.75"))
        // Uhrzeit nach Datum ohne Jahr darf nicht als Jahr gelesen werden
        assertEquals("am 27. November 10 Uhr 30", speakable("am 27.11. 10:30"))
    }

    @Test fun nonDatesStay() {
        assertEquals("Version 1.2.3", speakable("Version 1.2.3"))
        assertEquals("1.500 Euro", speakable("1.500 Euro"))
        assertEquals("am 32.13.2026", speakable("am 32.13.2026"))
    }

    @Test fun times() {
        assertEquals("um 10 Uhr 30", speakable("um 10:30 Uhr"))
        assertEquals("um 9 Uhr", speakable("um 09:00"))
        assertEquals("von 8 Uhr 15 bis 17 Uhr", speakable("von 08:15 bis 17:00 Uhr"))
        assertEquals("Zeitstempel 1:01:40", speakable("Zeitstempel 1:01:40"))
    }

    @Test fun abbreviations() {
        assertEquals("zum Beispiel Kosten, beziehungsweise circa 5 Stunden", speakable("z. B. Kosten, bzw. ca. 5 Std."))
        assertEquals("Frau Becker und Herr Weber", speakable("Fr. Becker und Hr. Weber"))
        assertEquals("Tagesordnungspunkt 3", speakable("TOP 3"))
        assertEquals("Stoppschild", speakable("Stoppschild"))
    }

    @Test fun markupRemoved() =
        assertEquals("Wichtig: Budget", speakable("**Wichtig:** _Budget_"))

    @Test fun durations() {
        assertEquals("", spokenDuration(0f))
        assertEquals("45 Sekunden", spokenDuration(45f))
        assertEquals("1 Minute", spokenDuration(60f))
        assertEquals("12 Minuten", spokenDuration(725f))
        assertEquals("1 Stunde 5 Minuten", spokenDuration(3900f))
        assertEquals("2 Stunden", spokenDuration(7200f))
    }

    // ------------------------------------------------------------------ Sätze

    @Test fun sentenceSplitting() {
        assertEquals(
            listOf("Erster Satz.", "Am 27. November geht es weiter!", "Frage?", "Ja."),
            splitSentences("Erster Satz. Am 27. November geht es weiter! Frage? Ja."),
        )
        assertEquals(listOf("Dr. Weber kommt."), splitSentences("Dr. Weber kommt."))
        assertEquals(listOf("Person A. Danach"), splitSentences("Person A. Danach"))
    }

    @Test fun longSentencesAreWrappedAtWords() {
        val long = (1..2000).joinToString(" ") { "wort$it" }
        val parts = splitSentences(long, max = 3000)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 3000 })
        assertEquals(long, parts.joinToString(" "))
        assertFalse(parts.any { it.startsWith(" ") || it.endsWith(" ") })
    }

    // ------------------------------------------------------------------ buildSpeechText

    @Test fun ergebnisprotokoll() {
        val m = meeting(
            summary = Summary(
                overview = "Das Team hat das Budget besprochen. Es gibt z. B. neue Angebote.",
                decisions = listOf("Budget wird freigegeben", " "),
            ),
            tasks = listOf(
                TaskItem("Angebote einholen", owner = "Frau Becker", due = "Fr., 27.11.2026", id = "1"),
                TaskItem("Protokoll verschicken", owner = "offen", due = "offen", id = "2"),
                TaskItem("Raum buchen.", owner = "Jonas", due = "nächste Sitzung", done = true, id = "3"),
            ),
        )
        assertEquals(
            listOf(
                "Ergebnisprotokoll: Teamrunde.",
                "Montag, 23. November 2026, 10 Uhr 30. Dauer: 1 Stunde 5 Minuten.",
                "Zusammenfassung.",
                "Das Team hat das Budget besprochen.",
                "Es gibt zum Beispiel neue Angebote.",
                "Ein Beschluss.",
                "Budget wird freigegeben.",
                "3 Aufgaben.",
                "Frau Becker: Angebote einholen, bis Freitag, 27. November.",
                "Protokoll verschicken.",
                "Jonas: Raum buchen, bis nächste Sitzung, erledigt.",
            ),
            buildSpeechText(m, berlin),
        )
    }

    @Test fun wortprotokollWithSummaryReadsOnlySummary() {
        val m = meeting(
            mode = ProtocolMode.WORT,
            summary = Summary(overview = "Kurz und knapp."),
            tasks = listOf(TaskItem("Aufgabe", id = "1")),
        )
        val out = buildSpeechText(m, berlin)
        assertEquals("Wortprotokoll: Teamrunde.", out[0])
        assertEquals(listOf("Zusammenfassung.", "Kurz und knapp."), out.drop(2))
    }

    @Test fun wortprotokollWithoutSummaryReadsTasks() {
        val m = meeting(mode = ProtocolMode.WORT, tasks = listOf(TaskItem("Bericht schreiben", "Anna", "bis Ende Januar", id = "1")))
        assertEquals(listOf("Eine Aufgabe.", "Anna: Bericht schreiben, bis Ende Januar."), buildSpeechText(m, berlin).drop(2))
    }

    @Test fun emptyMeetingSaysSo() {
        val out = buildSpeechText(meeting().copy(title = "", durationSec = 0f), berlin)
        assertEquals("Ergebnisprotokoll: Besprechung.", out[0])
        assertEquals("Montag, 23. November 2026, 10 Uhr 30.", out[1])
        assertEquals("Für diese Besprechung gibt es noch keine Zusammenfassung und keine Aufgaben.", out[2])
        assertEquals(3, out.size)
    }

    @Test fun fullHourHasNoMinutes() {
        val nine = ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals("Montag, 23. November 2026, 9 Uhr. Dauer: 1 Stunde 5 Minuten.",
            buildSpeechText(meeting().copy(createdAt = nine), berlin)[1])
    }

    @Test fun everyUtteranceFitsTtsLimit() {
        val huge = (1..3000).joinToString(" ") { "Wort" }
        val out = buildSpeechText(meeting(summary = Summary(overview = huge)), berlin)
        assertTrue(out.all { it.length <= MAX_UTTERANCE })
        assertTrue(out.size > 4)
    }
}
