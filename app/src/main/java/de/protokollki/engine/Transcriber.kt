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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import de.protokollki.audio.Audio16k
import de.protokollki.audio.FloatAudio
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

    /** Längste Aufnahme, die in einem Durchgang nach Sprechern getrennt wird (Sekunden). */
    var singlePassMaxSec: Float = 45f * 60f

    /** Abschnittslänge für längere Aufnahmen (Sekunden). */
    var windowSec: Float = 30f * 60f

    /** Raw-Sprecherabschnitte (Sekunden, Sprecher-ID). */
    data class Turn(val start: Float, val end: Float, val speaker: Int)

    fun diarize(
        samples: FloatArray,
        numSpeakers: Int,
        onProgress: (Float) -> Boolean,
    ): List<Turn> = diarize(FloatAudio(samples), numSpeakers, onProgress)

    /**
     * Sprechererkennung. Bis [singlePassMaxSec] in einem Durchgang (wie bisher); längere Aufnahmen
     * in Abschnitten von [windowSec], deren Sprecher anschließend über Stimm-Vektoren verbunden
     * werden ([SpeakerLinker]). So liegt nie die ganze Aufnahme als FloatArray im Speicher
     * (3 h wären 690 MB, mehr als der Java-Heap der meisten Handys hergibt).
     */
    fun diarize(
        audio: Audio16k,
        numSpeakers: Int,
        onProgress: (Float) -> Boolean,
    ): List<Turn> {
        val gain = AudioLevel.gain(audio)
        val single = (singlePassMaxSec * sampleRate).toLong()
        if (audio.size <= single) {
            val sd = OfflineSpeakerDiarization(config = diarizationConfig(numSpeakers))
            try {
                val samples = AudioLevel.applyGain(audio.floats(0, audio.size), gain)
                val result = sd.processWithCallback(samples, callback = DiarizationCallback(onProgress))
                return result.sortedBy { it.start }.map { Turn(it.start, it.end, it.speaker) }
            } finally {
                sd.release()
            }
        }
        return diarizeWindowed(audio, gain, numSpeakers, onProgress)
    }

    private fun diarizeWindowed(
        audio: Audio16k,
        gain: Float,
        numSpeakers: Int,
        onProgress: (Float) -> Boolean,
    ): List<Turn> {
        val win = (windowSec * sampleRate).toInt().coerceAtLeast(sampleRate * 10)
        val count = (audio.size + win - 1) / win
        // Kurzer Rest am Ende (< 1/3 Fenster) wird dem letzten Abschnitt zugeschlagen
        val bounds = (0 until count).map { i -> i * win to minOf(audio.size, (i + 1) * win) }.toMutableList()
        if (bounds.size >= 2 && bounds.last().let { it.second - it.first } < win / 3) {
            val last = bounds.removeAt(bounds.size - 1)
            bounds[bounds.size - 1] = bounds.last().first to last.second
        }
        val windowTurns = mutableListOf<List<Turn>>()
        val windowEmb = mutableListOf<Map<Int, FloatArray>>()
        var stopped = false
        // Je Abschnitt frei clustern; die vorgegebene Teilnehmerzahl gilt für die ganze Aufnahme
        val sd = OfflineSpeakerDiarization(config = diarizationConfig(0))
        val embedder = SpeakerEmbedder(threads)
        try {
            for ((i, b) in bounds.withIndex()) {
                val (turns, emb) = diarizeWindow(sd, embedder, audio, b.first, b.second, gain) { f ->
                    val go = onProgress((i + f.coerceIn(0f, 1f)) / bounds.size)
                    if (!go) stopped = true
                    go
                }
                windowTurns += turns
                windowEmb += emb
                if (stopped || !onProgress((i + 1f) / bounds.size)) break
            }
        } finally {
            embedder.close()
            sd.release()
        }
        val links = SpeakerLinker.link(windowEmb, numSpeakers)
        val out = mutableListOf<Turn>()
        for ((w, turns) in windowTurns.withIndex()) {
            val map = links.getOrNull(w).orEmpty()
            for (t in turns) {
                // Sprecher ohne Stimm-Vektor (nur Sekundenbruchteile): dem vorigen Beitrag zuschlagen
                val g = map[t.speaker] ?: out.lastOrNull()?.speaker ?: map.values.firstOrNull() ?: 0
                out += t.copy(speaker = g)
            }
        }
        return out.sortedBy { it.start }
    }

    /** Ein Abschnitt [a, e): Sprecherabschnitte (in Sekunden der ganzen Aufnahme) und Stimm-Vektoren. */
    private fun diarizeWindow(
        sd: OfflineSpeakerDiarization,
        embedder: SpeakerEmbedder,
        audio: Audio16k,
        a: Int,
        e: Int,
        gain: Float,
        onProgress: (Float) -> Boolean,
    ): Pair<List<Turn>, Map<Int, FloatArray>> {
        val offset = a / sampleRate.toFloat()
        // Das Abschnitts-Array lebt nur in dieser Funktion und wird danach freigegeben
        val samples = AudioLevel.applyGain(audio.floats(a, e), gain)
        var go = true
        val raw = sd.processWithCallback(samples, callback = DiarizationCallback { f ->
            onProgress(f).also { go = it }
        })
        val turns = raw.sortedBy { it.start }.map { Turn(it.start, it.end, it.speaker) }
        val emb = mutableMapOf<Int, FloatArray>()
        if (go) {
            // Stimm-Vektor je Sprecher (bis 20 s aus den längsten Beiträgen)
            for ((spk, list) in turns.groupBy { it.speaker }) {
                val clip = VoiceMath.collectAudio(samples, list.map { it.start to it.end }, maxSeconds = 20f)
                if (clip.size >= sampleRate * 2) emb[spk] = embedder.embed(clip)
            }
        }
        return turns.map { it.copy(start = it.start + offset, end = it.end + offset) } to emb
    }

    private fun diarizationConfig(numSpeakers: Int) = OfflineSpeakerDiarizationConfig(
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
            // an deutschen Testaufnahmen abgestimmt (0,5 fasst ähnliche Stimmen zusammen)
            threshold = 0.35f,
        ),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    )

    /**
     * Fasst Abschnitte zusammen und teilt zu lange (Whisper verarbeitet max. 30 s).
     * Sprecher-IDs werden nach erstem Auftreten neu nummeriert (0, 1, 2 …).
     */
    fun planChunks(turns: List<Turn>, samples: FloatArray): List<Turn> = planChunks(turns, FloatAudio(samples))

    fun planChunks(turns: List<Turn>, samples: Audio16k): List<Turn> {
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

    private fun splitLong(t: Turn, samples: Audio16k): List<Turn> {
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

    private fun quietestPoint(samples: Audio16k, fromSec: Float, toSec: Float): Float {
        val win = sampleRate / 10   // 100 ms
        var best = toSec
        var bestEnergy = Double.MAX_VALUE
        val from = (fromSec * sampleRate).toInt().coerceAtLeast(0)
        val end = min((toSec * sampleRate).toInt(), samples.size - win)
        if (from >= end) return best
        // Nur den betrachteten Ausschnitt lesen (8 s), nicht die ganze Aufnahme
        val part = samples.floats(from, end + win)
        var pos = from
        while (pos < end) {
            var e = 0.0
            for (i in pos - from until pos - from + win) e += part[i] * part[i]
            if (e < bestEnergy) { bestEnergy = e; best = (pos + win / 2) / sampleRate.toFloat() }
            pos += win / 2
        }
        return best
    }

    fun transcribe(
        samples: FloatArray,
        chunks: List<Turn>,
        onProgress: (done: Int, total: Int, partial: List<Segment>) -> Boolean,
    ): List<Segment> = transcribe(FloatAudio(samples), chunks, onProgress)

    fun transcribe(
        samples: Audio16k,
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
                        stream.acceptWaveform(samples.floats(a, b), sampleRate)
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
        samples: Audio16k,
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
        val recognizer = OfflineRecognizer(config = config)
        val words = mutableListOf<TokenAlign.Word>()
        var segments: List<Segment> = emptyList()
        try {
            for ((i, w) in windows.withIndex()) {
                val a = (w.first * sampleRate).toInt().coerceIn(0, samples.size)
                val b = (w.second * sampleRate).toInt().coerceIn(a, samples.size)
                if (b - a >= sampleRate / 4) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples.floats(a, b), sampleRate)
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
                if (!onProgress(i + 1, windows.size, segments)) break
            }
        } finally {
            recognizer.release()
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

    // Einmal übersetzt: clean() läuft bei langen Aufnahmen hunderttausendfach
    private val whitespace = Regex("\\s+")
    private val noise = Regex("\\[.*?]|\\(.*?(Musik|Applaus|Lachen).*?\\)", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String =
        raw.replace(whitespace, " ")
            .replace(noise, "")
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
