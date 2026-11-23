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

    init {
        LlamaNative.ensureInit(context)
        val path = ModelManager.file(set.llm.target).absolutePath
        handle = LlamaNative.load(path, set.llmContext, 0)
        if (handle == 0L) throw LlmException(
            "Das Sprachmodell konnte nicht geladen werden. Eventuell ist zu wenig Arbeitsspeicher frei. " +
                "Bitte andere Apps schließen oder in den Einstellungen ein kleineres Modellpaket wählen.")
    }

    val contextSize: Int get() = LlamaNative.contextSize(handle)

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

    override fun close() = LlamaNative.free(handle)
}
