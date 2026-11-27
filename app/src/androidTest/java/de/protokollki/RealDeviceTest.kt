package de.protokollki

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.protokollki.audio.importAudio
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.engine.AskEngine
import de.protokollki.engine.ModelCatalog
import de.protokollki.engine.ModelManager
import de.protokollki.engine.ModelSet
import de.protokollki.export.ExportFormat
import de.protokollki.export.Exporter
import de.protokollki.service.PowerStatus
import de.protokollki.service.ProcessingService
import de.protokollki.testutil.Documents
import de.protokollki.testutil.TestEnv
import de.protokollki.testutil.TestEnv.context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Kompletter Ablauf auf einem ECHTEN Handy (Firebase Test Lab, .github/workflows/real-devices.yml):
 *
 *  1. Modelle so laden, wie die App es tut (Internet nur hier), Standard: das Paket, das die App
 *     für dieses Handy empfiehlt; mit `-e modelSet premium` ein bestimmtes Paket.
 *  2. meeting.wav (69 s, 3 Personen) bis zum fertigen Protokoll verarbeiten, PDF und Word exportieren.
 *  3. Lange Besprechung (lang.wav, 4,7 min) – prüft den Speicher beim Schreiben des Protokolls.
 *
 * Jede wichtige Messung wird als Zeile „PKREPORT schlüssel=wert“ ins Systemprotokoll geschrieben;
 * der Workflow liest sie aus und baut daraus den Bericht. Die Tests laufen in Namensreihenfolge
 * im selben Prozess, die Modelle aus Schritt 1 bleiben also für 2 und 3 liegen.
 *
 * Auf dem Emulator wird diese Klasse nicht gestartet (instrumented.sh wählt Klassen explizit).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealDeviceTest {

    private fun report(key: String, value: Any?) = TestEnv.log("PKREPORT $key=$value")

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    /** Gewünschtes Paket: `-e modelSet <id>` oder die Empfehlung der App für dieses Handy. */
    private fun wantedSet(): ModelSet {
        val parts = arg("modelSet")?.split('+').orEmpty()
        // "premium+schnell": Schnellmodus (umgepackte Gewichte), "premium+gpu": Grafikchip – zum Messen erzwingen
        val fast = "schnell" in parts
        val gpu = "gpu" in parts
        context.getSharedPreferences("llm_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("force_fast", fast).putBoolean("force_gpu", gpu).commit()
        report("schnell_erzwungen", fast)
        report("gpu_erzwungen", gpu)
        val id = parts.firstOrNull()
        if (id == null || id == "recommended") return ModelCatalog.recommended(context)
        return ModelCatalog.byId(id) ?: error("Unbekanntes Modellpaket „$id“")
    }

    private fun ramInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun mb(bytes: Long) = bytes / 1024 / 1024

    /**
     * Misst während [block] alle 2 s den Speicher der App (PSS, nativer Heap) und den freien
     * RAM des Handys. So sieht man, wie knapp es beim Laden des Sprachmodells wird.
     */
    private fun <T> measured(label: String, block: () -> T): T {
        val running = AtomicBoolean(true)
        val maxPss = AtomicLong(0)
        val maxNative = AtomicLong(0)
        val minAvail = AtomicLong(Long.MAX_VALUE)
        val lowMemorySeen = AtomicBoolean(false)
        // Akku und Wärme: Stand vorher/nachher, höchste Akkutemperatur, höchster Thermal-Status
        val before = PowerStatus.battery(context)
        val capacityBefore = batteryCapacity()
        val maxTempTenths = AtomicLong(Long.MIN_VALUE)
        val maxThermal = AtomicLong(-1)
        val pluggedSeen = AtomicBoolean(before?.plugged == true)
        val sampler = Thread {
            while (running.get()) {
                runCatching {
                    PowerStatus.battery(context)?.let { b ->
                        b.temperatureC?.let { t -> maxTempTenths.accumulateAndGet((t * 10).toLong()) { a, c -> maxOf(a, c) } }
                        if (b.plugged) pluggedSeen.set(true)
                    }
                    thermalStatus()?.let { t -> maxThermal.accumulateAndGet(t.toLong()) { a, c -> maxOf(a, c) } }
                }
                runCatching {
                    val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    maxPss.accumulateAndGet(mi.totalPss * 1024L) { a, b -> maxOf(a, b) }
                    maxNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                    val r = ramInfo()
                    minAvail.accumulateAndGet(r.availMem) { a, b -> minOf(a, b) }
                    if (r.lowMemory) lowMemorySeen.set(true)
                }
                Thread.sleep(2_000)
            }
        }.apply { isDaemon = true; start() }
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            running.set(false)
            sampler.join(5_000)
            report("$label.sekunden", (SystemClock.elapsedRealtime() - t0) / 1000)
            report("$label.max_app_speicher_mb", mb(maxPss.get()))
            report("$label.max_nativ_mb", mb(maxNative.get()))
            report("$label.min_frei_ram_mb", if (minAvail.get() == Long.MAX_VALUE) "?" else mb(minAvail.get()))
            report("$label.speicher_knapp", lowMemorySeen.get())
            val after = PowerStatus.battery(context)
            report("$label.akku_vorher", capacityBefore ?: before?.percent ?: "?")
            report("$label.akku_nachher", batteryCapacity() ?: after?.percent ?: "?")
            report("$label.akku_temp_max", if (maxTempTenths.get() == Long.MIN_VALUE) "?" else "%.1f".format(java.util.Locale.ROOT, maxTempTenths.get() / 10.0))
            report("$label.thermal_max", if (maxThermal.get() < 0) "?" else thermalName(maxThermal.get().toInt()))
            report("$label.laedt", pluggedSeen.get())
        }
    }

    /** Akkustand in Prozent laut BatteryManager (genauer als der Broadcast), null wenn unbekannt. */
    private fun batteryCapacity(): Int? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    /** PowerManager.getCurrentThermalStatus (ab Android 10), sonst null. */
    private fun thermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun thermalName(s: Int): String = when (s) {
        0 -> "0 (normal)"
        1 -> "1 (leicht)"
        2 -> "2 (mittel)"
        3 -> "3 (stark – Drosselung)"
        4 -> "4 (kritisch)"
        5 -> "5 (Notfall)"
        6 -> "6 (Abschaltung)"
        else -> s.toString()
    }

    private fun assetToCache(asset: String): File {
        val out = File(context.cacheDir, "realdevice-" + asset.substringAfterLast('/'))
        if (!out.exists() || out.length() < 1000) {
            TestEnv.testContext.assets.open(asset).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
        }
        return out
    }

    private fun prepareForProcessing(): ModelSet {
        val set = wantedSet()
        if (!ModelManager.isInstalled(set)) fail("Modelle „${set.id}“ fehlen – ist Schritt 1 (Download) gescheitert?")
        TestEnv.awaitNoBusyMeeting()
        TestEnv.resetAppState()
        ModelManager.selectSet(set.id)
        return set
    }

    /** Verarbeitet eine Audiodatei über den echten Weg der App (Import → Dienst → Protokoll). */
    private fun processFile(label: String, wav: File, timeoutMin: Long): Meeting {
        val imported = runBlocking { importAudio(context, Uri.fromFile(wav)) }
        assertEquals(MeetingStatus.RECORDED, imported.status)
        val run = measured(label) {
            TestEnv.withAppInForeground {
                ProcessingService.process(context, imported.id)
                TestEnv.awaitProcessing(imported.id, timeoutMs = timeoutMin * 60_000L)
            }
        }
        val m = run.meeting
        report("$label.status", m.status)
        report("$label.fehler", m.error)
        report("$label.sprecher", m.speakers.size)
        report("$label.beitraege", m.segments.size)
        report("$label.aufgaben", m.tasks.size)
        report("$label.beschluesse", m.summary.decisions.size)
        TestEnv.log("[$label] Überblick: ${m.summary.overview}")
        m.tasks.forEach { TestEnv.log("[$label] Aufgabe: ${it.text} | ${it.owner} | ${it.due}") }
        assertNull("[$label] Fehler: ${m.error}", m.error)
        assertEquals("[$label] Protokoll nicht fertig", MeetingStatus.DONE, m.status)
        assertTrue("[$label] Phase Protokoll nie erreicht: ${run.seenStatuses}",
            MeetingStatus.SUMMARIZING in run.seenStatuses)
        assertTrue("[$label] kein Transkript", m.segments.isNotEmpty())
        assertTrue("[$label] keine Zusammenfassung", m.summary.overview.isNotBlank())
        return m
    }

    // ================================================================ 1. Modelle laden

    @Test
    fun step1_downloadModels() {
        val ram = ramInfo()
        val set = wantedSet()
        report("geraet", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        report("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report("ram_gesamt_gb", "%.1f".format(ram.totalMem / 1024.0 / 1024 / 1024))
        report("ram_frei_mb", mb(ram.availMem))
        report("empfohlen", ModelCatalog.recommended(context).id)
        report("paket", set.id)
        report("speicher_frei_gb", "%.1f".format(ModelManager.freeBytes() / 1e9))

        ModelManager.selectSet(set.id)
        if (ModelManager.isInstalled(set)) {
            report("download", "schon vorhanden")
            return
        }
        measured("download") {
            runBlocking {
                // Wie der Download-Dienst der App; bei Netzaussetzern bis zu 3 Anläufe
                // (bereits fertige Dateien bleiben erhalten).
                repeat(3) { attempt ->
                    if (ModelManager.isInstalled(set)) return@runBlocking
                    TestEnv.log("Download-Anlauf ${attempt + 1}: ${"%.2f".format(ModelManager.missingBytes(set) / 1e9)} GB fehlen")
                    ModelManager.downloadAll()
                    ModelManager.state.value.error?.let { TestEnv.log("Download-Fehler: $it") }
                }
            }
        }
        val st = ModelManager.state.value
        assertTrue("Modelle „${set.id}“ nicht vollständig geladen: ${st.error}", ModelManager.isInstalled(set))
        report("download", "ok")
    }

    // ================================================================ 2. Kurze Besprechung

    @Test
    fun step2_meetingWav_toFinishedProtocol_andExport() {
        prepareForProcessing()
        val m = processFile("kurz", TestEnv.meetingWav(), timeoutMin = 15)
        assertTrue("${m.speakers.size} Sprecher, erwartet 2–4", m.speakers.size in 2..4)
        assertTrue("keine Aufgaben erkannt", m.tasks.isNotEmpty())
        val pdf = Exporter.export(context, m, ExportFormat.PDF)
        Documents.assertValidPdf(pdf)
        Documents.readDocx(Exporter.export(context, m, ExportFormat.DOCX))
        report("kurz.export", "PDF ${pdf.length() / 1024} KB + Word ok")
        AskEngine.releaseNow()
    }

    // ================================================================ 3. Lange Besprechung

    @Test
    fun step3_longMeeting_toFinishedProtocol() {
        prepareForProcessing()
        val m = processFile("lang", assetToCache("scenarios/out/lang.wav"), timeoutMin = 25)
        assertTrue("keine Aufgaben in der langen Besprechung", m.tasks.isNotEmpty())
        AskEngine.releaseNow()
        TestEnv.resetAppState()
    }
}
