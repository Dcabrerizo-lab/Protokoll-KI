package de.protokollki.eval

import de.protokollki.data.ProtocolTemplate
import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import de.protokollki.engine.LlamaNative
import de.protokollki.engine.LlmProgress
import de.protokollki.engine.ProtocolGenerator
import de.protokollki.engine.TextModel
import de.protokollki.engine.Vocabulary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Host-Bewertungslauf: wendet ProtocolGenerator mit echtem Sprachmodell direkt auf die Szenario-Transkripte
 * an (ohne Spracherkennung) und schreibt je Szenario eine Zeile "PKRESULT {json}" (Auswertung:
 * scripts/quality/score.py).
 *
 * Aufruf: run --args="<szenarien.json> <ergebnis.jsonl>"
 * Umgebung: PK_LLM_LIB, PK_MODEL (ohne: Attrappe, nur zum Prüfen der Abläufe), PK_CTX (8192),
 * PK_NO_THINK=1 (Qwen3 mit Denkmodus), PK_STAGE (Name der Stufe), PK_VARIANTS ("ohne,mit"),
 * PK_ONLY (Komma-Liste von Szenarien), PK_GROUP (normal/hart).
 */
fun main(args: Array<String>) {
    val scenarios = JSONArray(File(args[0]).readText())
    val out = File(args.getOrElse(1) { "pk-results.jsonl" })
    out.writeText("")
    val stage = System.getenv("PK_STAGE") ?: "attrappe"
    val variants = (System.getenv("PK_VARIANTS") ?: "ohne,mit").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val only = System.getenv("PK_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val group = System.getenv("PK_GROUP")?.takeIf { it.isNotBlank() }
    val model = openModel()
    val monday = 1795431600000L // Montag, 23.11.2026

    for (variant in variants) for (i in 0 until scenarios.length()) {
        val sc = scenarios.getJSONObject(i)
        val name = sc.getString("name")
        if (only != null && name !in only) continue
        if (group != null && sc.getString("group") != group) continue
        val withList = variant == "mit"
        val participants = if (withList) sc.getJSONArray("participants").strs() else emptyList()
        // wie ProcessingService: Teilnehmernamen + eigenes Wörterbuch korrigieren das Transkript
        val vocab = if (withList) (participantTerms(participants) + sc.getJSONArray("vocabulary").strs()).distinct()
        else emptyList()
        val segJ = sc.getJSONArray("segments")
        val segments = (0 until segJ.length()).map {
            val s = segJ.getJSONObject(it)
            val text = s.getString("text").let { t -> if (vocab.isEmpty()) t else Vocabulary.correct(t, vocab) }
            Segment(s.getInt("speaker"), it * 5f, it * 5f + 4f, text)
        }
        val speakers = segments.map { it.speakerId }.distinct().sorted().map { Speaker(it, "Person ${it + 1}") }
        val openJ = sc.getJSONArray("openTasks")
        val open = (0 until openJ.length()).map {
            val o = openJ.getJSONObject(it)
            ProtocolGenerator.OpenTask("A${it + 1}", "t${it + 1}", "m0", o.getString("text"), o.getString("owner"))
        }
        model.reset()
        val t0 = System.currentTimeMillis()
        val r = runCatching {
            ProtocolGenerator(model).generate(name, monday, segments, speakers, wantSummary = true,
                agenda = sc.getJSONArray("agenda").strs(), openTasks = open,
                template = ProtocolTemplate.of(sc.getString("template")), vocabulary = vocab,
                participants = participants) { _, _ -> true }
        }
        val ms = System.currentTimeMillis() - t0
        val res = JSONObject().put("stage", stage).put("variant", variant).put("scenario", name)
            .put("group", sc.getString("group")).put("ms", ms).put("llm_calls", model.calls)
            .put("prompt_tokens", model.promptTokens).put("out_chars", model.outChars)
            .put("voices", sc.getJSONArray("voices"))
        r.onFailure { res.put("error", it.toString()) }
        r.onSuccess { g ->
            res.put("overview", g.summary.overview)
            res.put("topics", JSONArray(g.summary.topics.map { t ->
                JSONObject().put("title", t.title).put("points", JSONArray(t.points)) }))
            res.put("decisions", JSONArray(g.summary.decisions))
            res.put("open_points", JSONArray(g.summary.openPoints))
            res.put("tasks", JSONArray(g.tasks.map { t ->
                JSONObject().put("text", t.text).put("owner", t.owner).put("due", t.due).put("ref", t.ref) }))
            res.put("completed", JSONArray(g.completed.map { it.text }))
            res.put("sensitive", JSONArray(g.sensitiveTerms))
        }
        res.put("raw", JSONArray(model.raws))
        val line = res.toString()
        println("PKRESULT $line")
        out.appendText(line + "\n")
    }
}

private fun JSONArray.strs() = (0 until length()).map { getString(it) }

private fun participantTerms(names: List<String>): List<String> = names.flatMap { n ->
    val core = n.split(' ').filterNot { it.trimEnd('.').lowercase() in setOf("frau", "herr", "dr", "prof") }
        .joinToString(" ").trim()
    if (core.isEmpty()) emptyList() else listOf(core) + core.split(' ').drop(1).filter { it.length >= 4 }
}.distinct()

private abstract class CountingModel : TextModel {
    var calls = 0; var promptTokens = 0; var outChars = 0
    val raws = mutableListOf<String>()
    fun reset() { calls = 0; promptTokens = 0; outChars = 0; raws.clear() }
    fun record(system: String, user: String, out: String) {
        calls++; promptTokens += countTokens(system) + countTokens(user); outChars += out.length; raws += out
    }
}

private fun openModel(): CountingModel {
    val lib = System.getenv("PK_LLM_LIB")
    val path = System.getenv("PK_MODEL")
    if (lib == null || path == null) return object : CountingModel() {
        override val contextSize = 8192
        override fun countTokens(text: String) = text.length / 3 + 1
        override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                              grammar: String?, progress: LlmProgress?): String {
            if (user.contains("\nAussagen:\n")) println("ATTRAPPE-NACHFASSEN\n" + user.substringAfter("\nAussagen:\n"))
            val o = """{"zusammenfassung": "Attrappe.", "themen": [], "beschluesse": [], "offene_punkte": [],
                "aufgaben": [], "erledigte_aufgaben": [], "sensible_daten": []}"""
            record(system, user, o); return o
        }
    }
    val noThink = System.getenv("PK_NO_THINK") == "1"
    val ctx = System.getenv("PK_CTX")?.toInt() ?: 8192
    LlamaNative.initForTest(lib)
    val h = LlamaNative.load(path, ctx, 0, true)
    check(h != 0L) { "Modell nicht geladen" }
    return object : CountingModel() {
        override val contextSize = LlamaNative.contextSize(h)
        override fun countTokens(text: String) = LlamaNative.countTokens(h, text)
        override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                              grammar: String?, progress: LlmProgress?): String {
            val out = LlamaNative.complete(h, system, if (noThink) "$user /no_think" else user,
                maxTokens, temperature, grammar, progress) ?: error("abgebrochen")
            val clean = out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            record(system, user, clean)
            return clean
        }
    }
}
