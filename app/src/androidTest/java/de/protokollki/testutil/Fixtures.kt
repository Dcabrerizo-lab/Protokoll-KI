package de.protokollki.testutil

import de.protokollki.data.FollowUp
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.ProtocolTemplate
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic
import de.protokollki.importdoc.DocumentText
import java.util.UUID

/** Feste, realistische Testdaten (angelehnt an scripts/e2e/meeting.wav). */
object Fixtures {
    // Sensible Angaben, die in anonymisierten Exporten NIE auftauchen dürfen
    const val CLIENT = "Frau Schulz"
    const val CLIENT_SURNAME = "Schulz"
    const val PHONE = "0171 2345678"
    const val PHONE_TAIL = "2345678"
    const val IBAN = "DE89 3704 0044 0532 0130 00"
    const val IBAN_PART = "3704 0044"
    const val CUSTOM_TERM = "Kolbe"           // aus den Einstellungen („Immer schwärzen“)
    const val ORGANIZATION = "Tafel Musterstadt e. V."

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    val speakers = listOf(
        Speaker(0, "Herr Wagner"),
        Speaker(1, "Frau Becker"),
        Speaker(2, "Herr Okafor"),
    )

    /**
     * Vereinssitzung mit allem, was der Export kann: Kopfdaten, Tagesordnung, Themen mit Belegen,
     * Beschlüsse, offene Punkte, Aufgaben, Stand früherer Aufgaben – und sensible Daten
     * (Klientenname, Telefonnummer, IBAN, eigener Begriff).
     */
    fun richMeeting(
        id: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis() - HOUR,
        audioFile: String = "",
        mode: ProtocolMode = ProtocolMode.ERGEBNIS,
    ): Meeting = Meeting(
        id = id,
        title = "Vorstandssitzung Fall $CLIENT_SURNAME",
        createdAt = createdAt,
        durationSec = 45f,
        audioFile = audioFile,
        status = MeetingStatus.DONE,
        mode = mode,
        segments = listOf(
            Segment(0, 0f, 6f, "Guten Morgen, wir beginnen mit der Telefonanlage."),
            Segment(1, 6f, 14f, "Ich hole bis Freitag drei Angebote für die neue Telefonanlage ein."),
            Segment(2, 14f, 22f, "$CLIENT hat gestern angerufen, ihre Nummer ist $PHONE."),
            Segment(0, 22f, 30f, "Die Spende von Herrn $CUSTOM_TERM ist auf dem Konto $IBAN eingegangen."),
            Segment(1, 30f, 38f, "Die Tour Nord wird ab März geteilt, das übernimmt Herr Wagner."),
            Segment(2, 38f, 45f, "Die Telefonanlage soll bis Ende Januar laufen."),
        ),
        speakers = speakers,
        summary = Summary(
            overview = "Der Vorstand hat die Telefonanlage, den Anruf von $CLIENT und die Tour Nord besprochen.",
            topics = listOf(
                Topic("Telefonanlage", listOf("Drei Angebote bis Freitag", "Inbetriebnahme bis Ende Januar"), listOf(1, 5)),
                Topic("Spenden", listOf("Spende von Herrn $CUSTOM_TERM auf $IBAN eingegangen"), listOf(3)),
                Topic("Tour Nord", listOf("Teilung ab März"), listOf(4)),
            ),
            decisions = listOf("Die Tour Nord wird ab März geteilt."),
            openPoints = listOf("Rückruf bei $CLIENT unter $PHONE"),
            decisionRefs = listOf(4),
            openPointRefs = listOf(2),
        ),
        tasks = listOf(
            TaskItem("Drei Angebote für die Telefonanlage einholen", "Frau Becker", "Freitag", id = "rich-t1", ref = 1),
            TaskItem("Tour Nord ab März aufteilen", "Herr Wagner", "01.03.2027", id = "rich-t2", ref = 4),
            TaskItem("$CLIENT zurückrufen", "offen", "offen", id = "rich-t3", ref = 2),
        ),
        agenda = listOf("Telefonanlage", "Spenden", "Tour Nord"),
        followUps = listOf(
            FollowUp("old-task-1", "old-meeting-1", "Kassenbericht vorbereiten", "Frau Becker", done = true, ref = 0),
        ),
        sensitiveTerms = listOf(CLIENT),
        template = ProtocolTemplate.VEREINSSITZUNG,
        location = "Vereinsheim Nord",
        chair = "Herr Wagner",
        minuteTaker = "Frau Becker",
    )

    /** Fertige Besprechung, deren Transkript dem Inhalt von meeting.wav entspricht (für AskEngine). */
    fun processedMeetingLikeWav(id: String = UUID.randomUUID().toString()): Meeting = Meeting(
        id = id,
        title = "Teamsitzung Fuhrpark",
        createdAt = System.currentTimeMillis() - 2 * HOUR,
        durationSec = 69f,
        audioFile = "",
        audioDeleted = true,
        status = MeetingStatus.DONE,
        segments = listOf(
            Segment(0, 0f, 8f, "Guten Morgen zusammen, heute geht es um die Telefonanlage und die Touren."),
            Segment(1, 8f, 17f, "Für die neue Telefonanlage hole ich bis Freitag drei Angebote ein."),
            Segment(0, 17f, 25f, "Gut. Die Telefonanlage muss bis Ende Januar installiert sein."),
            Segment(2, 25f, 36f, "Die Tour Nord ist zu lang geworden, die Fahrer schaffen sie nicht mehr."),
            Segment(0, 36f, 47f, "Dann beschließen wir: Die Tour Nord wird ab März geteilt."),
            Segment(1, 47f, 58f, "Einverstanden, ich plane die zwei neuen Touren bis Mitte Februar."),
        ),
        speakers = speakers,
        summary = Summary(
            overview = "Besprochen wurden die neue Telefonanlage und die Teilung der Tour Nord.",
            topics = listOf(
                Topic("Telefonanlage", listOf("Drei Angebote bis Freitag", "Installation bis Ende Januar"), listOf(1, 2)),
                Topic("Tour Nord", listOf("Tour Nord wird ab März geteilt"), listOf(4)),
            ),
            decisions = listOf("Die Tour Nord wird ab März geteilt."),
            decisionRefs = listOf(4),
        ),
        tasks = listOf(
            TaskItem("Drei Angebote für die Telefonanlage einholen", "Frau Becker", "Freitag", ref = 1),
            TaskItem("Zwei neue Touren planen", "Frau Becker", "Mitte Februar", ref = 5),
        ),
    )

    // ------------------------------------------------------------------ Oberfläche

    const val UI_AUDIO_TITLE = "Teamsitzung Fuhrpark"
    const val UI_FORMAL_TITLE = "Vorstandssitzung Förderverein"
    const val UI_IMPORTED_TITLE = "Altes Protokoll Hausmeister"
    const val UI_IMPORTED_FILE = "protokoll-2024.pdf"
    const val UI_TASK_BECKER = "Drei Angebote für die Telefonanlage einholen"
    const val UI_OPEN_TASKS = 5

    class UiSeed(val audio: Meeting, val formal: Meeting, val imported: Meeting)

    /**
     * Drei fertige Besprechungen für die Oberflächentests: eine mit echter Aufnahme (Player),
     * eine formale Vereinssitzung ohne Audio und ein importiertes Protokoll. 5 offene Aufgaben:
     * Frau Becker (2), Herr Wagner (2), ohne Zuständigkeit (1).
     */
    fun seedUiMeetings(): UiSeed {
        val now = System.currentTimeMillis()
        val audioId = UUID.randomUUID().toString()
        val audio = Meeting(
            id = audioId,
            title = UI_AUDIO_TITLE,
            createdAt = now - HOUR,
            durationSec = 69f,
            audioFile = MeetingRepository.dirFor(audioId).resolve("audio.wav").absolutePath,
            status = MeetingStatus.DONE,
            segments = listOf(
                Segment(0, 0f, 8f, "Guten Morgen, heute geht es um die Telefonanlage."),
                Segment(1, 8f, 17f, "Ich hole bis Freitag drei Angebote für die Telefonanlage ein."),
                Segment(0, 17f, 25f, "Die Anlage muss bis Ende Januar laufen."),
                Segment(2, 25f, 36f, "Die Tour Nord ist zu lang, die Fahrer schaffen sie nicht."),
                Segment(0, 36f, 47f, "Wir teilen die Tour Nord ab März."),
            ),
            speakers = speakers,
            summary = Summary(
                overview = "Kurzer Überblick: Telefonanlage und Tour Nord.",
                topics = listOf(
                    Topic("Telefonanlage", listOf("Drei Angebote bis Freitag", "Betrieb bis Ende Januar"), listOf(1, 2)),
                    Topic("Tour Nord", listOf("Teilung ab März"), listOf(4)),
                ),
                decisions = listOf("Die Tour Nord wird ab März geteilt."),
                decisionRefs = listOf(4),
            ),
            tasks = listOf(
                TaskItem(UI_TASK_BECKER, "Frau Becker", "Freitag", id = "ui-t1", ref = 1),
                TaskItem("Tour Nord ab März aufteilen", "Herr Wagner", "01.03.2027", id = "ui-t2", ref = 4),
                TaskItem("Dienstplan aktualisieren", "offen", "offen", id = "ui-t3", ref = -1),
            ),
        )
        TestEnv.installMeetingAudio(audio)
        MeetingRepository.save(audio)

        val formal = richMeeting(createdAt = now - 2 * DAY).copy(
            title = UI_FORMAL_TITLE,
            audioDeleted = true,
            sensitiveTerms = emptyList(),
            tasks = listOf(TaskItem("Kassenprüfung organisieren", "Frau Becker", "offen", id = "ui-t4", ref = 0)),
        )
        MeetingRepository.save(formal)

        val paragraphs = listOf(
            UI_IMPORTED_TITLE,
            "Der Hausmeister prüft die Heizung im Keller.",
            "Die Schließanlage wird im Frühjahr erneuert.",
        )
        val imported = Meeting(
            id = UUID.randomUUID().toString(),
            title = UI_IMPORTED_TITLE,
            createdAt = now - 10 * DAY,
            audioFile = "",
            audioDeleted = true,
            status = MeetingStatus.DONE,
            segments = paragraphs.map { Segment(0, 0f, 0f, it) },
            speakers = listOf(Speaker(0, DocumentText.SPEAKER_NAME)),
            summary = Summary(overview = "Heizung und Schließanlage besprochen."),
            tasks = listOf(TaskItem("Heizung im Keller prüfen lassen", "Herr Wagner", "offen", id = "ui-t5", ref = 1)),
            importedFrom = UI_IMPORTED_FILE,
        )
        MeetingRepository.save(imported)
        return UiSeed(audio, formal, imported)
    }
}
