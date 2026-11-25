package de.protokollki.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import de.protokollki.audio.Audio16k
import de.protokollki.audio.FloatAudio
import de.protokollki.data.MeetingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VoiceProfile(val id: String, val name: String, val createdAt: Long, val samples: Int)

/**
 * Stimmprofile: einmal eingelernte Stimmen werden in neuen Besprechungen automatisch
 * erkannt und mit Namen versehen. Gespeichert werden nur Zahlenvektoren (keine Audios).
 */
object VoiceProfiles {
    private const val FILE = "voices.json"
    private const val MEETING_EMB = "speaker_embeddings.json"

    private data class Stored(val profile: VoiceProfile, val embedding: FloatArray)

    private var stored = listOf<Stored>()
    private val _profiles = MutableStateFlow<List<VoiceProfile>>(emptyList())
    val profiles: StateFlow<List<VoiceProfile>> = _profiles.asStateFlow()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, FILE)
        stored = runCatching {
            val arr = JSONArray(file!!.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Stored(VoiceProfile(o.getString("id"), o.getString("name"), o.optLong("createdAt"),
                    o.optInt("samples", 1)), o.getJSONArray("embedding").toFloats())
            }
        }.getOrDefault(emptyList())
        publish()
    }

    private fun publish() { _profiles.value = stored.map { it.profile }.sortedBy { it.name.lowercase() } }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        stored.forEach {
            arr.put(JSONObject().put("id", it.profile.id).put("name", it.profile.name)
                .put("createdAt", it.profile.createdAt).put("samples", it.profile.samples)
                .put("embedding", it.embedding.toJson()))
        }
        file?.writeText(arr.toString())
        publish()
    }

    // ------------------------------------------------------------- Einlernen

    suspend fun enrollFromRecording(context: Context, name: String, samples: FloatArray): VoiceProfile =
        withContext(Dispatchers.Default) {
            init(context)
            require(VoiceMath.speechSeconds(samples) >= 8f) {
                "Zu wenig Sprache erkannt. Bitte etwa 15 Sekunden laut und deutlich sprechen."
            }
            val emb = SpeakerEmbedder().use { it.embed(samples) }
            add(name, emb)
        }

    fun canEnrollFromMeeting(meetingId: String, speakerId: Int): Boolean =
        loadMeetingEmbeddings(meetingId)?.containsKey(speakerId) == true

    suspend fun enrollFromMeeting(context: Context, meetingId: String, speakerId: Int, name: String): VoiceProfile =
        withContext(Dispatchers.Default) {
            init(context)
            val emb = loadMeetingEmbeddings(meetingId)?.get(speakerId)
                ?: throw IllegalArgumentException("Für diese Person ist keine Stimmprobe gespeichert.")
            val profile = add(name, emb)
            MeetingRepository.update(meetingId) { m ->
                m.copy(speakers = m.speakers.map {
                    if (it.id == speakerId) it.copy(name = name.trim(), profileId = profile.id) else it
                })
            }
            profile
        }

    /** Gleichnamiges Profil wird verfeinert (Mittelwert), sonst neu angelegt. */
    @Synchronized
    private fun add(name: String, emb: FloatArray): VoiceProfile {
        val clean = name.trim().ifEmpty { "Unbenannt" }
        val existing = stored.firstOrNull { it.profile.name.equals(clean, ignoreCase = true) }
        val result: Stored = if (existing != null) {
            val n = existing.profile.samples
            val merged = VoiceMath.normalize(FloatArray(emb.size) { i ->
                (existing.embedding[i] * n + emb[i]) / (n + 1)
            })
            existing.copy(profile = existing.profile.copy(samples = n + 1), embedding = merged)
        } else {
            Stored(VoiceProfile(UUID.randomUUID().toString(), clean, System.currentTimeMillis(), 1),
                VoiceMath.normalize(emb))
        }
        stored = stored.filterNot { it.profile.id == result.profile.id } + result
        persist()
        return result.profile
    }

    fun rename(id: String, name: String) {
        stored = stored.map { if (it.profile.id == id) it.copy(profile = it.profile.copy(name = name.trim())) else it }
        persist()
    }

    fun delete(id: String) {
        stored = stored.filterNot { it.profile.id == id }
        persist()
    }

    /** Löscht alle Stimmprofile (Teil von "Alle Daten löschen"). */
    fun deleteAll() {
        stored = emptyList()
        persist()
    }

    // ------------------------------------------------------------- Erkennen

    /**
     * Berechnet je Sprecher eine Stimmprobe, speichert sie im Besprechungsordner (für späteres
     * "Stimme merken") und ordnet bekannte Profile zu. Rückgabe: speakerId -> Profil.
     */
    fun identify(
        context: Context,
        meetingId: String,
        samples: FloatArray,
        turns: List<Transcriber.Turn>,
    ): Map<Int, VoiceProfile> = identify(context, meetingId, FloatAudio(samples), turns)

    fun identify(
        context: Context,
        meetingId: String,
        samples: Audio16k,
        turns: List<Transcriber.Turn>,
    ): Map<Int, VoiceProfile> {
        init(context)
        val perSpeaker = turns.groupBy { it.speaker }
        if (perSpeaker.isEmpty()) return emptyMap()
        val embeddings = mutableMapOf<Int, FloatArray>()
        SpeakerEmbedder().use { embedder ->
            for ((spk, list) in perSpeaker) {
                val audio = VoiceMath.collectAudio(samples, list.map { it.start to it.end }, maxSeconds = 30f)
                if (audio.size < 16000 * 2) continue
                embeddings[spk] = VoiceMath.normalize(embedder.embed(audio))
            }
        }
        saveMeetingEmbeddings(meetingId, embeddings)
        val known = stored.map { it.profile.id to it.embedding }
        val match = VoiceMath.assign(embeddings, known)
        return match.mapValues { (_, id) -> stored.first { it.profile.id == id }.profile }
    }

    private fun meetingEmbFile(meetingId: String) = File(MeetingRepository.dirFor(meetingId), MEETING_EMB)

    private fun saveMeetingEmbeddings(meetingId: String, e: Map<Int, FloatArray>) {
        val o = JSONObject()
        e.forEach { (k, v) -> o.put(k.toString(), v.toJson()) }
        meetingEmbFile(meetingId).writeText(o.toString())
    }

    private fun loadMeetingEmbeddings(meetingId: String): Map<Int, FloatArray>? = runCatching {
        val o = JSONObject(meetingEmbFile(meetingId).readText())
        o.keys().asSequence().associate { it.toInt() to o.getJSONArray(it).toFloats() }
    }.getOrNull()

    /** Nach Zusammenführen von Sprechern: Stimmprobe des entfernten Sprechers verwerfen. */
    fun forgetMeetingSpeaker(meetingId: String, speakerId: Int) {
        val e = loadMeetingEmbeddings(meetingId)?.toMutableMap() ?: return
        e.remove(speakerId); saveMeetingEmbeddings(meetingId, e)
    }

    private fun FloatArray.toJson() = JSONArray().also { a -> forEach { a.put(it.toDouble()) } }
    private fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }
}

/** Reine Rechenlogik (ohne Android), testbar. */
object VoiceMath {
    /** Mindestähnlichkeit und Abstand zum Zweitbesten für eine sichere Zuordnung. */
    const val THRESHOLD = 0.62f
    const val MARGIN = 0.05f

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val norm = kotlin.math.sqrt(n).toFloat().takeIf { it > 1e-9f } ?: return v
        return FloatArray(v.size) { v[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }

    /**
     * Ordnet Sprechern Profile zu (jedes Profil höchstens einmal, beste Paare zuerst).
     * Nur sichere Treffer: Ähnlichkeit >= THRESHOLD und deutlicher Abstand zum nächsten Profil.
     */
    fun assign(speakers: Map<Int, FloatArray>, profiles: List<Pair<String, FloatArray>>): Map<Int, String> {
        if (profiles.isEmpty()) return emptyMap()
        data class Cand(val spk: Int, val id: String, val score: Float)
        val cands = mutableListOf<Cand>()
        for ((spk, e) in speakers) {
            val scores = profiles.map { (id, p) -> id to cosine(e, p) }.sortedByDescending { it.second }
            val best = scores[0]
            val second = scores.getOrNull(1)?.second ?: -1f
            if (best.second >= THRESHOLD && best.second - second >= MARGIN) cands += Cand(spk, best.first, best.second)
        }
        val result = mutableMapOf<Int, String>()
        val used = mutableSetOf<String>()
        for (c in cands.sortedByDescending { it.score }) {
            if (c.spk in result || c.id in used) continue
            result[c.spk] = c.id; used += c.id
        }
        return result
    }

    /** Sammelt bis zu [maxSeconds] Audio aus den längsten Abschnitten. */
    fun collectAudio(samples: FloatArray, spans: List<Pair<Float, Float>>, maxSeconds: Float): FloatArray =
        collectAudio(FloatAudio(samples), spans, maxSeconds)

    fun collectAudio(samples: Audio16k, spans: List<Pair<Float, Float>>, maxSeconds: Float): FloatArray {
        val sorted = spans.sortedByDescending { it.second - it.first }
        val out = ArrayList<FloatArray>()
        var total = 0
        val max = (maxSeconds * 16000).toInt()
        for ((s, e) in sorted) {
            val a = (s * 16000).toInt().coerceIn(0, samples.size)
            val b = (e * 16000).toInt().coerceIn(a, samples.size)
            val take = minOf(b - a, max - total)
            if (take <= 0) break
            out += samples.floats(a, a + take); total += take
        }
        val result = FloatArray(total)
        var pos = 0
        for (chunk in out) { chunk.copyInto(result, pos); pos += chunk.size }
        return result
    }

    /** Grobe Schätzung der Sprechdauer über Energie (20-ms-Fenster). */
    fun speechSeconds(samples: FloatArray): Float {
        val win = 320
        var voiced = 0
        var i = 0
        while (i + win <= samples.size) {
            var e = 0.0
            for (j in i until i + win) e += samples[j] * samples[j]
            if (e / win > 1e-4) voiced++
            i += win
        }
        return voiced * win / 16000f
    }
}

/** Stimm-Vektor (WeSpeaker) aus Audio. Immer mit [close] freigeben. */
class SpeakerEmbedder(threads: Int = 2) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(
            model = ModelManager.file(ModelCatalog.EMBEDDING).absolutePath,
            numThreads = threads,
        )
    )

    fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}
