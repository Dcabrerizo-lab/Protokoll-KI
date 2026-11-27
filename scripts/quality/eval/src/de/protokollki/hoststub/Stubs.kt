// Ersatz für Android-abhängige Teile der App (nur für den Host-Bewertungslauf).
@file:Suppress("unused")
package de.protokollki.engine

/** Rückmeldung aus dem nativen Code. phase 0 = Text lesen, 1 = Antwort schreiben. */
fun interface LlmProgress { fun onProgress(phase: Int, done: Int, total: Int): Boolean }

class LlmException(message: String) : Exception(message)

/** Gleiche JNI-Signaturen wie in LocalLlm.kt (Symbolnamen Java_de_protokollki_engine_LlamaNative_*). */
object LlamaNative {
    fun initForTest(libPath: String) { System.load(libPath); init("", false) }
    external fun init(nativeLibDir: String, gpu: Boolean)
    external fun load(modelPath: String, nCtx: Int, nThreads: Int, fast: Boolean): Long
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, text: String): Int
    external fun cancel(handle: Long)
    external fun complete(handle: Long, system: String, user: String, maxTokens: Int,
                          temperature: Float, grammar: String?, callback: LlmProgress?, cachePrefix: String?): String?
    external fun setCacheDir(handle: Long, dir: String?)
    external fun free(handle: Long)
}

class LocalLlm {
    val contextSize = 0
    fun countTokens(text: String) = 0
    fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                 grammar: String?, cachePrefix: String? = null, progress: LlmProgress? = null) = ""
}

class Holder<T>(val value: T)
object VoiceProfiles { class P(val name: String); val profiles = Holder(listOf<P>()) }
