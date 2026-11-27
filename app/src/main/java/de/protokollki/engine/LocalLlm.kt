package de.protokollki.engine

import android.content.Context

/** Rückmeldung aus dem nativen Code. phase 0 = Text lesen, 1 = Antwort schreiben. */
fun interface LlmProgress {
    fun onProgress(phase: Int, done: Int, total: Int): Boolean
}

/** JNI-Brücke zu llama.cpp (siehe app/src/main/cpp/llm_jni.cpp). */
object LlamaNative {
    @Volatile private var initialized = false

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("protokollki_llm")
            init(context.applicationInfo.nativeLibraryDir)
            initialized = true
        }
    }

    /** Nur für JVM-Tests auf dem PC (Qualitätstest in der CI). */
    fun initForTest(libPath: String) {
        if (initialized) return
        System.load(libPath)
        init("")
        initialized = true
    }

    external fun init(nativeLibDir: String)
    external fun load(modelPath: String, nCtx: Int, nThreads: Int, fast: Boolean): Long
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, text: String): Int
    external fun cancel(handle: Long)
    external fun complete(
        handle: Long, system: String, user: String, maxTokens: Int,
        temperature: Float, grammar: String?, callback: LlmProgress?, cachePrefix: String?,
    ): String?
    /** Ordner für vorberechnete Anweisungsteile (null = aus). */
    external fun setCacheDir(handle: Long, dir: String?)
    external fun free(handle: Long)
}

class LlmException(message: String) : Exception(message)

/** Lokales Sprachmodell. Immer mit [close] freigeben (belegt mehrere GB RAM). */
class LocalLlm(context: Context, private val set: ModelSet) : AutoCloseable {
    private val handle: Long
    private val appContext: Context

    init {
        LlamaNative.ensureInit(context)
        val path = ModelManager.file(set.llm.target).absolutePath
        val ctx = contextFor(context, set, java.io.File(path).length())
        android.util.Log.i("ProtokollKI", "Lade Sprachmodell ${set.id} mit Kontext $ctx")
        // Freigegebene Spracherkennung und Audio wirklich zurückgeben, bevor mehrere GB geladen werden
        System.gc()
        val crashed = crashedLastTime(context)
        val threads = if (crashed) 4 else 0
        val fast = !crashed && fastAllowed(context, java.io.File(path).length())
        android.util.Log.i("ProtokollKI", "Schnellmodus: $fast")
        appContext = context.applicationContext
        markRunning(appContext, true)
        val t0 = System.currentTimeMillis()
        val freeBefore = freeMb(context)
        var h = LlamaNative.load(path, ctx, threads, fast)
        if (h == 0L && fast) h = LlamaNative.load(path, ctx, threads, false)
        if (h == 0L && ctx > 4096) h = LlamaNative.load(path, 4096, threads, false)
        handle = h
        Timing.report("laden", "Sprachmodell ${set.id}, Kontext ${if (h != 0L) LlamaNative.contextSize(h) else 0}, " +
            "schnell=$fast, ${(System.currentTimeMillis() - t0) / 1000.0} s, Modell ${java.io.File(path).length() / 1048576} MB, " +
            "frei vorher $freeBefore MB, nachher ${freeMb(context)} MB")
        if (handle != 0L) runCatching {
            // Vorberechnete Anweisungsteile (je ca. 40–70 MB): nur die vier neuesten behalten
            val dir = java.io.File(context.cacheDir, "llm-prefix").apply { mkdirs() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
            LlamaNative.setCacheDir(h, dir.absolutePath)
        }
        if (handle == 0L) throw LlmException(
            "Das Sprachmodell konnte nicht geladen werden. Eventuell ist zu wenig Arbeitsspeicher frei. " +
                "Bitte andere Apps schließen oder in den Einstellungen ein kleineres Modellpaket wählen.")
    }

    val contextSize: Int get() = LlamaNative.contextSize(handle)

    companion object {
        /**
         * Kontextlänge passend zum freien Arbeitsspeicher. Android beendet Apps ohne Rückfrage, wenn
         * sie zu viel RAM belegen; auf 8-GB-Handys (S23+) war 16k Kontext mit 4B-Modell zu viel.
         * KV-Cache (8 Bit) bei Qwen3 4B/8B: ca. 75 KB je Token.
         */
        private const val PREFS = "llm_guard"

        /**
         * Wächter gegen wiederholte Abstürze: wird vor dem Sprachmodell gesetzt und danach gelöscht.
         * Steht er beim nächsten Mal noch, wurde die App währenddessen beendet (meist Speicher), und
         * die App arbeitet dann sparsamer (4k Kontext, 4 Threads).
         */
        /** Freier Arbeitsspeicher laut System (für die Messung, ob der Schnellmodus passt). */
        fun freeMb(context: Context): Long {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return -1
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return mi.availMem / 1048576
        }

        fun markRunning(context: Context, running: Boolean) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (running) p.edit().putBoolean("running", true).commit()
            else p.edit().putBoolean("running", false).putInt("crashes", 0).apply()
        }

        /** true, wenn der letzte Lauf des Sprachmodells nicht sauber beendet wurde. */
        fun crashedLastTime(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("running", false)

        /**
         * Schnellmodus (umgepackte Gewichte, etwa doppelt so schnelles Einlesen) braucht eine zweite
         * Kopie der Gewichte im RAM. Nur wenn danach noch reichlich frei ist; stürzt die App trotzdem
         * ab, schaltet der Absturzwächter ihn beim nächsten Mal ab.
         */
        fun fastAllowed(context: Context, @Suppress("UNUSED_PARAMETER") modelBytes: Long): Boolean {
            // Nur für Gerätetests: Schnellmodus (umgepackte Gewichte) erzwingen, Paket "…+schnell"
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("force_fast", false)) return true
            // Gerätetests: 26.11. S23+ lange Besprechung > 25 min; 27.11. erzwungen kam schon das Einlesen
            // einer 69-s-Besprechung in 45 min nicht zu Ende (2 GB RAM frei). Auf keinem Handy schneller: aus.
            return false
        }

        fun contextFor(context: Context, set: ModelSet, modelBytes: Long): Int {
            if (crashedLastTime(context)) return 4096
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            // Gewichte sind eingeblendet (mmap) und zählen nicht fest: auf echten Handys (S23+, Pixel 8)
            // blieben mit 8k-Kontext stets über 2 GB frei. Grundlage ist daher der freie RAM abzüglich Puffer.
            val spare = mi.availMem - 800L * 1024 * 1024 - (if (modelBytes > 4_000_000_000L) modelBytes / 2 else 0L)
            val fit = when {
                spare > 1200L * 1024 * 1024 -> 12288
                spare > 600L * 1024 * 1024 -> 8192
                else -> 4096
            }
            // Große Modelle nie über 8k: reicht für etwa eine Stunde Besprechung je Abschnitt
            val cap = if (set.llm.bytes > 2_000_000_000L) 8192 else set.llmContext
            return minOf(set.llmContext, cap, fit)
        }
    }

    fun countTokens(text: String) = LlamaNative.countTokens(handle, text)

    fun cancel() = LlamaNative.cancel(handle)

    fun complete(
        system: String,
        user: String,
        maxTokens: Int = 1500,
        temperature: Float = 0.2f,
        grammar: String? = null,
        cachePrefix: String? = null,
        progress: LlmProgress? = null,
    ): String {
        val prompt = if (set.noThink) "$user /no_think" else user
        val out = LlamaNative.complete(handle, system, prompt, maxTokens, temperature, grammar, progress, cachePrefix)
            ?: throw LlmException("Abgebrochen")
        if (out.startsWith("\u0001")) {
            throw LlmException(
                if (out.contains("PROMPT_TOO_LONG")) "Text zu lang für das Sprachmodell"
                else "Fehler im Sprachmodell")
        }
        return out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    override fun close() {
        LlamaNative.free(handle)
        markRunning(appContext, false)
    }
}
