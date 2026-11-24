package de.protokollki.engine

import android.app.ActivityManager
import android.content.Context

/** Eine herunterladbare Datei. Bei Archiven werden nur [extract] übernommen. */
data class ModelFile(
    val url: String,
    val target: String,                 // Dateiname/Ordner unter models/
    val bytes: Long,                    // Download-Größe
    val extract: Map<String, String> = emptyMap(),  // Pfad im Archiv -> Zieldatei
    /**
     * Dieselben Zieldateien einzeln und entpackt (Release models-v1 dieses Projekts). Wird zuerst
     * versucht: kein minutenlanges Entpacken auf dem Handy, und jede Datei lässt sich fortsetzen.
     * Schlägt das fehl, wird das Archiv geladen und entpackt.
     */
    val direct: List<String> = emptyList(),
)

/** Ein Paket aus Spracherkennung + Sprachmodell, passend zur Geräteleistung. */
data class ModelSet(
    val id: String,
    val label: String,
    val fitsFor: String,                // passende Handys, wird in Klammern angezeigt
    val description: String,
    val minRamGb: Double,
    val whisperPrefix: String,          // z. B. "turbo" -> turbo-encoder.int8.onnx
    val whisper: ModelFile,
    val llm: ModelFile,
    val llmContext: Int,
    val noThink: Boolean = false,       // Qwen3-Hybridmodelle: Denkmodus abschalten
) {
    val displayName get() = "$label ($fitsFor)"

    val downloadBytes get() = whisper.bytes + llm.bytes + ModelCatalog.common.sumOf { it.bytes }
}

object ModelCatalog {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val HF = "https://huggingface.co"
    const val MIRROR = "https://github.com/Dcabrerizo-lab/Protokoll-KI/releases/download/models-v1"

    const val SEGMENTATION = "pyannote-segmentation-3-0.int8.onnx"
    const val EMBEDDING = "wespeaker_en_voxceleb_resnet34_LM.onnx"

    /** Sprechererkennung, für alle Pakete gleich. */
    val common = listOf(
        ModelFile(
            url = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            target = "segmentation",
            bytes = 6_958_444,
            extract = mapOf("sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" to SEGMENTATION),
            direct = listOf(SEGMENTATION),
        ),
        ModelFile(
            url = "$GH/speaker-recongition-models/$EMBEDDING",
            target = EMBEDDING,
            bytes = 26_530_550,
        ),
    )

    private fun whisper(name: String, bytes: Long) = ModelFile(
        url = "$GH/asr-models/sherpa-onnx-whisper-$name.tar.bz2",
        target = "whisper-$name",
        bytes = bytes,
        extract = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").associate {
            "sherpa-onnx-whisper-$name/$name-$it" to "$name-$it"
        },
        direct = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").map { "$name-$it" },
    )

    /**
     * NVIDIA Parakeet TDT 0.6B v3 (25 europäische Sprachen, auch Deutsch, mit Satzzeichen und
     * Zeitstempeln je Wort). Auf deutschen Testbesprechungen gleich genau wie Whisper large-v3-turbo,
     * aber etwa 7-mal schneller, weil lange Fenster am Stück verarbeitet werden.
     */
    private val parakeet = ModelFile(
        url = "$GH/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        target = "parakeet-v3",
        bytes = 487_211_443,
        extract = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").associate {
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/$it" to "parakeet-$it"
        },
        direct = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").map { "parakeet-$it" },
    )

    val sets = listOf(
        ModelSet(
            id = "ultra",
            label = "Ultra",
            fitsFor = "z. B. Galaxy S24 Ultra, S25 Ultra, Pixel 9 Pro · ab 12 GB RAM",
            description = "Höchste Qualität. Parakeet-Spracherkennung und Qwen3 8B.",
            minRamGb = 10.5,
            whisperPrefix = "parakeet",
            whisper = parakeet,
            llm = ModelFile(
                url = "$HF/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
                target = "Qwen3-8B-Q4_K_M.gguf",
                bytes = 5_027_783_488,
            ),
            llmContext = 16384,
            noThink = true,
        ),
        ModelSet(
            id = "premium",
            label = "Premium",
            fitsFor = "z. B. Galaxy S23, S23+, S24, Pixel 8 · 8 GB RAM",
            description = "Sehr gute Qualität, schnell. Parakeet-Spracherkennung und Qwen3 4B.",
            minRamGb = 6.5,   // Galaxy S23/S23+ melden 6,9 GB
            whisperPrefix = "parakeet",
            whisper = parakeet,
            llm = ModelFile(
                url = "$HF/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                target = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                bytes = 2_497_280_000,
            ),
            llmContext = 16384,
        ),
        ModelSet(
            id = "standard",
            label = "Standard",
            fitsFor = "z. B. Galaxy A54, A55, Pixel 6a · 6 GB RAM",
            description = "Gute Qualität. Parakeet-Spracherkennung und Qwen2.5 3B.",
            minRamGb = 5.0,
            whisperPrefix = "parakeet",
            whisper = parakeet,
            llm = ModelFile(
                url = "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                target = "qwen2.5-3b-instruct-q4_k_m.gguf",
                bytes = 2_104_932_768,
            ),
            llmContext = 8192,
        ),
        ModelSet(
            id = "kompakt",
            label = "Kompakt",
            fitsFor = "ältere Handys · 4 GB RAM",
            description = "Schnell und sparsam. Parakeet-Spracherkennung und Qwen2.5 1.5B.",
            minRamGb = 0.0,
            whisperPrefix = "parakeet",
            whisper = parakeet,
            llm = ModelFile(
                url = "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                target = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                bytes = 1_117_320_736,
            ),
            llmContext = 8192,
        ),
    )

    fun byId(id: String?) = sets.firstOrNull { it.id == id }

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem / 1024.0 / 1024.0 / 1024.0
    }

    /** Empfohlenes Paket nach Arbeitsspeicher (8-GB-Handys melden ca. 7,3 GB, 12-GB-Handys ca. 11 GB). */
    fun recommended(context: Context): ModelSet {
        val ram = totalRamGb(context)
        return sets.first { ram >= it.minRamGb }
    }
}
