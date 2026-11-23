package de.protokollki

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.protokollki.audio.WavIO
import de.protokollki.audio.importAudio
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.Segment
import de.protokollki.data.meetingFromJson
import de.protokollki.engine.AskEngine
import de.protokollki.engine.AskPrompt
import de.protokollki.engine.MeetingSearch
import de.protokollki.engine.ModelManager
import de.protokollki.engine.ModelSet
import de.protokollki.engine.Transcriber
import de.protokollki.engine.VoiceMath
import de.protokollki.engine.VoiceProfiles
import de.protokollki.export.ExportFormat
import de.protokollki.export.Exporter
import de.protokollki.importdoc.DocumentText
import de.protokollki.importdoc.ProtocolImporter
import de.protokollki.service.ProcessingService
import de.protokollki.testutil.Documents
import de.protokollki.testutil.Fixtures
import de.protokollki.testutil.RequiresModels
import de.protokollki.testutil.TestEnv
import de.protokollki.testutil.TestEnv.context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Langsame Ende-zu-Ende-Tests mit den echten KI-Modellen (Paket „Kompakt“: Whisper base,
 * pyannote, WeSpeaker, Qwen2.5 1.5B) auf scripts/e2e/meeting.wav (69 s, 3 Sprecher).
 *
 * Voraussetzung: Modelle liegen in filesDir/models (siehe [TestEnv.requireKompaktModels]).
 * Laufzeit auf dem x86_64-Emulator (4 Kerne) ≈ 20–35 min für die ganze Klasse.
 */
@RequiresModels
@RunWith(AndroidJUnit4::class)
class ModelPipelineTest {

    private lateinit var set: ModelSet
    private var originalModelSet: String? = null

    @Before
    fun setUp() {
        originalModelSet = AppSettings.state.value.modelSetId
        TestEnv.awaitNoBusyMeeting()
        TestEnv.resetAppState()
        set = TestEnv.requireKompaktModels()
    }

    @After
    fun tearDown() {
        // Ein fehlgeschlagener Test darf keine laufende Verarbeitung hinterlassen
        if (MeetingRepository.meetings.value.any { it.isBusy }) {
            TestEnv.withAppInForeground {
                runCatching { ProcessingService.cancel(context) }
                TestEnv.awaitNoBusyMeeting(timeoutMs = 300_000)
            }
        }
        AskEngine.releaseNow()
        TestEnv.resetAppState()
        if (AppSettings.state.value.modelSetId != originalModelSet) ModelManager.selectSet(originalModelSet)
    }

    // ================================================================ Hilfen

    private fun samples(): FloatArray {
        val s = WavIO.readMono16k(TestEnv.meetingWav().absolutePath)
        assertEquals("meeting.wav sollte ~69 s lang sein", 69f, s.size / 16_000f, 2f)
        return s
    }

    /** Wortstämme aus meeting.wav; Whisper base schreibt sie nicht immer gleich. */
    private val keyGroups = listOf(
        listOf("telefon"),
        listOf("tour", "nord"),
        listOf("freitag"),
        listOf("januar"),
        listOf("märz", "maerz", "marz"),
    )

    private fun assertRefsValid(m: Meeting) {
        val valid = -1 until m.segments.size
        m.summary.topics.forEach { t ->
            t.refs.forEach { assertTrue("Themen-Beleg $it ungültig (${m.segments.size} Beiträge)", it in valid) }
        }
        m.summary.decisionRefs.forEach { assertTrue("Beschluss-Beleg $it ungültig", it in valid) }
        m.summary.openPointRefs.forEach { assertTrue("Offener-Punkt-Beleg $it ungültig", it in valid) }
        m.tasks.forEach { assertTrue("Aufgaben-Beleg ${it.ref} ungültig", it.ref in valid) }
        m.followUps.forEach { assertTrue("Folge-Beleg ${it.ref} ungültig", it.ref in valid) }
    }

    private fun assertDiskEqualsMemory(id: String) {
        val current = MeetingRepository.get(id)!!
        val disk = meetingFromJson(JSONObject(File(context.filesDir, "meetings/$id/meeting.json").readText()))
        // Fortschritt wird bewusst nicht gespeichert
        assertEquals("meeting.json weicht vom Speicherstand ab", current.copy(progress = 0f, progressText = ""), disk)
    }

    private fun assertTranscriptSane(segments: List<Segment>, durationSec: Float) {
        assertTrue("kein Transkript", segments.isNotEmpty())
        for ((i, s) in segments.withIndex()) {
            assertTrue("Beitrag $i: leerer Text", s.text.isNotBlank())
            assertTrue("Beitrag $i: start ${s.start} ≥ end ${s.end}", s.start < s.end)
            assertTrue("Beitrag $i: außerhalb der Aufnahme", s.start >= 0f && s.end <= durationSec + 0.5f)
            if (i > 0) {
                val prev = segments[i - 1]
                assertTrue("Beitrag $i nicht zeitlich sortiert", s.start >= prev.start)
                assertTrue("Beitrag $i überlappt um ${prev.end - s.start} s", s.start >= prev.end - 0.05f)
                assertFalse("Beitrag $i wiederholt den vorigen Text: ${s.text}",
                    s.text.trim().equals(prev.text.trim(), ignoreCase = true))
            }
        }
        val all = segments.joinToString(" ") { it.text }.lowercase()
        TestEnv.log("Transkript: $all")
        assertTrue("„Angebot“ nicht erkannt: $all",
            listOf("angebot", "angebote", "anngebot").any { all.contains(it) })
        val found = keyGroups.count { g -> g.any { all.contains(it) } }
        assertTrue("nur $found von ${keyGroups.size} Schlüsselbegriffen erkannt: $all", found >= 2)
        assertTrue("Transkript klingt nicht deutsch: $all",
            listOf(" und ", " die ", " der ", " wir ", " ich ", " das ").count { all.contains(it) } >= 2)
        assertFalse("Whisper-Halluzination im Transkript: $all",
            listOf("untertitel", "amara.org", "vielen dank fürs zuschauen").any { all.contains(it) })
        val covered = segments.sumOf { (it.end - it.start).toDouble() }
        assertTrue("nur $covered s von $durationSec s transkribiert", covered >= 0.5 * durationSec)
    }

    /** Neue Besprechung aus meeting.wav über den echten Importweg (MediaExtractor/MediaCodec). */
    private fun importMeetingWav(): Meeting {
        val m = runBlocking { importAudio(context, Uri.fromFile(TestEnv.meetingWav())) }
        assertEquals(MeetingStatus.RECORDED, m.status)
        assertEquals(69f, m.durationSec, 2f)
        assertTrue(File(m.audioFile).length() > 1_000_000)
        return m
    }

    // ================================================================ Sprechererkennung + Whisper

    @Test
    fun transcriber_diarizeAndTranscribe_meetingWav() {
        val samples = samples()
        val duration = samples.size / 16_000f
        val t = Transcriber(set, "de")

        val progress = mutableListOf<Float>()
        val t0 = SystemClock.elapsedRealtime()
        val turns = t.diarize(samples, 0) { f -> progress += f; true }
        TestEnv.log("Diarisierung: ${turns.size} Abschnitte in ${(SystemClock.elapsedRealtime() - t0) / 1000} s, " +
            "Fortschritt ${progress.size}× bis ${progress.maxOrNull()}")
        assertTrue("Fortschritts-Callback wurde nie aufgerufen (JNI-Signatur?)", progress.isNotEmpty())
        assertTrue("Fortschritt außerhalb 0..1: $progress", progress.all { it in 0f..1f })
        assertTrue("keine Sprecherabschnitte", turns.isNotEmpty())
        val rawSpeakers = turns.map { it.speaker }.distinct().size
        assertTrue("$rawSpeakers Sprecher erkannt, erwartet 2–4 (3)", rawSpeakers in 2..4)

        val chunks = t.planChunks(turns, samples)
        assertTrue(chunks.isNotEmpty())
        for ((i, c) in chunks.withIndex()) {
            assertTrue("Abschnitt $i zu lang für Whisper: ${c.end - c.start} s", c.end - c.start <= 30f)
            assertTrue("Abschnitt $i leer", c.end > c.start)
            if (i > 0) assertTrue("Abschnitte $i überlappen", c.start >= chunks[i - 1].end - 1e-3f)
        }
        val ids = chunks.map { it.speaker }.distinct()
        assertEquals("Sprecher-IDs nicht fortlaufend ab 0", (0 until ids.size).toList(), ids.sorted())

        var lastDone = 0
        val t1 = SystemClock.elapsedRealtime()
        val segments = t.transcribe(samples, chunks) { done, total, partial ->
            assertTrue("Fortschritt rückwärts", done > lastDone && done <= total)
            assertEquals(chunks.size, total)
            assertTrue(partial.size <= done)
            lastDone = done
            true
        }
        TestEnv.log("Whisper: ${segments.size} Beiträge in ${(SystemClock.elapsedRealtime() - t1) / 1000} s")
        assertEquals("nicht alle Abschnitte verarbeitet", chunks.size, lastDone)
        assertTranscriptSane(segments, duration)
        val speakers = segments.map { it.speakerId }.distinct().size
        assertTrue("$speakers Sprecher im Transkript, erwartet 2–4", speakers in 2..4)
    }

    @Test
    fun transcriber_diarizationCancel_stopsCleanly_andNextRunWorks() {
        val samples = samples()
        val t = Transcriber(set, "de")
        var calls = 0
        val cancelled = t.diarize(samples, 0) { calls++; false }
        assertTrue("Callback beim Abbrechen nie aufgerufen", calls >= 1)
        TestEnv.log("Abgebrochene Diarisierung: $calls Aufrufe, ${cancelled.size} Abschnitte")

        // Der native Zustand muss sauber sein: der nächste Lauf (mit fester Sprecherzahl) funktioniert
        val turns = t.diarize(samples, 3) { true }
        val speakers = turns.map { it.speaker }.distinct().size
        assertTrue("nach Abbruch: $speakers Sprecher bei vorgegebenen 3", speakers in 2..3)
    }

    // ================================================================ Gesamte Verarbeitung

    @Test
    fun processingService_endToEnd_meetingWav() {
        val imported = importMeetingWav()
        val run = TestEnv.withAppInForeground {
            ProcessingService.process(context, imported.id)
            TestEnv.awaitProcessing(imported.id, timeoutMs = 15 * 60_000L)
        }
        val m = run.meeting
        TestEnv.log("Ergebnis: status=${m.status} error=${m.error} sprecher=${m.speakers} " +
            "überblick=${m.summary.overview} aufgaben=${m.tasks}")
        assertNull("Fehler: ${m.error}", m.error)
        assertEquals(MeetingStatus.DONE, m.status)
        assertTrue("Phase Transkription nie gesehen: ${run.seenStatuses}", MeetingStatus.TRANSCRIBING in run.seenStatuses)
        assertTrue("Phase Protokoll nie gesehen: ${run.seenStatuses}", MeetingStatus.SUMMARIZING in run.seenStatuses)

        assertTranscriptSane(m.segments, m.durationSec)
        assertTrue("${m.speakers.size} Sprecher, erwartet ≥ 2", m.speakers.size in 2..4)
        val speakerIds = m.speakers.map { it.id }.toSet()
        assertTrue("Beitrag mit unbekanntem Sprecher", m.segments.all { it.speakerId in speakerIds })
        assertTrue("Sprecher ohne Beitrag", speakerIds.all { id -> m.segments.any { it.speakerId == id } })

        assertTrue("keine Zusammenfassung", m.summary.overview.isNotBlank())
        assertTrue("keine Aufgaben erkannt", m.tasks.isNotEmpty())
        m.tasks.forEach {
            assertTrue("Aufgabe ohne Text", it.text.isNotBlank())
            assertTrue("Aufgabe ohne Verantwortlich-Feld", it.owner.isNotBlank())
            assertTrue("Aufgabe ohne Frist-Feld", it.due.isNotBlank())
        }
        assertTrue("keine Aufgabe mit Person oder Frist: ${m.tasks}",
            m.tasks.any { it.owner != "offen" || it.due != "offen" })
        assertEquals("Aufgaben-IDs nicht eindeutig", m.tasks.size, m.tasks.map { it.id }.toSet().size)
        assertRefsValid(m)
        assertDiskEqualsMemory(m.id)
        assertTrue("Stimmproben der Sprecher fehlen (Stimmprofile)",
            File(context.filesDir, "meetings/${m.id}/speaker_embeddings.json").isFile)

        // Ergebnis ist sofort durchsuchbar und exportierbar
        assertTrue(MeetingSearch.search(MeetingRepository.meetings.value, "Angebote Telefonanlage").isNotEmpty())
        Documents.assertValidPdf(Exporter.export(context, m, ExportFormat.PDF))
        Documents.readDocx(Exporter.export(context, m, ExportFormat.DOCX, anonymized = true))
    }

    @Test
    fun processingService_cancelDuringTranscription_isNotStuck_andRestartsCleanly() {
        val m = importMeetingWav()
        TestEnv.withAppInForeground {
            ProcessingService.process(context, m.id)
            TestEnv.waitUntil("Transkription beginnt", 10 * 60_000L, pollMs = 500,
                describe = { MeetingRepository.get(m.id)?.let { "${it.status} „${it.progressText}“ ${it.error}" }.orEmpty() }) {
                val cur = MeetingRepository.get(m.id)!!
                if (cur.status == MeetingStatus.ERROR) fail("Verarbeitung fehlgeschlagen: ${cur.error}")
                cur.status == MeetingStatus.TRANSCRIBING && cur.progressText.startsWith("Transkribiere")
            }
            ProcessingService.cancel(context)
            TestEnv.waitUntil("Abbruch wirkt", 5 * 60_000L, pollMs = 500,
                describe = { MeetingRepository.get(m.id)?.status.toString() }) {
                !MeetingRepository.get(m.id)!!.isBusy
            }
            val after = MeetingRepository.get(m.id)!!
            assertTrue("nach Abbruch: ${after.status}",
                after.status == MeetingStatus.RECORDED)
            assertNull(after.error)
            assertEquals(0f, after.progress)
            assertDiskEqualsMemory(m.id)

            // Dem Service Zeit geben, sich zu beenden (siehe Bericht: stopSelf()-Wettlauf)
            Thread.sleep(1_500)

            // Zweiter Lauf startet sauber (Diarisierung läuft wieder an), dann erneut abbrechen
            MeetingRepository.update(m.id) { it.copy(status = MeetingStatus.RECORDED, segments = emptyList()) }
            ProcessingService.process(context, m.id)
            TestEnv.waitUntil("zweiter Lauf startet", 3 * 60_000L, pollMs = 500,
                describe = { MeetingRepository.get(m.id)?.let { "${it.status} ${it.progress} „${it.progressText}“" }.orEmpty() }) {
                val cur = MeetingRepository.get(m.id)!!
                if (cur.status == MeetingStatus.ERROR) fail("zweiter Lauf fehlgeschlagen: ${cur.error}")
                cur.status == MeetingStatus.TRANSCRIBING && cur.progress > 0f
            }
            ProcessingService.cancel(context)
            TestEnv.waitUntil("zweiter Abbruch wirkt", 5 * 60_000L, pollMs = 500) {
                !MeetingRepository.get(m.id)!!.isBusy
            }
            assertNull(MeetingRepository.get(m.id)!!.error)
        }
    }

    @Test
    fun processingService_tooShortRecording_failsWithGermanMessage() {
        val m = MeetingRepository.create("Zu kurz", MeetingStatus.RECORDED)
        TestEnv.writeWav(File(m.audioFile), TestEnv.tone(0.5f))
        val run = TestEnv.withAppInForeground {
            ProcessingService.process(context, m.id)
            TestEnv.awaitProcessing(m.id, timeoutMs = 120_000)
        }
        assertEquals(MeetingStatus.ERROR, run.meeting.status)
        assertTrue("${run.meeting.error}", run.meeting.error.orEmpty().contains("zu kurz"))
    }

    // ================================================================ Stimmprofile

    @Test
    fun voiceProfiles_enrollFromRecording_identifiesExactlyThatSpeaker() {
        val samples = samples()
        val t = Transcriber(set, "de")
        val chunks = t.planChunks(t.diarize(samples, 0) { true }, samples)
        val bySpeaker = chunks.groupBy { it.speaker }
        assertTrue("zu wenige Sprecher für den Test: ${bySpeaker.keys}", bySpeaker.size >= 2)
        val target = bySpeaker.maxByOrNull { (_, l) -> l.sumOf { (it.end - it.start).toDouble() } }!!.key
        val spans = bySpeaker.getValue(target).map { it.start to it.end }
        val enrollAudio = VoiceMath.collectAudio(samples, spans, maxSeconds = 14f)
        val speech = VoiceMath.speechSeconds(enrollAudio)
        TestEnv.log("Einlernen: Sprecher $target, ${enrollAudio.size / 16_000f} s Audio, $speech s Sprache")
        assertTrue("Testaudio hat zu wenig Sprache ($speech s)", speech >= 8f)

        var profileId: String? = null
        try {
            val profile = runBlocking { VoiceProfiles.enrollFromRecording(context, "Testprofil Leitung", enrollAudio) }
            profileId = profile.id
            assertEquals(1, profile.samples)
            assertTrue(VoiceProfiles.profiles.value.any { it.id == profile.id })

            val meeting = MeetingRepository.create("Stimmprofil-Test", MeetingStatus.RECORDED)
            val known = VoiceProfiles.identify(context, meeting.id, samples, chunks)
            TestEnv.log("Zuordnung: $known")
            assertEquals("Profil muss genau einem Sprecher zugeordnet werden: $known", 1, known.size)
            assertEquals("falscher Sprecher erkannt", target, known.keys.single())
            assertEquals(profile.id, known.values.single().id)
            assertTrue(VoiceProfiles.canEnrollFromMeeting(meeting.id, target))

            // gleicher Name = Profil verfeinern statt doppelt anlegen
            val refined = runBlocking { VoiceProfiles.enrollFromRecording(context, "testprofil leitung", enrollAudio) }
            assertEquals(profile.id, refined.id)
            assertEquals(2, refined.samples)
            assertEquals(1, VoiceProfiles.profiles.value.size)
        } finally {
            profileId?.let { VoiceProfiles.delete(it) }
        }
        assertTrue(VoiceProfiles.profiles.value.isEmpty())
    }

    @Test
    fun voiceProfiles_enrollWithTooLittleSpeech_throwsGermanMessage() {
        val samples = samples()
        val short = samples.copyOfRange(16_000, 16_000 * 6)     // 5 s
        val silence = FloatArray(16_000 * 20)                  // 20 s Stille
        for (audio in listOf(short, silence)) {
            val e = runCatching {
                runBlocking { VoiceProfiles.enrollFromRecording(context, "Zu kurz", audio) }
            }.exceptionOrNull()
            assertTrue("erwartet IllegalArgumentException, war $e", e is IllegalArgumentException)
            assertTrue(e!!.message!!, e.message!!.contains("Zu wenig Sprache"))
        }
        assertTrue("fehlgeschlagenes Einlernen hat ein Profil angelegt", VoiceProfiles.profiles.value.isEmpty())
    }

    // ================================================================ Fragen an alle Besprechungen

    @Test
    fun askEngine_answersFromLocalModel_withValidCitations() {
        MeetingRepository.save(Fixtures.processedMeetingLikeWav())
        val q = "Was wurde zur Tour Nord beschlossen?"
        AskEngine.ask(context, q)
        val first = AskEngine.state.value
        assertEquals(q, first.question)
        assertTrue("keine Sofort-Treffer", first.hits.isNotEmpty())
        assertTrue("KI-Antwort startet nicht", first.answering)
        assertNull(first.error)

        TestEnv.waitUntil("KI-Antwort", 8 * 60_000L, pollMs = 1_000,
            describe = { AskEngine.state.value.let { "${it.progressText} error=${it.error}" } }) {
            !AskEngine.state.value.answering
        }
        val s = AskEngine.state.value
        TestEnv.log("Antwort: ${s.answer}")
        assertNull("Fehler: ${s.error}", s.error)
        assertTrue("leere Antwort", s.answer.isNotBlank())
        assertFalse("Rohe Denk-Tags in der Antwort", s.answer.contains("<think>"))
        val sources = minOf(s.hits.size, AskPrompt.MAX_SOURCES)
        val cites = AskEngine.citations(s.answer)
        cites.forEach { assertTrue("Quelle [$it] verweist ins Leere (nur $sources Fundstellen)", it in 1..sources) }
        assertTrue("Antwort geht nicht auf die Frage ein: ${s.answer}",
            listOf("märz", "teil", "tour", "nord").any { s.answer.lowercase().contains(it) })
        AskEngine.release()
    }

    @Test
    fun askEngine_newQuestionCancelsRunningAnswer() {
        MeetingRepository.save(Fixtures.processedMeetingLikeWav())
        AskEngine.ask(context, "Was wurde zur Telefonanlage besprochen?")
        Thread.sleep(3_000)   // Modell lädt/rechnet gerade
        val q2 = "Bis wann sollen die Angebote eingeholt werden?"
        AskEngine.ask(context, q2)
        TestEnv.waitUntil("Antwort auf die zweite Frage", 8 * 60_000L, pollMs = 1_000) {
            !AskEngine.state.value.answering
        }
        val s = AskEngine.state.value
        assertEquals(q2, s.question)
        assertNull("Fehler: ${s.error}", s.error)
        assertTrue("leere Antwort", s.answer.isNotBlank())
        AskEngine.release()
    }

    // ================================================================ Importiertes Protokoll → KI

    @Test
    fun importedDocx_processesToDone_withTasks() {
        val f = File(context.cacheDir, "import-test/teamsitzung.docx").apply { parentFile?.mkdirs() }
        Documents.writeDocx(f, listOf(
            "Protokoll der Teamsitzung",
            "Datum: 12.03.2025",
            "Anwesend: Herr Wagner, Frau Becker, Herr Okafor",
            "TOP 1: Telefonanlage. Die alte Anlage fällt regelmäßig aus. Frau Becker holt bis Freitag drei Angebote ein.",
            "TOP 2: Tour Nord. Die Tour ist zu lang. Beschluss: Die Tour Nord wird ab März geteilt. Herr Wagner plant die neuen Touren bis Ende Februar.",
            "TOP 3: Verschiedenes. Herr Okafor bestellt bis 20.03.2025 neue Warnwesten.",
        ))
        val imported = runBlocking { ProtocolImporter.import(context, Uri.fromFile(f), startProcessing = false) }
        assertEquals("teamsitzung.docx", imported.importedFrom)

        val run = TestEnv.withAppInForeground {
            ProcessingService.regenerate(context, imported.id)
            TestEnv.awaitProcessing(imported.id, timeoutMs = 10 * 60_000L)
        }
        val m = run.meeting
        TestEnv.log("Import-Ergebnis: ${m.summary.overview} / ${m.tasks}")
        assertNull("Fehler: ${m.error}", m.error)
        assertEquals(MeetingStatus.DONE, m.status)
        assertFalse("Import darf nicht transkribiert werden", MeetingStatus.TRANSCRIBING in run.seenStatuses)
        assertEquals("Absätze verändert", imported.segments, m.segments)
        assertEquals(imported.speakers, m.speakers)
        assertEquals("teamsitzung.docx", m.importedFrom)
        assertEquals(DocumentText.SPEAKER_NAME, m.speakers.single().name)
        assertTrue("keine Zusammenfassung", m.summary.overview.isNotBlank())
        assertTrue("keine Aufgaben aus dem Protokoll", m.tasks.isNotEmpty())
        assertTrue("keine Aufgabe mit Person oder Frist: ${m.tasks}",
            m.tasks.any { it.owner != "offen" || it.due != "offen" })
        assertRefsValid(m)
        assertDiskEqualsMemory(m.id)
        assertNotNull(MeetingRepository.get(m.id))
    }
}
