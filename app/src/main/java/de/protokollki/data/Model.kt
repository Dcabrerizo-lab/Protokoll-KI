package de.protokollki.data

import org.json.JSONArray
import org.json.JSONObject

/** Art des Protokolls. */
enum class ProtocolMode(val label: String) {
    ERGEBNIS("Ergebnisprotokoll"),
    WORT("Wortprotokoll"),
}

enum class MeetingStatus {
    RECORDING,      // Aufnahme läuft
    RECORDED,       // Audio vorhanden, noch nicht verarbeitet
    QUEUED,         // wartet auf Verarbeitung
    TRANSCRIBING,   // Sprechererkennung + Transkription
    SUMMARIZING,    // Sprachmodell schreibt Protokoll/Aufgaben
    DONE,
    ERROR,
}

/** Ein Redebeitrag. */
data class Segment(
    val speakerId: Int,
    val start: Float,   // Sekunden
    val end: Float,
    val text: String,
)

data class Speaker(
    val id: Int,
    val name: String,   // "Person 1" oder vom Nutzer umbenannt
)

data class TaskItem(
    val text: String,
    val owner: String = "offen",
    val due: String = "offen",
    val done: Boolean = false,
)

/** Ein Abschnitt des Ergebnisprotokolls (Thema mit Ergebnis-Stichpunkten). */
data class Topic(
    val title: String,
    val points: List<String>,
)

data class Summary(
    val overview: String = "",              // 3-5 Sätze
    val topics: List<Topic> = emptyList(),
    val decisions: List<String> = emptyList(),
    val openPoints: List<String> = emptyList(),
) {
    val isEmpty get() = overview.isBlank() && topics.isEmpty()
}

data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,                    // Epoch-Millis
    val durationSec: Float = 0f,
    val audioFile: String,                  // absoluter Pfad, WAV 16 kHz mono
    val status: MeetingStatus = MeetingStatus.RECORDED,
    val progress: Float = 0f,               // 0..1 der aktuellen Phase
    val progressText: String = "",
    val mode: ProtocolMode = ProtocolMode.ERGEBNIS,
    val expectedSpeakers: Int = 0,          // 0 = automatisch
    val segments: List<Segment> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val summary: Summary = Summary(),
    val tasks: List<TaskItem> = emptyList(),
    val error: String? = null,
) {
    fun speakerName(id: Int): String =
        speakers.firstOrNull { it.id == id }?.name ?: "Person ${id + 1}"

    val isBusy get() = status == MeetingStatus.QUEUED ||
        status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.SUMMARIZING
}

// ---------------------------------------------------------------- JSON

private fun List<String>.toJson() = JSONArray().also { a -> forEach { a.put(it) } }
private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun Meeting.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("createdAt", createdAt)
    put("durationSec", durationSec.toDouble()); put("audioFile", audioFile)
    put("status", status.name); put("mode", mode.name)
    put("expectedSpeakers", expectedSpeakers)
    put("error", error)
    put("segments", JSONArray().also { a ->
        segments.forEach {
            a.put(JSONObject().put("s", it.speakerId).put("a", it.start.toDouble())
                .put("b", it.end.toDouble()).put("t", it.text))
        }
    })
    put("speakers", JSONArray().also { a ->
        speakers.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
    })
    put("summary", JSONObject().apply {
        put("overview", summary.overview)
        put("topics", JSONArray().also { a ->
            summary.topics.forEach {
                a.put(JSONObject().put("title", it.title).put("points", it.points.toJson()))
            }
        })
        put("decisions", summary.decisions.toJson())
        put("openPoints", summary.openPoints.toJson())
    })
    put("tasks", JSONArray().also { a ->
        tasks.forEach {
            a.put(JSONObject().put("text", it.text).put("owner", it.owner)
                .put("due", it.due).put("done", it.done))
        }
    })
}

fun meetingFromJson(o: JSONObject): Meeting {
    val seg = o.optJSONArray("segments") ?: JSONArray()
    val spk = o.optJSONArray("speakers") ?: JSONArray()
    val sum = o.optJSONObject("summary") ?: JSONObject()
    val topics = sum.optJSONArray("topics") ?: JSONArray()
    val tasks = o.optJSONArray("tasks") ?: JSONArray()
    var status = runCatching { MeetingStatus.valueOf(o.optString("status")) }
        .getOrDefault(MeetingStatus.RECORDED)
    return Meeting(
        id = o.getString("id"),
        title = o.optString("title"),
        createdAt = o.optLong("createdAt"),
        durationSec = o.optDouble("durationSec", 0.0).toFloat(),
        audioFile = o.optString("audioFile"),
        status = status,
        mode = runCatching { ProtocolMode.valueOf(o.optString("mode")) }
            .getOrDefault(ProtocolMode.ERGEBNIS),
        expectedSpeakers = o.optInt("expectedSpeakers", 0),
        error = if (o.isNull("error")) null else o.optString("error"),
        segments = (0 until seg.length()).map {
            val s = seg.getJSONObject(it)
            Segment(s.optInt("s"), s.optDouble("a").toFloat(), s.optDouble("b").toFloat(),
                s.optString("t"))
        },
        speakers = (0 until spk.length()).map {
            val s = spk.getJSONObject(it); Speaker(s.optInt("id"), s.optString("name"))
        },
        summary = Summary(
            overview = sum.optString("overview"),
            topics = (0 until topics.length()).map {
                val t = topics.getJSONObject(it)
                Topic(t.optString("title"), t.optJSONArray("points").strings())
            },
            decisions = sum.optJSONArray("decisions").strings(),
            openPoints = sum.optJSONArray("openPoints").strings(),
        ),
        tasks = (0 until tasks.length()).map {
            val t = tasks.getJSONObject(it)
            TaskItem(t.optString("text"), t.optString("owner", "offen"),
                t.optString("due", "offen"), t.optBoolean("done"))
        },
    )
}
