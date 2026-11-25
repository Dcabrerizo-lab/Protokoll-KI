package de.protokollki.engine

import android.content.Context
import android.os.StatFs
import de.protokollki.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelState(
    val set: ModelSet,
    val recommended: ModelSet,
    val installed: Boolean,
    val downloading: Boolean = false,
    val progress: Float = 0f,           // 0..1 über alle Dateien
    val status: String = "",
    val error: String? = null,
    val ramGb: Double = 0.0,
)

/**
 * Lädt die KI-Modelle EINMALIG herunter (der einzige Moment, in dem die App das
 * Internet nutzt). Danach laufen Transkription und Protokoll komplett offline.
 */
object ModelManager {
    private lateinit var dir: File
    private const val MIN_FREE_DURING_DOWNLOAD = 30L * 1024 * 1024
    private lateinit var _state: MutableStateFlow<ModelState>
    val state: StateFlow<ModelState> get() = _state.asStateFlow()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "models").apply { mkdirs() }
        val rec = ModelCatalog.recommended(context)
        val set = ModelCatalog.byId(AppSettings.state.value.modelSetId) ?: rec
        _state = MutableStateFlow(
            ModelState(set, rec, isInstalled(set), ramGb = ModelCatalog.totalRamGb(context))
        )
    }

    val modelsDir: File get() = dir

    fun file(name: String) = File(dir, name)

    private fun filesOf(set: ModelSet) = ModelCatalog.common + set.whisper + set.llm

    private fun isComplete(f: ModelFile): Boolean =
        if (f.extract.isEmpty()) File(dir, f.target).let { it.exists() && it.length() > 0 }
        else f.extract.values.all { File(dir, it).exists() }

    fun isInstalled(set: ModelSet) = filesOf(set).all(::isComplete)

    fun selectSet(id: String?) {
        AppSettings.update { it.copy(modelSetId = id) }
        val set = ModelCatalog.byId(id) ?: _state.value.recommended
        _state.value = _state.value.copy(set = set, installed = isInstalled(set), error = null)
    }

    fun freeBytes(): Long = StatFs(dir.absolutePath).availableBytes

    fun missingBytes(set: ModelSet = _state.value.set): Long =
        filesOf(set).filterNot(::isComplete).sumOf { it.bytes }

    /** Löscht Modelle, die zu keinem gewählten Paket gehören (Speicher freigeben). */
    fun deleteUnused() {
        val keep = filesOf(_state.value.set).flatMap {
            if (it.extract.isEmpty()) listOf(it.target) else it.extract.values.toList()
        }.toSet()
        dir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
        _state.value = _state.value.copy(installed = isInstalled(_state.value.set))
    }

    /** Wird vom [DownloadService] im Hintergrund aufgerufen. */
    suspend fun downloadAll() = withContext(Dispatchers.IO) {
        val set = _state.value.set
        val todo = filesOf(set).filterNot(::isComplete)
        val total = todo.sumOf { it.bytes }.coerceAtLeast(1)
        var doneBefore = 0L
        _state.value = _state.value.copy(downloading = true, error = null, progress = 0f)
        try {
            if (freeBytes() < missingBytes(set) * 1.3) {
                throw IllegalStateException(
                    "Zu wenig Speicher frei. Benötigt werden etwa " +
                        "${"%.1f".format(missingBytes(set) * 1.3 / 1e9)} GB."
                )
            }
            for ((index, f) in todo.withIndex()) {
                val label = "Lade Modell ${index + 1} von ${todo.size} …"
                // Fortschritt dieser Datei (0..1) auf ihren Anteil am Gesamtpaket abbilden
                fun report(fraction: Float, text: String = label) {
                    _state.value = _state.value.copy(
                        progress = ((doneBefore + f.bytes * fraction.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f),
                        status = text,
                    )
                }
                if (f.direct.isNotEmpty() && downloadDirect(f, ::report)) {
                    // fertig, ohne Entpacken
                } else {
                    val tmp = File(dir, f.target + ".part")
                    download(f.url, tmp) { got, size ->
                        // beim Archiv: Laden = 70 %, Entpacken = 30 % des Anteils
                        val frac = got.toFloat() / (if (size > 0) size else f.bytes)
                        report(if (f.extract.isEmpty()) frac else 0.7f * frac)
                    }
                    if (f.extract.isEmpty()) {
                        tmp.renameTo(File(dir, f.target))
                    } else {
                        extractTarBz2(tmp, f.extract) { frac ->
                            report(0.7f + 0.3f * frac, "Entpacke Modell ${index + 1} … ${(frac * 100).toInt()} %")
                        }
                        tmp.delete()
                    }
                }
                doneBefore += f.bytes
            }
            _state.value = _state.value.copy(
                downloading = false, installed = isInstalled(set), progress = 1f,
                status = "Fertig. Die App arbeitet ab jetzt komplett offline.",
            )
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(downloading = false, status = "Abgebrochen")
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                downloading = false,
                error = if (DiskSpace.isDiskFull(e)) DiskSpace.FULL_MESSAGE else e.message ?: "Download fehlgeschlagen",
                status = "",
            )
        }
    }

    /**
     * Lädt die entpackten Einzeldateien vom Spiegel. false, wenn der Spiegel nicht erreichbar ist
     * (dann wird das Archiv geladen). Bereits fertige Einzeldateien bleiben erhalten.
     */
    private suspend fun downloadDirect(f: ModelFile, report: (Float, String) -> Unit): Boolean {
        val sizes = LongArray(f.direct.size)
        val got = LongArray(f.direct.size)
        for ((i, name) in f.direct.withIndex()) {
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 0) { got[i] = dest.length(); sizes[i] = dest.length(); continue }
            val tmp = File(dir, "$name.part")
            try {
                download("${ModelCatalog.MIRROR}/$name", tmp, attempts = 4) { g, size ->
                    got[i] = g; if (size > 0) sizes[i] = size
                    // unbekannte Größen grob schätzen, damit der Balken nicht springt
                    val known = sizes.sum().coerceAtLeast(1)
                    report((got.sum().toFloat() / known) * (sizes.count { it > 0 }.toFloat() / sizes.size),
                        "Lade Spracherkennung … ${name.substringAfter('-').substringBefore('.')}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tmp.delete()
                if (DiskSpace.isDiskFull(e)) throw DiskFullException()   // Archiv wäre noch größer
                return false
            }
            if (!tmp.renameTo(dest)) return false
        }
        return f.direct.all { File(dir, it).let { d -> d.exists() && d.length() > 0 } }
    }

    /** HTTP-Download mit Fortsetzen (Range), falls die Verbindung abreißt. onProgress(geladen, Gesamtgröße oder -1). */
    private suspend fun download(url: String, target: File, attempts: Int = 5, onProgress: (Long, Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                var existing = if (target.exists()) target.length() else 0L
                var current = URL(url)
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = (current.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "ProtokollKI")
                        if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 10) {
                        current = URL(current, conn.getHeaderField("Location"))
                        conn.disconnect(); redirects++; continue
                    }
                    break
                }
                val code = conn.responseCode
                if (code == 416) { onProgress(existing, existing); return }  // schon vollständig
                if (code == 404) throw java.io.FileNotFoundException("nicht gefunden: $url")
                if (code !in 200..299) throw IllegalStateException("Server antwortet mit $code")
                if (code == 200) existing = 0L                       // kein Range-Support
                val size = conn.contentLengthLong.let { if (it > 0) it + existing else -1L }
                conn.inputStream.use { input ->
                    FileOutputStream(target, existing > 0).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = existing
                        var lastReport = 0L
                        var nextSpaceCheck = got
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            // Handy nicht randvoll schreiben (Android wird dann instabil): sauber abbrechen
                            if (got >= nextSpaceCheck) {
                                nextSpaceCheck = got + 8_000_000L
                                if (DiskSpace.freeBytes(target.parentFile ?: dir) < MIN_FREE_DURING_DOWNLOAD) throw DiskFullException()
                            }
                            out.write(buf, 0, n)
                            got += n
                            if (got - lastReport > 1_000_000) { onProgress(got, size); lastReport = got }
                        }
                        onProgress(got, size)
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.FileNotFoundException) {
                throw e                                           // erneut versuchen hilft nicht
            } catch (e: Exception) {
                if (DiskSpace.isDiskFull(e)) throw DiskFullException()   // erneut versuchen hilft nicht
                if (++attempt >= attempts) throw IllegalStateException(
                    "Download fehlgeschlagen (${e.message}). Bitte WLAN prüfen und erneut versuchen.")
                kotlinx.coroutines.delay(3000L * attempt)
            }
        }
    }

    private suspend fun extractTarBz2(archive: File, wanted: Map<String, String>, onProgress: (Float) -> Unit = {}) {
        val total = archive.length().coerceAtLeast(1)
        var read = 0L
        var lastReport = 0L
        // Zählt die gelesenen (gepackten) Bytes, damit beim Entpacken ein Fortschritt sichtbar ist
        val counting = object : java.io.FilterInputStream(FileInputStream(archive)) {
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }
            private fun count(n: Long) {
                read += n
                if (read - lastReport > 2_000_000) { lastReport = read; onProgress(read.toFloat() / total) }
            }
        }
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))
        ).use { tar ->
            var found = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val dest = wanted[entry.name.removePrefix("./")] ?: continue
                val tmp = File(dir, "$dest.tmp")
                FileOutputStream(tmp).use { tar.copyTo(it, 1 shl 16) }
                tmp.renameTo(File(dir, dest))
                if (++found == wanted.size) break
            }
            if (found < wanted.size) throw IllegalStateException("Archiv unvollständig")
        }
    }
}
