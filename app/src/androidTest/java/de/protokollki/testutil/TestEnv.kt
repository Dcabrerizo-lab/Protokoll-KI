package de.protokollki.testutil

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.protokollki.MainActivity
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.SettingsState
import de.protokollki.engine.AskEngine
import de.protokollki.engine.ModelCatalog
import de.protokollki.engine.ModelManager
import de.protokollki.engine.ModelSet
import de.protokollki.engine.ModelState
import de.protokollki.engine.VoiceProfiles
import de.protokollki.tts.ReadAloud
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemeinsame Werkzeuge der instrumentierten Tests. Alle Tests laufen im App-Prozess
 * (ProtokollApp hat AppSettings, MeetingRepository, ModelManager und VoiceProfiles bereits
 * initialisiert); jeder Test räumt mit [resetAppState] vorher und nachher auf.
 */
object TestEnv {
    const val TAG = "ProtokollKITest"
    const val KOMPAKT = "kompakt"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Kontext der App (filesDir, cacheDir, SharedPreferences der App). */
    val context: Context get() = instrumentation.targetContext

    /** Kontext der Test-APK (Assets: meeting.wav). */
    val testContext: Context get() = instrumentation.context

    fun log(msg: String) {
        Log.i(TAG, msg)
    }

    // ------------------------------------------------------------------ Zustand

    /**
     * Setzt die App auf einen sauberen Stand: keine Besprechungen, keine Exporte, keine
     * Stimmprofile, Standard-Einstellungen (Einrichtung erledigt). Die Modellauswahl bleibt,
     * heruntergeladene Modelle werden nie angerührt.
     */
    fun resetAppState() {
        runCatching { ReadAloud.stop() }
        runCatching { AskEngine.clear() }
        MeetingRepository.meetings.value.forEach { MeetingRepository.delete(it.id) }
        File(context.filesDir, "meetings").listFiles()?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "exports").deleteRecursively()
        runCatching { VoiceProfiles.deleteAll() }
        val modelSetId = AppSettings.state.value.modelSetId
        AppSettings.update {
            SettingsState(onboardingDone = true, autoProcess = false, modelSetId = modelSetId)
        }
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Wartet, bis keine Besprechung mehr verarbeitet wird (z. B. nach einem abgebrochenen Test). */
    fun awaitNoBusyMeeting(timeoutMs: Long = 180_000) {
        waitUntil("keine laufende Verarbeitung", timeoutMs, pollMs = 500,
            describe = { MeetingRepository.meetings.value.joinToString { "${it.title}=${it.status}" } }) {
            MeetingRepository.meetings.value.none { it.isBusy }
        }
    }

    // ------------------------------------------------------------------ Warten

    /**
     * Pollt [condition], bis sie wahr ist. Scheitert mit einer aussagekräftigen Meldung
     * (inkl. [describe] = aktueller Zustand), statt still zu hängen.
     */
    fun waitUntil(
        what: String,
        timeoutMs: Long,
        pollMs: Long = 250,
        describe: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (condition()) return
            if (SystemClock.elapsedRealtime() - start > timeoutMs) {
                fail("Zeitüberschreitung nach ${timeoutMs / 1000} s beim Warten auf: $what. Zustand: ${describe()}")
            }
            Thread.sleep(pollMs)
        }
    }

    /** Ergebnis von [awaitProcessing]. */
    class ProcessingRun(val meeting: Meeting, val seenStatuses: Set<MeetingStatus>, val seconds: Long)

    /**
     * Wartet auf das Ende der Verarbeitung von [id]: erst bis die Besprechung den Ausgangszustand
     * [from] verlassen hat (der Service setzt QUEUED asynchron), dann bis sie nicht mehr
     * beschäftigt ist. Protokolliert alle beobachteten Zustände und prüft unterwegs den Fortschritt.
     */
    fun awaitProcessing(
        id: String,
        timeoutMs: Long,
        from: MeetingStatus = MeetingStatus.RECORDED,
    ): ProcessingRun {
        val start = SystemClock.elapsedRealtime()
        val seen = linkedSetOf<MeetingStatus>()
        var lastText = ""
        fun current(): Meeting =
            MeetingRepository.get(id) ?: throw AssertionError("Besprechung $id ist verschwunden")
        waitUntil("Verarbeitung von $id startet", 60_000, describe = { current().status.name }) {
            val m = current()
            m.status != from || m.isBusy
        }
        waitUntil("Verarbeitung von $id endet", timeoutMs, pollMs = 1_000,
            describe = { current().let { "${it.status} ${it.progress} „${it.progressText}“ error=${it.error}" } }) {
            val m = current()
            seen += m.status
            if (m.progress !in 0f..1f) fail("Fortschritt außerhalb 0..1: ${m.progress}")
            if (m.progressText != lastText && m.progressText.isNotBlank()) {
                lastText = m.progressText
                log("[$id] ${m.status} ${(m.progress * 100).toInt()} % – ${m.progressText}")
            }
            !m.isBusy
        }
        val m = current()
        seen += m.status
        val secs = (SystemClock.elapsedRealtime() - start) / 1000
        log("[$id] fertig nach $secs s: ${m.status}, Zustände=$seen, error=${m.error}")
        return ProcessingRun(m, seen, secs)
    }

    // ------------------------------------------------------------------ Modelle

    private fun modelsRequired(): Boolean =
        InstrumentationRegistry.getArguments().getString("requireModels").equals("true", ignoreCase = true)

    /**
     * Wählt das Paket „Kompakt“. Fehlen dessen Dateien in filesDir/models, wird der Test
     * übersprungen (oder schlägt mit `-e requireModels true` fehl).
     */
    fun requireKompaktModels(): ModelSet {
        val set = ModelCatalog.byId(KOMPAKT) ?: error("Paket $KOMPAKT fehlt im Katalog")
        val installed = ModelManager.isInstalled(set)
        val msg = "KI-Modelle „$KOMPAKT“ fehlen in ${ModelManager.modelsDir.absolutePath} " +
            "(vorhanden: ${ModelManager.modelsDir.list()?.sorted()})"
        if (modelsRequired() && !installed) fail(msg)
        assumeTrue(msg, installed)
        ModelManager.selectSet(KOMPAKT)
        check(ModelManager.state.value.installed) { "ModelManager meldet Kompakt nicht als installiert" }
        return set
    }

    /** Ein Modellpaket, dessen Dateien NICHT vorhanden sind (für Tests ohne KI). */
    fun notInstalledSet(): ModelSet {
        val set = ModelCatalog.sets.firstOrNull { !ModelManager.isInstalled(it) }
        assumeTrue("Alle Modellpakete sind installiert – kein Paket ohne Modelle verfügbar", set != null)
        return set!!
    }

    /**
     * Setzt nur das Anzeige-Flag „installiert“ im ModelManager (per Reflection), damit die
     * Oberfläche ohne echte Modelle die Startseite statt der Einrichtung zeigt. Die Dateiprüfung
     * [ModelManager.isInstalled] bleibt unberührt – es wird also nie ein Modell geladen.
     */
    fun forceModelsInstalledFlag(installed: Boolean) {
        val f = ModelManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(ModelManager) as MutableStateFlow<ModelState>
        flow.value = flow.value.copy(installed = installed)
    }

    // ------------------------------------------------------------------ Repository

    /**
     * Lädt das MeetingRepository neu von der Platte, als wäre die App neu gestartet
     * (init() ist sonst nur einmal pro Prozess wirksam).
     */
    fun reloadMeetingRepository() {
        val f = MeetingRepository::class.java.getDeclaredField("root").apply { isAccessible = true }
        f.set(null, null)
        MeetingRepository.init(context)
    }

    // ------------------------------------------------------------------ Audio

    /** meeting.wav (69 s, 16 kHz mono, 3 Sprecher) als Datei im Cache der App. */
    fun meetingWav(): File {
        val out = File(context.cacheDir, "test-meeting.wav")
        if (out.exists() && out.length() > 1_000_000) return out
        val fromAssets = runCatching {
            testContext.assets.open("meeting.wav").use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            true
        }.getOrDefault(false)
        if (!fromAssets) {
            // Fallback: von der CI nach filesDir/test.wav kopiert
            val pushed = File(context.filesDir, "test.wav")
            assumeTrue("meeting.wav weder als Test-Asset noch unter ${pushed.absolutePath}", pushed.exists())
            pushed.copyTo(out, overwrite = true)
        }
        return out
    }

    /** Kopiert meeting.wav als audio.wav in den Ordner der Besprechung [m]. */
    fun installMeetingAudio(m: Meeting) {
        val target = File(m.audioFile)
        target.parentFile?.mkdirs()
        meetingWav().copyTo(target, overwrite = true)
    }

    /** Schreibt ein 16-kHz-Mono-PCM16-WAV. [dataSizeInHeader] = null → korrekte Größen. */
    fun writeWav(file: File, samples: ShortArray, dataSizeInHeader: Int? = null) {
        val dataBytes = samples.size * 2
        val headerData = dataSizeInHeader ?: dataBytes
        val riff = if (dataSizeInHeader == null) 36 + dataBytes else dataSizeInHeader
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(riff)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16)
        bb.putShort(1); bb.putShort(1); bb.putInt(16_000); bb.putInt(32_000)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(headerData)
        samples.forEach { bb.putShort(it) }
        file.parentFile?.mkdirs()
        file.writeBytes(bb.array())
    }

    /** Sinuston (hörbar, aber keine Sprache) mit [seconds] Dauer. */
    fun tone(seconds: Float, hz: Double = 440.0): ShortArray =
        ShortArray((seconds * 16_000).toInt()) { i ->
            (Math.sin(2 * Math.PI * hz * i / 16_000.0) * 8_000).toInt().toShort()
        }

    /** Little-Endian-UInt32 an [offset] der Datei. */
    fun readUInt32(file: File, offset: Int): Long {
        val b = file.readBytes()
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    // ------------------------------------------------------------------ Vordergrund

    /**
     * Führt [block] aus, während MainActivity sichtbar ist. Ab Android 12 dürfen Foreground-Services
     * (ProcessingService) nur aus dem Vordergrund gestartet werden – so wie in der echten App.
     */
    fun <T> withAppInForeground(block: () -> T): T {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            return block()
        } finally {
            runCatching { scenario.close() }
        }
    }
}
