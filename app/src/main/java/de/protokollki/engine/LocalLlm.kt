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
    external fun load(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, text: String): Int
    external fun cancel(handle: Long)
    external fun complete(
        handle: Long, system: String, user: String, maxTokens: Int,
        temperature: Float, grammar: String?, callback: LlmProgress?,
    ): String?
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
        val threads = if (crashedLastTime(context)) 4 else 0
        appContext = context.applicationContext
        markRunning(appContext, true)
        var h = LlamaNative.load(path, ctx, threads)
        if (h == 0L && ctx > 4096) h = LlamaNative.load(path, 4096, threads)
        handle = h
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
        fun markRunning(context: Context, running: Boolean) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (running) p.edit().putBoolean("running", true).commit()
            else p.edit().putBoolean("running", false).putInt("crashes", 0).apply()
        }

        /** true, wenn der letzte Lauf des Sprachmodells nicht sauber beendet wurde. */
        fun crashedLastTime(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("running", false)

        fun contextFor(context: Context, set: ModelSet, modelBytes: Long): Int {
            if (crashedLastTime(context)) return 4096
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val spare = mi.availMem - modelBytes - 800L * 1024 * 1024
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
        progress: LlmProgress? = null,
    ): String {
        val prompt = if (set.noThink) "$user /no_think" else user
        val out = LlamaNative.complete(handle, system, prompt, maxTokens, temperature, grammar, progress)
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
