package de.protokollki.engine

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import de.protokollki.data.Segment
import kotlin.math.max
import kotlin.math.min

/**
 * Sprechererkennung (pyannote-Segmentierung + Sprecher-Embeddings) und
 * Transkription (Whisper) über sherpa-onnx. Läuft vollständig auf dem Gerät.
 */
class Transcriber(private val set: ModelSet, private val language: String = "de") {

    private val sampleRate = 16000
    private val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() - 2))

    /** Raw-Sprecherabschnitte (Sekunden, Sprecher-ID). */
    data class Turn(val start: Float, val end: Float, val speaker: Int)

    fun diarize(
        samples: FloatArray,
        numSpeakers: Int,
        onProgress: (Float) -> Boolean,
    ): List<Turn> {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = ModelManager.file(ModelCatalog.SEGMENTATION).absolutePath,
                ),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = ModelManager.file(ModelCatalog.EMBEDDING).absolutePath,
                numThreads = threads,
            ),
            clustering = FastClusteringConfig(
                numClusters = if (numSpeakers > 0) numSpeakers else -1,
                // an 18 deutschen Testszenarien abgestimmt (siehe scripts/bench)
                threshold = ModelCatalog.CLUSTER_THRESHOLD,
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val sd = OfflineSpeakerDiarization(config = config)
        try {
            val result = sd.processWithCallback(AudioLevel.normalize(samples), callback = DiarizationCallback(onProgress))
            return result.sortedBy { it.start }.map { Turn(it.start, it.end, it.speaker) }
        } finally {
            sd.release()
        }
    }

    /**
     * Fasst Abschnitte zusammen und teilt zu lange (Whisper verarbeitet max. 30 s).
     * Sprecher-IDs werden nach erstem Auftreten neu nummeriert (0, 1, 2 …).
     */
    fun planChunks(turns: List<Turn>, samples: FloatArray): List<Turn> {
        val totalSec = samples.size / sampleRate.toFloat()
        if (turns.isEmpty()) {
            return splitLong(Turn(0f, totalSec, 0), samples)
        }
        val merged = mutableListOf<Turn>()
        for (raw in turns) {
            val last = merged.lastOrNull()
            // Überlappungen abschneiden, damit kein Audio doppelt transkribiert wird
            val t = if (last != null && raw.start < last.end && raw.speaker != last.speaker)
                raw.copy(start = last.end) else raw
            if (t.end - t.start <= 0f) continue
            if (last != null && last.speaker == t.speaker && t.start - last.end < 1.0f) {
                merged[merged.size - 1] = last.copy(end = max(last.end, t.end))
            } else if (last != null && t.end <= last.end) {
                continue   // vollständig überlappt
            } else {
                merged += t
            }
        }
        // Mini-Abschnitte (< 0,6 s) dem Nachbarn zuschlagen
        val cleaned = mutableListOf<Turn>()
        for (t in merged) {
            if (t.end - t.start < 0.6f && cleaned.isNotEmpty()) {
                val prev = cleaned.last()
                cleaned[cleaned.size - 1] = prev.copy(end = max(prev.end, t.end))
            } else cleaned += t
        }
        val remap = LinkedHashMap<Int, Int>()
        return cleaned.flatMap { t ->
            val id = remap.getOrPut(t.speaker) { remap.size }
            splitLong(t.copy(speaker = id), samples)
        }
    }

    private fun splitLong(t: Turn, samples: FloatArray): List<Turn> {
        val out = mutableListOf<Turn>()
        var start = t.start
        while (t.end - start > 28f) {
            // leisesten Punkt zwischen 18 und 26 s suchen
            val cut = quietestPoint(samples, start + 18f, start + 26f)
            out += Turn(start, cut, t.speaker)
            start = cut
        }
        out += Turn(start, t.end, t.speaker)
        return out
    }

    private fun quietestPoint(samples: FloatArray, fromSec: Float, toSec: Float): Float {
        val win = sampleRate / 10   // 100 ms
        var best = toSec
        var bestEnergy = Double.MAX_VALUE
        var pos = (fromSec * sampleRate).toInt()
        val end = min((toSec * sampleRate).toInt(), samples.size - win)
        while (pos < end) {
            var e = 0.0
            for (i in pos until pos + win) e += samples[i] * samples[i]
            if (e < bestEnergy) { bestEnergy = e; best = (pos + win / 2) / sampleRate.toFloat() }
            pos += win / 2
        }
        return best
    }

    fun transcribe(
        samples: FloatArray,
        chunks: List<Turn>,
        onProgress: (done: Int, total: Int, partial: List<Segment>) -> Boolean,
    ): List<Segment> {
        if (set.whisperPrefix == "parakeet") return transcribeParakeet(samples, chunks, onProgress)
        val prefix = set.whisperPrefix
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = ModelManager.file("$prefix-encoder.int8.onnx").absolutePath,
                    decoder = ModelManager.file("$prefix-decoder.int8.onnx").absolutePath,
                    language = language,
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = ModelManager.file("$prefix-tokens.txt").absolutePath,
                numThreads = threads,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
        val recognizer = OfflineRecognizer(config = config)
        val segments = mutableListOf<Segment>()
        try {
            for ((i, c) in chunks.withIndex()) {
                val a = (c.start * sampleRate).toInt().coerceIn(0, samples.size)
                val b = (c.end * sampleRate).toInt().coerceIn(a, samples.size)
                if (b - a >= sampleRate / 4) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples.copyOfRange(a, b), sampleRate)
                        recognizer.decode(stream)
                        val text = TextCleanup.clean(recognizer.getResult(stream).text)
                        if (text.isNotEmpty() && !TextCleanup.isHallucination(text, b - a)) {
                            val last = segments.lastOrNull()
                            // gleicher Sprecher direkt danach (geteilter Abschnitt): anhängen
                            if (last != null && last.speakerId == c.speaker && c.start - last.end < 0.3f) {
                                segments[segments.size - 1] =
                                    last.copy(end = c.end, text = last.text + " " + text)
                            } else {
                                segments += Segment(c.speaker, c.start, c.end, text)
                            }
                        }
                    } finally {
                        stream.release()
                    }
                }
                if (!onProgress(i + 1, chunks.size, segments)) break
            }
        } finally {
            recognizer.release()
        }
        return segments
    }

    /**
     * Parakeet: verarbeitet die Aufnahme in Fenstern bis 25 s (an leisen Stellen geschnitten) und
     * ordnet jedes Wort über seinen Zeitstempel dem Sprecher zu. [chunks] liefert die Sprecher-
     * abschnitte (bereits nummeriert wie die Sprecherliste und die Stimmprofile).
     */
    private fun transcribeParakeet(
        samples: FloatArray,
        chunks: List<Turn>,
        onProgress: (done: Int, total: Int, partial: List<Segment>) -> Boolean,
    ): List<Segment> {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = ModelManager.file("parakeet-encoder.int8.onnx").absolutePath,
                    decoder = ModelManager.file("parakeet-decoder.int8.onnx").absolutePath,
                    joiner = ModelManager.file("parakeet-joiner.int8.onnx").absolutePath,
                ),
                tokens = ModelManager.file("parakeet-tokens.txt").absolutePath,
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        )
        val totalSec = samples.size / sampleRate.toFloat()
        // Nur Fenster mit Sprache (laut Sprechererkennung) verarbeiten
        val windows = TokenAlign.windows(totalSec) { a, b -> quietestPoint(samples, a, b) }
            .filter { (a, b) -> chunks.isEmpty() || chunks.any { it.end > a && it.start < b } }
        // Pegelangleich wie bei der Sprechererkennung: leise Aufnahmen (Fernmikrofon) ergaben sonst fast
        // nur Fehler oder leeren Text (Messung: 91 % → 73 % Wortfehler bei -35 dBFS). Je Fenster, damit
        // bei langen Aufnahmen keine zweite Kopie der ganzen Aufnahme im Speicher liegt.
        val gain = AudioLevel.gain(samples).let { if (it in 0.9f..1.1f) 1f else it }
        val recognizer = OfflineRecognizer(config = config)
        val words = mutableListOf<TokenAlign.Word>()
        var segments: List<Segment> = emptyList()
        var stopped = false
        try {
            for ((i, w) in windows.withIndex()) {
                val a = (w.first * sampleRate).toInt().coerceIn(0, samples.size)
                val b = (w.second * sampleRate).toInt().coerceIn(a, samples.size)
                if (b - a >= sampleRate / 4) {
                    val stream = recognizer.createStream()
                    try {
                        val piece = samples.copyOfRange(a, b)
                        if (gain != 1f) for (k in piece.indices) piece[k] = (piece[k] * gain).coerceIn(-1f, 1f)
                        stream.acceptWaveform(piece, sampleRate)
                        recognizer.decode(stream)
                        val r = recognizer.getResult(stream)
                        words += TokenAlign.words(r.tokens, r.timestamps, r.durations, w.first)
                    } finally {
                        stream.release()
                    }
                }
                segments = TokenAlign.segments(words, chunks, renumber = false)
                    .map { it.copy(text = TextCleanup.clean(it.text)) }
                    .filter { it.text.isNotEmpty() }
                if (!onProgress(i + 1, windows.size, segments)) { stopped = true; break }
            }
        } finally {
            recognizer.release()
        }
        // Nachclustering: jeder Satz zum ähnlichsten Sprecher (behebt Verwechslung ähnlicher Stimmen)
        if (!stopped && words.isNotEmpty() && chunks.map { it.speaker }.distinct().size >= 2) {
            runCatching {
                val extractor = SpeakerEmbeddingExtractor(
                    config = SpeakerEmbeddingExtractorConfig(
                        model = ModelManager.file(ModelCatalog.EMBEDDING).absolutePath,
                        numThreads = threads,
                    )
                )
                try {
                    val spk = TokenAlign.refineSpeakers(words, chunks, embed = { from, to ->
                        val a = (from * sampleRate).toInt().coerceIn(0, samples.size)
                        val b = (to * sampleRate).toInt().coerceIn(a, samples.size)
                        if (b - a < sampleRate) null else {
                            val piece = samples.copyOfRange(a, b)
                            if (gain != 1f) for (k in piece.indices) piece[k] = (piece[k] * gain).coerceIn(-1f, 1f)
                            val stream = extractor.createStream()
                            try {
                                stream.acceptWaveform(piece, sampleRate)
                                stream.inputFinished()
                                extractor.compute(stream)
                            } finally {
                                stream.release()
                            }
                        }
                    })
                    segments = TokenAlign.segments(words, chunks, renumber = false, speakers = spk)
                        .map { it.copy(text = TextCleanup.clean(it.text)) }
                        .filter { it.text.isNotEmpty() }
                } finally {
                    extractor.release()
                }
            }.onFailure { android.util.Log.w("ProtokollKI", "Nachclustering übersprungen: $it") }
        }
        return segments
    }
}

object TextCleanup {
    // Typische Whisper-Halluzinationen bei Stille/Rauschen (deutsch)
    private val hallucinations = listOf(
        "untertitel", "vielen dank fürs zuschauen", "vielen dank für's zuschauen",
        "bis zum nächsten mal", "copyright", "swr ", "zdf", "ard ", "wdr",
        "abonniert", "amara.org", "musik", "♪",
    )

    fun clean(raw: String): String =
        raw.replace(Regex("\\s+"), " ")
            .replace(Regex("\\[.*?]|\\(.*?(Musik|Applaus|Lachen).*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()

    fun isHallucination(text: String, nSamples: Int): Boolean {
        val t = text.lowercase()
        val seconds = nSamples / 16000f
        if (hallucinations.any { t.contains(it) } && (t.length < 80 || seconds < 4f)) return true
        // Endlos-Wiederholungen ("ja ja ja ja ...")
        val words = t.split(" ").filter { it.isNotBlank() }
        if (words.size >= 8 && words.toSet().size <= 2) return true
        return false
    }
}

/**
 * Fortschritts-Callback für sherpa-onnx. Die native Seite sucht per JNI exakt die Methode
 * `invoke(IIJ)Ljava/lang/Integer;`. Ein Kotlin-Lambda hat nur `invoke(Object, Object, Object)`
 * und führt zum Absturz ("JNI DETECTED ERROR"). Diese Klasse erzeugt die passende Signatur
 * (geprüft mit javap: `public java.lang.Integer invoke(int, int, long)`).
 */
class DiarizationCallback(private val onProgress: (Float) -> Boolean) : (Int, Int, Long) -> Int {
    override fun invoke(done: Int, total: Int, arg: Long): Int =
        if (onProgress(if (total > 0) done.toFloat() / total else 0f)) 0 else 1
}
