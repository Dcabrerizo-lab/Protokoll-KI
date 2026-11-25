package de.protokollki

import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.protokollki.audio.AudioImportException
import de.protokollki.audio.WavIO
import de.protokollki.audio.importAudio
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.ProtocolTemplate
import de.protokollki.data.Segment
import de.protokollki.data.SettingsState
import de.protokollki.data.Speaker
import de.protokollki.data.Summary
import de.protokollki.data.meetingFromJson
import de.protokollki.data.toJson
import de.protokollki.engine.AskEngine
import de.protokollki.engine.AskPrompt
import de.protokollki.engine.AskState
import de.protokollki.engine.DiarizationCallback
import de.protokollki.engine.MeetingSearch
import de.protokollki.engine.ModelManager
import de.protokollki.engine.VoiceProfiles
import de.protokollki.export.ExportFormat
import de.protokollki.export.Exporter
import de.protokollki.importdoc.DocumentText
import de.protokollki.importdoc.ProtocolImportException
import de.protokollki.importdoc.ProtocolImporter
import de.protokollki.privacy.AppLock
import de.protokollki.privacy.PrivacyReport
import de.protokollki.privacy.Redactor
import de.protokollki.privacy.Retention
import de.protokollki.service.PowerStatus
import de.protokollki.service.ProcessingService
import de.protokollki.testutil.Documents
import de.protokollki.testutil.Fixtures
import de.protokollki.testutil.TestEnv
import de.protokollki.testutil.TestEnv.context
import de.protokollki.tts.ReadAloud
import de.protokollki.tts.ReadAloudState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Schnelle Tests ohne KI-Modelle für alles, was nur auf einer echten Android-Laufzeit geprüft
 * werden kann: PdfRenderer, XmlPullParser, FileProvider, SharedPreferences, das Android-org.json,
 * MediaCodec, Sprachausgabe, Foreground-Service und JNI-Signaturen. Laufzeit ≈ 1–2 min.
 */
@RunWith(AndroidJUnit4::class)
class PlatformTest {

    private var originalModelSet: String? = null

    @Before
    fun setUp() {
        TestEnv.awaitNoBusyMeeting()
        originalModelSet = AppSettings.state.value.modelSetId
        TestEnv.resetAppState()
    }

    @After
    fun tearDown() {
        // Kein Warten auf „beschäftigte“ Besprechungen: einige Tests legen bewusst Besprechungen im
        // Zustand TRANSCRIBING/SUMMARIZING an, ohne dass ein Service läuft – resetAppState löscht sie.
        if (AppSettings.state.value.modelSetId != originalModelSet) ModelManager.selectSet(originalModelSet)
        TestEnv.resetAppState()
    }

    companion object {
        @JvmStatic
        @AfterClass
        fun releaseTts() {
            TestEnv.instrumentation.runOnMainSync { runCatching { ReadAloud.shutdown() } }
        }
    }

    private fun fromDisk(id: String): Meeting =
        meetingFromJson(JSONObject(File(context.filesDir, "meetings/$id/meeting.json").readText()))

    // ================================================================ Export

    @Test
    fun exporter_everyFormat_plainAndAnonymized_bothModes() {
        AppSettings.update {
            it.copy(organization = Fixtures.ORGANIZATION, customTerms = listOf(Fixtures.CUSTOM_TERM))
        }
        val forbidden = listOf(Fixtures.CLIENT_SURNAME, Fixtures.PHONE_TAIL, Fixtures.IBAN_PART, Fixtures.CUSTOM_TERM)
        for (mode in ProtocolMode.entries) {
            val m = Fixtures.richMeeting(mode = mode)
            for (format in ExportFormat.entries) {
                for (anonymized in listOf(false, true)) {
                    val file = Exporter.export(context, m, format, anonymized)
                    val label = "$mode/$format/anonymisiert=$anonymized (${file.name})"
                    assertTrue("$label: Datei fehlt", file.isFile)
                    assertTrue("$label: Datei leer", file.length() > 0)
                    assertEquals("$label: Endung", format.ext, file.extension)
                    assertEquals("$label: Namenszusatz", anonymized, file.name.contains(Exporter.ANONYMIZED_SUFFIX))
                    assertFalse("$label: .tmp liegen geblieben", File(file.parentFile, file.name + ".tmp").exists())
                    if (anonymized) {
                        assertFalse("$label: Dateiname verrät den Namen", file.name.contains(Fixtures.CLIENT_SURNAME))
                    } else {
                        assertTrue("$label: Titel fehlt im Dateinamen", file.name.contains(Fixtures.CLIENT_SURNAME))
                    }

                    val content: String? = when (format) {
                        ExportFormat.PDF -> {
                            Documents.assertValidPdf(file)
                            null  // Text im PDF ist komprimiert/als Glyphen gesetzt – Inhalt über die anderen Formate
                        }
                        ExportFormat.DOCX -> {
                            val docx = Documents.readDocx(file)
                            for (role in ProtocolTemplate.VEREINSSITZUNG.signatures) {
                                assertTrue("$label: Unterschriftsrolle „$role“ fehlt", docx.text.contains(role))
                            }
                            docx.rawXml.values.joinToString("\n")
                        }
                        ExportFormat.MARKDOWN, ExportFormat.TEXT -> file.readText(Charsets.UTF_8)
                    }
                    if (content == null) continue
                    for (role in ProtocolTemplate.VEREINSSITZUNG.signatures) {
                        assertTrue("$label: „$role“ fehlt", content.contains(role))
                    }
                    assertTrue("$label: Einrichtung fehlt", content.contains(Fixtures.ORGANIZATION))
                    assertTrue("$label: Ort fehlt", content.contains("Vereinsheim Nord"))
                    if (anonymized) {
                        forbidden.forEach { assertFalse("$label enthält „$it“", content.contains(it)) }
                        val plain = content.replace("\\", "")
                        assertTrue("$label: Platzhalter ${Redactor.PHONE} fehlt", plain.contains(Redactor.PHONE))
                        assertTrue("$label: Platzhalter ${Redactor.IBAN} fehlt", plain.contains(Redactor.IBAN))
                    } else {
                        // Gegenprobe: ohne Anonymisierung stehen die Angaben drin (sonst wäre der Test wertlos)
                        forbidden.forEach { assertTrue("$label: „$it“ fehlt im Original", content.contains(it)) }
                    }
                }
            }
        }
    }

    @Test
    fun exporter_filesAreShareableViaFileProvider_meetingDataIsNot() {
        val authority = "${context.packageName}.fileprovider"
        val m = Fixtures.richMeeting()
        for (format in ExportFormat.entries) {
            val file = Exporter.export(context, m, format, anonymized = true)
            val uri = FileProvider.getUriForFile(context, authority, file)
            assertEquals("content", uri.scheme)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals("$format: Inhalt über FileProvider weicht ab", file.readBytes(), bytes)
        }
        val pdf = Exporter.export(context, m, ExportFormat.PDF)
        assertEquals("application/pdf",
            context.contentResolver.getType(FileProvider.getUriForFile(context, authority, pdf)))
        // Besprechungsdaten (Audio, JSON) dürfen NIE über den FileProvider erreichbar sein
        MeetingRepository.save(m)
        val json = File(context.filesDir, "meetings/${m.id}/meeting.json")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, json)
        }
    }

    @Test
    fun privacyReport_isValidPdf() {
        MeetingRepository.save(Fixtures.richMeeting())
        val file = PrivacyReport.create(context)
        assertTrue(file.name, file.name.startsWith("Datenschutz-Nachweis-") && file.name.endsWith(".pdf"))
        Documents.assertValidPdf(file)
    }

    // ================================================================ Löschfrist / Alles löschen

    private fun meetingWithAudio(title: String, ageDays: Int, status: MeetingStatus, transcript: Boolean): Meeting {
        val created = MeetingRepository.create(title, status)
        TestEnv.writeWav(File(created.audioFile), TestEnv.tone(1f))
        return MeetingRepository.update(created.id) {
            it.copy(
                createdAt = System.currentTimeMillis() - ageDays * 86_400_000L,
                status = status,
                durationSec = 1f,
                segments = if (transcript) listOf(Segment(0, 0f, 1f, "Hallo zusammen")) else emptyList(),
                speakers = if (transcript) listOf(Speaker(0, "Person 1")) else emptyList(),
            )
        }!!
    }

    @Test
    fun retention_deletesOnlyExpiredProcessedAudio() {
        val old = meetingWithAudio("alt fertig", 40, MeetingStatus.DONE, transcript = true)
        val fresh = meetingWithAudio("neu fertig", 5, MeetingStatus.DONE, transcript = true)
        val unprocessed = meetingWithAudio("alt unverarbeitet", 40, MeetingStatus.RECORDED, transcript = false)
        val noTranscript = meetingWithAudio("alt ohne Transkript", 40, MeetingStatus.DONE, transcript = false)
        val all = listOf(old, fresh, unprocessed, noTranscript)

        AppSettings.update { it.copy(audioRetentionDays = 0) }
        assertEquals("Frist 0 = nie löschen", 0, Retention.apply(context))
        all.forEach { assertTrue("${it.title}: Audio fehlt", File(it.audioFile).exists()) }

        AppSettings.update { it.copy(audioRetentionDays = 30) }
        assertEquals(1, Retention.apply(context))
        assertFalse("altes Audio nicht gelöscht", File(old.audioFile).exists())
        val after = MeetingRepository.get(old.id)!!
        assertTrue(after.audioDeleted)
        assertEquals("Transkript muss bleiben", 1, after.segments.size)
        assertTrue("audioDeleted nicht gespeichert", fromDisk(old.id).audioDeleted)
        for (m in listOf(fresh, unprocessed, noTranscript)) {
            assertTrue("${m.title}: Audio wurde fälschlich gelöscht", File(m.audioFile).exists())
            assertFalse(m.title, MeetingRepository.get(m.id)!!.audioDeleted)
        }
        assertEquals("zweiter Lauf ist idempotent", 0, Retention.apply(context))
    }

    @Test
    fun retention_deleteAll_removesMeetingsAndVoiceProfiles_keepsSettingsAndModels() {
        meetingWithAudio("Eins", 1, MeetingStatus.DONE, transcript = true)
        meetingWithAudio("Zwei", 2, MeetingStatus.RECORDED, transcript = false)
        val busy = meetingWithAudio("Läuft gerade", 0, MeetingStatus.TRANSCRIBING, transcript = false)
        Exporter.export(context, Fixtures.richMeeting(), ExportFormat.TEXT)
        // Stimmprofil ohne Modell anlegen (interne Methode, nur Zahlenvektor)
        val add = VoiceProfiles::class.java.getDeclaredMethod("add", String::class.java, FloatArray::class.java)
            .apply { isAccessible = true }
        add.invoke(VoiceProfiles, "Testperson", FloatArray(256) { (it % 7) + 1f })
        assertEquals(1, VoiceProfiles.profiles.value.size)
        AppSettings.update { it.copy(organization = Fixtures.ORGANIZATION, vocabulary = listOf("Kolbenfresser")) }
        val models = ModelManager.modelsDir.listFiles().orEmpty().map { it.name to it.length() }.toSet()

        assertEquals("laufende Verarbeitung darf nicht gelöscht werden", 2, Retention.deleteAll(context))

        assertEquals(listOf(busy.id), MeetingRepository.meetings.value.map { it.id })
        assertEquals(listOf(busy.id), File(context.filesDir, "meetings").list()!!.toList())
        assertFalse("Exporte nicht gelöscht", File(context.cacheDir, "exports").exists())
        assertTrue(VoiceProfiles.profiles.value.isEmpty())
        assertEquals("[]", File(context.filesDir, "voices.json").readText().trim())
        assertEquals(Fixtures.ORGANIZATION, AppSettings.state.value.organization)
        assertEquals(listOf("Kolbenfresser"), AppSettings.state.value.vocabulary)
        assertEquals("Modelle dürfen nicht angerührt werden", models,
            ModelManager.modelsDir.listFiles().orEmpty().map { it.name to it.length() }.toSet())
    }

    // ================================================================ Einstellungen

    @Test
    fun appSettings_roundTripOfAllFields_acrossFreshInit() {
        val keepSet = AppSettings.state.value.modelSetId
        val expected = SettingsState(
            defaultMode = ProtocolMode.WORT,
            autoProcess = false,
            language = "de",
            modelSetId = keepSet,
            onboardingDone = true,
            appLock = true,
            blockScreenshots = true,
            audioRetentionDays = 90,
            customTerms = listOf("Kolbe", "Meier-Lüdenscheidt"),
            trackTasks = false,
            defaultTemplate = ProtocolTemplate.UEBERGABE,
            vocabulary = listOf("SGB XI", "Pflegegrad", "Kolbenfresser"),
            organization = Fixtures.ORGANIZATION,
        )
        try {
            AppSettings.update { expected }
            assertEquals(expected, AppSettings.state.value)
            AppSettings.init(context)   // liest alles neu aus den SharedPreferences
            assertEquals(expected, AppSettings.state.value)
            val xml = File(context.applicationInfo.dataDir, "shared_prefs/settings.xml")
            TestEnv.waitUntil("Einstellungen auf der Platte", 10_000, describe = { xml.takeIf { it.exists() }?.readText().orEmpty() }) {
                xml.exists() && xml.readText().let { it.contains("Kolbenfresser") && it.contains("UEBERGABE") }
            }

            val defaults = SettingsState(onboardingDone = true, modelSetId = keepSet)
            AppSettings.update { defaults }
            AppSettings.init(context)
            assertEquals("Zurücksetzen auf Standard überlebt Neustart nicht", defaults, AppSettings.state.value)
        } finally {
            AppSettings.update { it.copy(appLock = false, blockScreenshots = false) }
        }
    }

    @Test
    fun appLock_isAvailableDoesNotThrow_andIsUnlockedWhenOff() {
        val available = AppLock.isAvailable(context)
        TestEnv.log("AppLock.isAvailable = $available")
        try {
            AppSettings.update { it.copy(appLock = true) }
            AppLock.lockNow()
            assertTrue("lockNow wirkt bei eingeschalteter Sperre", AppLock.locked.value)
        } finally {
            AppSettings.update { it.copy(appLock = false) }
        }
        AppLock.onAppForegrounded()
        assertFalse("Sperre aus → entsperrt", AppLock.locked.value)
        AppLock.lockNow()
        assertFalse("lockNow ohne Sperre darf nicht sperren", AppLock.locked.value)
        AppLock.onAppBackgrounded()
        AppLock.onAppForegrounded()
        assertFalse(AppLock.locked.value)
    }

    // ================================================================ MeetingRepository

    @Test
    fun meetingRepository_saveUpdateDelete_persistsJsonWithAndroidOrgJson() {
        val m = Fixtures.richMeeting()
        MeetingRepository.save(m)
        assertEquals(m, MeetingRepository.get(m.id))
        assertEquals("JSON-Rundreise (Android-org.json)", m, fromDisk(m.id))

        // Sonderzeichen, Anführungszeichen, Emoji, Zeilenumbruch, null-Felder
        val tricky = m.copy(
            id = UUID.randomUUID().toString(),
            title = "Sitzung „Ä/Ö/Ü“ \"zitiert\" \\ \n😀 <tag> &amp;",
            error = null, importedFrom = null,
            createdAt = m.createdAt - 1_000,
        )
        MeetingRepository.save(tricky)
        assertEquals(tricky, fromDisk(tricky.id))
        assertEquals("neueste zuerst", listOf(m.id, tricky.id), MeetingRepository.meetings.value.map { it.id })

        // Fortschritt nur im Speicher
        MeetingRepository.update(m.id, persist = false) {
            it.copy(status = MeetingStatus.TRANSCRIBING, progress = 0.5f, progressText = "Transkribiere … 50 %")
        }
        assertEquals(0.5f, MeetingRepository.get(m.id)!!.progress)
        assertEquals("persist=false darf nicht schreiben", MeetingStatus.DONE, fromDisk(m.id).status)

        MeetingRepository.update(m.id) { it.copy(title = "Neuer Titel", status = MeetingStatus.DONE) }
        assertEquals("Neuer Titel", fromDisk(m.id).title)
        assertNull(MeetingRepository.update("gibt-es-nicht") { it })

        MeetingRepository.delete(m.id)
        assertNull(MeetingRepository.get(m.id))
        assertFalse("Ordner nicht gelöscht", File(context.filesDir, "meetings/${m.id}").exists())
        assertEquals(listOf(tricky.id), MeetingRepository.meetings.value.map { it.id })
    }

    @Test
    fun meetingRepository_concurrentUpdates_areAtomic() {
        val m = MeetingRepository.create("Parallel", MeetingStatus.RECORDED)
        val threads = (1..8).map {
            Thread { repeat(50) { MeetingRepository.update(m.id) { it.copy(durationSec = it.durationSec + 1f) } } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        assertEquals(400f, MeetingRepository.get(m.id)!!.durationSec)
        assertEquals(400f, fromDisk(m.id).durationSec)
    }

    @Test
    fun meetingRepository_initRepairsInterruptedRecording() {
        val root = File(context.filesDir, "meetings")
        fun place(m: Meeting) {
            File(root, m.id).mkdirs()
            File(root, "${m.id}/meeting.json").writeText(m.toJson().toString())
        }
        fun base(title: String, status: MeetingStatus): Meeting {
            val id = UUID.randomUUID().toString()
            return Meeting(id = id, title = title, createdAt = System.currentTimeMillis(),
                audioFile = File(root, "$id/audio.wav").absolutePath, status = status)
        }
        // Absturz während der Aufnahme: Header noch mit Größe 0 (wie WavWriter beim Start), 3 s Audio
        val crashed = base("Abgestürzte Aufnahme", MeetingStatus.RECORDING)
        place(crashed)
        TestEnv.writeWav(File(crashed.audioFile), TestEnv.tone(3f), dataSizeInHeader = 0)
        // Absturz direkt nach dem Start: praktisch kein Audio
        val tiny = base("Zu kurz", MeetingStatus.RECORDING)
        place(tiny)
        TestEnv.writeWav(File(tiny.audioFile), TestEnv.tone(0.01f), dataSizeInHeader = 0)
        // Absturz während der Verarbeitung
        val busy = base("Hing in Verarbeitung", MeetingStatus.TRANSCRIBING)
            .copy(segments = listOf(Segment(0, 0f, 1f, "Hallo")))
        place(busy)
        TestEnv.writeWav(File(busy.audioFile), TestEnv.tone(2f))
        // Absturz bei der Neuerstellung eines fertigen Protokolls
        val regen = base("Neuerstellung unterbrochen", MeetingStatus.SUMMARIZING)
            .copy(segments = listOf(Segment(0, 0f, 1f, "Hallo")), summary = Summary(overview = "Alt"))
        place(regen)
        TestEnv.writeWav(File(regen.audioFile), TestEnv.tone(2f))
        // Absturz beim Speichern: nur die vollständige Hilfsdatei liegt vor
        val half = base("Nur Hilfsdatei", MeetingStatus.DONE)
        File(root, half.id).mkdirs()
        File(root, "${half.id}/meeting.json.tmp").writeText(half.toJson().toString())
        // Kaputte Datei darf das Laden nicht verhindern
        File(root, "kaputt").mkdirs()
        File(root, "kaputt/meeting.json").writeText("{ das ist kein JSON")

        TestEnv.reloadMeetingRepository()

        assertEquals(5, MeetingRepository.meetings.value.size)
        val repaired = MeetingRepository.get(crashed.id)!!
        assertEquals(MeetingStatus.RECORDED, repaired.status)
        assertNull(repaired.error)
        assertEquals(3f, repaired.durationSec, 0.01f)
        val wav = File(crashed.audioFile)
        assertEquals("data-Größe im Header", 96_000L, TestEnv.readUInt32(wav, 40))
        assertEquals("RIFF-Größe im Header", 36L + 96_000L, TestEnv.readUInt32(wav, 4))
        assertEquals(48_000, WavIO.readMono16k(wav.absolutePath).size)

        val lost = MeetingRepository.get(tiny.id)!!
        assertEquals(MeetingStatus.ERROR, lost.status)
        assertEquals("Aufnahme wurde unterbrochen.", lost.error)

        // Keine automatische Wiederaufnahme (Endlosschleife bei wiederholbarem Absturz),
        // sondern verständlich als fehlgeschlagen; das Transkript bleibt für "Erneut versuchen"
        val reset = MeetingRepository.get(busy.id)!!
        assertEquals(MeetingStatus.ERROR, reset.status)
        assertEquals(MeetingRepository.INTERRUPTED_MESSAGE, reset.error)
        assertEquals(1, reset.segments.size)
        assertFalse(reset.isBusy)
        assertEquals(MeetingStatus.ERROR, fromDisk(busy.id).status)

        val regenerated = MeetingRepository.get(regen.id)!!
        assertEquals(MeetingStatus.DONE, regenerated.status)
        assertEquals("Alt", regenerated.summary.overview)

        assertEquals(MeetingStatus.DONE, MeetingRepository.get(half.id)!!.status)
        assertTrue(File(root, "${half.id}/meeting.json").exists())
    }

    @Test
    fun meetingRepository_saveIsAtomic_noTempFileLeft() {
        val m = MeetingRepository.create("Atomar", MeetingStatus.RECORDED)
        repeat(20) { i -> MeetingRepository.update(m.id) { it.copy(durationSec = i.toFloat()) } }
        val dir = File(context.filesDir, "meetings/${m.id}")
        assertFalse("Hilfsdatei liegt noch", File(dir, "meeting.json.tmp").exists())
        assertEquals(19f, fromDisk(m.id).durationSec)
    }

    // ================================================================ Vorlesen

    @Test
    fun readAloud_speakMeeting_isActiveOrReportsGermanError_andStopResets() {
        val m = Fixtures.richMeeting()
        TestEnv.instrumentation.runOnMainSync { ReadAloud.speakMeeting(context, m) }
        val first = ReadAloud.state.value
        assertTrue("nichts zum Vorlesen erzeugt", first.total > 0)
        assertEquals(m.title, first.title)
        val t0 = SystemClock.elapsedRealtime()
        TestEnv.waitUntil("Sprachausgabe reagiert", 30_000, describe = { ReadAloud.state.value.toString() }) {
            val s = ReadAloud.state.value
            s.error != null || (s.active && SystemClock.elapsedRealtime() - t0 > 4_000)
        }
        val s = ReadAloud.state.value
        val error = s.error
        if (error != null) {
            TestEnv.log("Sprachausgabe nicht verfügbar (erwartbar im Emulator): $error")
            assertFalse(s.active)
            assertTrue("Fehlermeldung nicht deutsch/verständlich: $error",
                listOf("Sprachausgabe", "Stimme", "Vorlesen").any { error.contains(it) })
        } else {
            assertTrue(s.active)
            ReadAloud.pause()
            assertTrue(ReadAloud.state.value.paused)
            ReadAloud.resume()
            assertFalse(ReadAloud.state.value.paused)
            ReadAloud.setSpeed(1.5f)
            assertEquals(1.5f, ReadAloud.state.value.speed)
        }
        ReadAloud.stop()
        assertEquals(ReadAloudState(speed = ReadAloud.state.value.speed), ReadAloud.state.value)
        ReadAloud.setSpeed(1f)
    }

    // ================================================================ JNI-Vertrag sherpa-onnx

    @Test
    fun diarizationCallback_hasExactJniSignature_onArt() {
        // sherpa-onnx sucht per JNI exakt invoke(IIJ)Ljava/lang/Integer; – sonst Absturz der App
        val method = DiarizationCallback::class.java.getMethod(
            "invoke", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        assertEquals(Int::class.javaObjectType, method.returnType)
        assertEquals("weiter = 0", 0, method.invoke(DiarizationCallback { true }, 1, 10, 0L))
        assertEquals("abbrechen = 1", 1, method.invoke(DiarizationCallback { false }, 1, 10, 0L))
        var fraction = -1f
        DiarizationCallback { fraction = it; true }.invoke(5, 10, 0L)
        assertEquals(0.5f, fraction, 1e-6f)
        DiarizationCallback { fraction = it; true }.invoke(0, 0, 0L)
        assertEquals("total = 0 darf nicht durch 0 teilen", 0f, fraction)
    }

    // ================================================================ Import alter Protokolle

    private fun importFile(name: String, checkRecognized: Boolean = true, write: (File) -> Unit): Meeting {
        val dir = File(context.cacheDir, "import-test").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, name)
        write(f)
        val uri = Uri.fromFile(f)
        if (checkRecognized) {
            assertTrue("$name wird nicht als Dokument erkannt", ProtocolImporter.isDocument(context, uri))
        }
        return runBlocking { ProtocolImporter.import(context, uri, startProcessing = false) }
    }

    private fun assertImported(m: Meeting, fileName: String, date: LocalDate) {
        assertEquals(fileName, m.importedFrom)
        assertEquals(MeetingStatus.RECORDED, m.status)
        assertEquals("", m.audioFile)
        assertTrue(m.audioDeleted)
        assertEquals(listOf(Speaker(0, DocumentText.SPEAKER_NAME)), m.speakers)
        assertTrue("keine Absätze", m.segments.size >= 3)
        assertTrue(m.segments.all { it.speakerId == 0 && it.text.isNotBlank() && !it.text.contains('�') })
        assertEquals("Sitzungsdatum", date,
            Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault()).toLocalDate())
        assertEquals(m, MeetingRepository.get(m.id))
        assertEquals(m, fromDisk(m.id))
        val hits = MeetingSearch.search(MeetingRepository.meetings.value, "Angebote")
        assertTrue("Import nicht sofort durchsuchbar", hits.any { it.meetingId == m.id })
    }

    private fun Meeting.fullText() = segments.joinToString("\n") { it.text }

    @Test
    fun importer_pdfCreatedWithPdfDocument() {
        val m = importFile("protokoll-test.pdf") { f ->
            Documents.writePdf(f, listOf(
                "Protokoll der Teamsitzung",
                "Datum: 12.03.2025",
                "Anwesend: Herr Wagner, Frau Becker, Herr Okafor",
                "TOP 1 Telefonanlage",
                "Die neue Telefonanlage wird bis Ende Januar installiert.",
                "- Frau Becker holt bis Freitag drei Angebote ein.",
                "- Herr Wagner teilt die Tour Nord ab März auf.",
            ))
        }
        assertImported(m, "protokoll-test.pdf", LocalDate.of(2025, 3, 12))
        assertEquals("Protokoll der Teamsitzung", m.title)
        val text = m.fullText()
        assertTrue(text, text.contains("drei Angebote"))
        assertTrue(text, text.contains("Telefonanlage"))
        assertTrue("Umlaut aus PDF verloren: $text", text.contains("März"))
    }

    @Test
    fun importer_docx() {
        val m = importFile("sitzung.docx") { f ->
            Documents.writeDocx(f, listOf(
                "Sitzung Förderverein & Freunde",
                "Datum: 05.11.2024",
                "Anwesend: Frau Becker, Herr Wagner",
                "Die Größe des Lagers reicht nicht mehr aus.",
                "- Frau Becker prüft bis 15.11.2024 die Angebote für ein neues Regal.",
                "- Herr Wagner übernimmt die Tour Nord ab März.",
            ))
        }
        assertImported(m, "sitzung.docx", LocalDate.of(2024, 11, 5))
        assertEquals("Sitzung Förderverein & Freunde", m.title)
        assertTrue(m.fullText().contains("Die Größe des Lagers reicht nicht mehr aus."))
    }

    @Test
    fun importer_textInWindows1252() {
        val text = "Übergabeprotokoll Wohngruppe\r\n\r\nDatum: 01.02.2025\r\n\r\n" +
            "Herr Wagner übernimmt die Dienstplanung für März.\r\n" +
            "Frau Becker klärt die Größe des Raums und holt Angebote ein.\r\n"
        val bytes = text.toByteArray(charset("windows-1252"))
        assertTrue("Testdaten sollen KEIN gültiges UTF-8 sein", bytes.toString(Charsets.UTF_8).contains('�'))
        val m = importFile("altes-protokoll.txt") { it.writeBytes(bytes) }
        assertImported(m, "altes-protokoll.txt", LocalDate.of(2025, 2, 1))
        assertEquals("Übergabeprotokoll Wohngruppe", m.title)
        val full = m.fullText()
        assertTrue(full, full.contains("März") && full.contains("Größe") && full.contains("übernimmt"))
    }

    @Test
    fun importer_rejectsOldDocAndEmptyFiles_withGermanMessage_andCreatesNothing() {
        val doc = runCatching { importFile("uralt.doc", checkRecognized = false) { it.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) } }
            .exceptionOrNull()
        assertTrue("erwartet ProtocolImportException, war $doc", doc is ProtocolImportException)
        assertTrue(doc!!.message!!, doc.message!!.contains(".docx"))

        val empty = runCatching { importFile("leer.txt") { it.writeText("   \n\n  ") } }.exceptionOrNull()
        assertTrue("erwartet ProtocolImportException, war $empty", empty is ProtocolImportException)
        assertTrue(empty!!.message!!, empty.message!!.contains("kein Text"))

        assertTrue("fehlgeschlagener Import hat Besprechungen angelegt", MeetingRepository.meetings.value.isEmpty())
    }

    // ================================================================ Audio-Import (MediaCodec)

    @Test
    fun audioImporter_decodesMeetingWavThroughMediaCodec_losslessly() {
        val src = TestEnv.meetingWav()
        val m = runBlocking { importAudio(context, Uri.fromFile(src)) }
        assertEquals(MeetingStatus.RECORDED, m.status)
        assertEquals(src.nameWithoutExtension, m.title)
        assertEquals(69f, m.durationSec, 1.5f)
        assertEquals(m, fromDisk(m.id))
        val a = WavIO.readMono16k(src.absolutePath)
        val b = WavIO.readMono16k(m.audioFile)
        assertEquals("Länge nach Import", a.size.toFloat(), b.size.toFloat(), 16_000 * 0.05f)
        val n = minOf(a.size, b.size)
        var dot = 0.0; var ea = 0.0; var eb = 0.0
        for (i in 0 until n) { dot += a[i] * b[i]; ea += a[i] * a[i]; eb += b[i] * b[i] }
        assertTrue("Import ist still", eb > 1.0)
        val corr = dot / sqrt(ea * eb)
        assertTrue("Import weicht vom Original ab (Korrelation $corr)", corr > 0.95)
        assertTrue("Lautstärke verändert", abs(sqrt(ea / n) - sqrt(eb / n)) / sqrt(ea / n) < 0.05)
    }

    @Test
    fun audioImporter_rejectsNonAudio_withGermanMessage_andCreatesNothing() {
        val f = File(context.cacheDir, "kein-audio.mp3").apply { writeText("Das ist keine Audiodatei.") }
        val e = runCatching { runBlocking { importAudio(context, Uri.fromFile(f)) } }.exceptionOrNull()
        assertTrue("erwartet AudioImportException, war $e", e is AudioImportException)
        assertTrue(e!!.message!!, e.message!!.contains("Datei") || e.message!!.contains("Ton"))
        assertTrue(MeetingRepository.meetings.value.isEmpty())
        assertTrue("temporäre Importdatei liegen geblieben",
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("import-") && it.name.endsWith(".wav") })
    }

    // ================================================================ Ohne Modelle: verständliche Hinweise

    @Test
    fun askEngine_withoutModels_showsHitsImmediately_andGermanHints() {
        ModelManager.selectSet(TestEnv.notInstalledSet().id)
        val m = Fixtures.processedMeetingLikeWav()
        MeetingRepository.save(m)

        val q = "Was wurde zur Tour Nord beschlossen?"
        AskEngine.ask(context, q)
        val s = AskEngine.state.value
        assertEquals(q, s.question)
        assertTrue("keine Sofort-Treffer", s.hits.isNotEmpty())
        assertTrue(s.hits.any { it.text.contains("Tour Nord") })
        s.hits.forEach { assertTrue("ungültiger Beleg ${it.segment}", it.segment == -1 || it.segment in m.segments.indices) }
        assertFalse("ohne Modelle darf keine KI-Antwort starten", s.answering)
        assertEquals("Für KI-Antworten bitte zuerst die KI-Modelle herunterladen.", s.error)

        AskEngine.ask(context, "Quantenchromodynamik Raumschiff")
        assertTrue(AskEngine.state.value.hits.isEmpty())
        assertEquals(AskPrompt.NOTHING_FOUND, AskEngine.state.value.answer)
        assertNull(AskEngine.state.value.error)

        MeetingRepository.save(Fixtures.processedMeetingLikeWav().copy(status = MeetingStatus.SUMMARIZING))
        AskEngine.ask(context, q)
        assertEquals(AskEngine.BUSY_MESSAGE, AskEngine.state.value.error)
        assertFalse(AskEngine.state.value.answering)

        AskEngine.ask(context, "   ")
        assertEquals(AskState(), AskEngine.state.value)
    }

    @Test
    fun powerStatus_readsBattery_andWarnsOnlyWhenLowWithoutCharger() {
        val b = PowerStatus.battery(context)
        assertNotNull("Akkustand nicht lesbar", b)
        assertTrue("Akkustand ${b!!.percent} %", b.percent in 0..100)
        val w = PowerStatus.warning(context)
        if (b.plugged || b.percent >= PowerStatus.LOW_BATTERY_PERCENT) {
            assertTrue("Warnung trotz Ladegerät/genug Akku: $w", w == null || w.startsWith("Energiesparmodus"))
        } else {
            assertTrue("keine Akku-Warnung bei ${b.percent} %: $w", w != null && w.contains("Ladegerät"))
        }
    }

    @Test
    fun processingService_withoutModels_failsWithGermanMessage_andStops() {
        ModelManager.selectSet(TestEnv.notInstalledSet().id)
        val m = MeetingRepository.create("Ohne Modelle", MeetingStatus.RECORDED)
        TestEnv.installMeetingAudio(m)
        val run = TestEnv.withAppInForeground {
            ProcessingService.process(context, m.id)
            TestEnv.awaitProcessing(m.id, timeoutMs = 60_000)
        }
        assertEquals(MeetingStatus.ERROR, run.meeting.status)
        assertNotNull(run.meeting.error)
        assertTrue(run.meeting.error!!, run.meeting.error!!.contains("heruntergeladen"))
        assertEquals("Fehler muss gespeichert sein", MeetingStatus.ERROR, fromDisk(m.id).status)
    }
}
