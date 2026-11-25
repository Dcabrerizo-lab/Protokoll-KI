package de.protokollki.data

import org.json.JSONArray
import org.json.JSONObject

/** Art des Protokolls. */
enum class ProtocolMode(val label: String) {
    ERGEBNIS("Ergebnisprotokoll"),
    WORT("Wortprotokoll"),
}

/** Protokollvorlage: steuert Gliederung, Formulierungen und das Unterschriftsfeld im Export. */
enum class ProtocolTemplate(
    val label: String,
    val description: String,
    val documentTitle: String,
    val signatures: List<String>,
) {
    ALLGEMEIN("Allgemein", "Für jede Art von Besprechung", "Ergebnisprotokoll", emptyList()),
    DIENSTBESPRECHUNG("Dienstbesprechung", "Team, Organisation, Termine", "Protokoll der Dienstbesprechung",
        listOf("Protokollführung", "Leitung")),
    FALLBESPRECHUNG("Fallbesprechung", "Je Fall: Lage, Einschätzung, Maßnahmen", "Protokoll der Fallbesprechung",
        listOf("Protokollführung", "Fallverantwortliche Fachkraft")),
    UEBERGABE("Übergabe", "Schicht- oder Dienstübergabe je Person", "Übergabeprotokoll",
        listOf("Übergebende Fachkraft", "Übernehmende Fachkraft")),
    VEREINSSITZUNG("Vereinssitzung", "TOPs, Abstimmungen, Beschlussfähigkeit", "Sitzungsprotokoll",
        listOf("Sitzungsleitung", "Protokollführung"));

    companion object {
        fun of(name: String?): ProtocolTemplate =
            entries.firstOrNull { it.name == name } ?: ALLGEMEIN
    }
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
    val profileId: String? = null,   // per Stimmprofil automatisch erkannt
)

data class TaskItem(
    val text: String,
    val owner: String = "offen",
    val due: String = "offen",
    val done: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
    val ref: Int = -1,                  // Index des Belegsegments in Meeting.segments, -1 = keiner
    val doneNote: String? = null,       // z. B. "Erledigt laut Besprechung vom 30.11.2026"
)

/** Stand einer offenen Aufgabe aus einer früheren Besprechung, erkannt in dieser Besprechung. */
data class FollowUp(
    val taskId: String,
    val meetingId: String,              // Besprechung, aus der die Aufgabe stammt
    val text: String,
    val owner: String,
    val done: Boolean,                  // laut dieser Besprechung erledigt
    val ref: Int = -1,
)

/** Ein Abschnitt des Ergebnisprotokolls (Thema mit Ergebnis-Stichpunkten). */
data class Topic(
    val title: String,
    val points: List<String>,
    val refs: List<Int> = emptyList(),  // je Punkt Index des Belegsegments, -1 = keiner
) {
    fun refAt(i: Int): Int = refs.getOrElse(i) { -1 }
}

data class Summary(
    val overview: String = "",              // 3-5 Sätze
    val topics: List<Topic> = emptyList(),
    val decisions: List<String> = emptyList(),
    val openPoints: List<String> = emptyList(),
    val decisionRefs: List<Int> = emptyList(),
    val openPointRefs: List<Int> = emptyList(),
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
    val agenda: List<String> = emptyList(),         // Tagesordnung (vorab eingegeben)
    val participants: List<String> = emptyList(),   // Namen der Teilnehmenden (vorab, optional)
    val followUps: List<FollowUp> = emptyList(),    // Stand offener Aufgaben aus früheren Sitzungen
    val sensitiveTerms: List<String> = emptyList(), // zu schwärzende Begriffe (Klientennamen usw.)
    val audioDeleted: Boolean = false,              // Audio per Löschfrist entfernt
    val template: ProtocolTemplate = ProtocolTemplate.ALLGEMEIN,
    val location: String = "",                      // Ort (für formale Protokolle)
    val chair: String = "",                         // Sitzungsleitung / Leitung
    val minuteTaker: String = "",                   // Protokollführung
    val importedFrom: String? = null,               // Dateiname, wenn aus altem Protokoll (PDF/Word/Text) importiert
) {
    fun speakerName(id: Int): String =
        speakers.firstOrNull { it.id == id }?.name ?: "Person ${id + 1}"

    val isBusy get() = status == MeetingStatus.QUEUED ||
        status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.SUMMARIZING
}

// ---------------------------------------------------------------- JSON

private fun List<String>.toJson() = JSONArray().also { a -> forEach { a.put(it) } }
private fun List<Int>.intsToJson() = JSONArray().also { a -> forEach { a.put(it) } }
private fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it, -1) }
private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun Meeting.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("createdAt", createdAt)
    put("durationSec", durationSec.toDouble()); put("audioFile", audioFile)
    put("status", status.name); put("mode", mode.name)
    put("expectedSpeakers", expectedSpeakers)
    put("error", error)
    put("agenda", agenda.toJson())
    put("participants", participants.toJson())
    put("sensitiveTerms", sensitiveTerms.toJson())
    put("audioDeleted", audioDeleted)
    put("template", template.name)
    put("location", location); put("chair", chair); put("minuteTaker", minuteTaker)
    put("importedFrom", importedFrom)
    put("followUps", JSONArray().also { a ->
        followUps.forEach {
            a.put(JSONObject().put("taskId", it.taskId).put("meetingId", it.meetingId)
                .put("text", it.text).put("owner", it.owner).put("done", it.done).put("ref", it.ref))
        }
    })
    put("segments", JSONArray().also { a ->
        segments.forEach {
            a.put(JSONObject().put("s", it.speakerId).put("a", it.start.toDouble())
                .put("b", it.end.toDouble()).put("t", it.text))
        }
    })
    put("speakers", JSONArray().also { a ->
        speakers.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("profileId", it.profileId))
        }
    })
    put("summary", JSONObject().apply {
        put("overview", summary.overview)
        put("topics", JSONArray().also { a ->
            summary.topics.forEach {
                a.put(JSONObject().put("title", it.title).put("points", it.points.toJson())
                    .put("refs", it.refs.intsToJson()))
            }
        })
        put("decisions", summary.decisions.toJson())
        put("openPoints", summary.openPoints.toJson())
        put("decisionRefs", summary.decisionRefs.intsToJson())
        put("openPointRefs", summary.openPointRefs.intsToJson())
    })
    put("tasks", JSONArray().also { a ->
        tasks.forEach {
            a.put(JSONObject().put("text", it.text).put("owner", it.owner)
                .put("due", it.due).put("done", it.done).put("id", it.id).put("ref", it.ref)
                .put("doneNote", it.doneNote))
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
            val s = spk.getJSONObject(it)
            Speaker(s.optInt("id"), s.optString("name"),
                if (s.isNull("profileId")) null else s.optString("profileId"))
        },
        summary = Summary(
            overview = sum.optString("overview"),
            topics = (0 until topics.length()).map {
                val t = topics.getJSONObject(it)
                Topic(t.optString("title"), t.optJSONArray("points").strings(),
                    t.optJSONArray("refs").ints())
            },
            decisions = sum.optJSONArray("decisions").strings(),
            openPoints = sum.optJSONArray("openPoints").strings(),
            decisionRefs = sum.optJSONArray("decisionRefs").ints(),
            openPointRefs = sum.optJSONArray("openPointRefs").ints(),
        ),
        tasks = (0 until tasks.length()).map {
            val t = tasks.getJSONObject(it)
            TaskItem(t.optString("text"), t.optString("owner", "offen"),
                t.optString("due", "offen"), t.optBoolean("done"),
                id = t.optString("id").ifEmpty { "${o.getString("id")}-$it" },
                ref = t.optInt("ref", -1),
                doneNote = if (t.isNull("doneNote")) null else t.optString("doneNote"))
        },
        agenda = o.optJSONArray("agenda").strings(),
        participants = o.optJSONArray("participants").strings(),
        sensitiveTerms = o.optJSONArray("sensitiveTerms").strings(),
        audioDeleted = o.optBoolean("audioDeleted", false),
        template = ProtocolTemplate.of(o.optString("template", "")),
        location = o.optString("location", ""),
        chair = o.optString("chair", ""),
        minuteTaker = o.optString("minuteTaker", ""),
        importedFrom = if (o.isNull("importedFrom")) null else o.optString("importedFrom"),
        followUps = (o.optJSONArray("followUps") ?: JSONArray()).let { a ->
            (0 until a.length()).map {
                val f = a.getJSONObject(it)
                FollowUp(f.optString("taskId"), f.optString("meetingId"), f.optString("text"),
                    f.optString("owner"), f.optBoolean("done"), f.optInt("ref", -1))
            }
        },
    )
}
