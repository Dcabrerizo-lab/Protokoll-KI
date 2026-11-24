package de.protokollki.engine

import de.protokollki.data.Segment
import de.protokollki.data.Speaker
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Qualitätstest mit echtem Sprachmodell. Läuft nur, wenn PK_LLM_LIB und PK_MODEL
 * gesetzt sind (in der CI-Pipeline "quality"), sonst übersprungen.
 */
class QualityTest {
    @Test
    fun protocolFromTestMeeting() {
        val lib = System.getenv("PK_LLM_LIB")
        val modelPath = System.getenv("PK_MODEL")
        assumeTrue(lib != null && modelPath != null)
        val noThink = System.getenv("PK_NO_THINK") == "1"
        val ctx = System.getenv("PK_CTX")?.toInt() ?: 8192

        LlamaNative.initForTest(lib!!)
        val handle = LlamaNative.load(modelPath!!, ctx, 0, true)
        assertTrue("Modell nicht geladen", handle != 0L)
        val model = object : TextModel {
            override val contextSize = LlamaNative.contextSize(handle)
            override fun countTokens(text: String) = LlamaNative.countTokens(handle, text)
            override fun complete(system: String, user: String, maxTokens: Int, temperature: Float,
                                  grammar: String?, progress: LlmProgress?): String {
                val t0 = System.currentTimeMillis()
                val out = LlamaNative.complete(handle, system, if (noThink) "$user /no_think" else user,
                    maxTokens, temperature, grammar, progress) ?: error("abgebrochen")
                println("--- LLM (${System.currentTimeMillis() - t0} ms) ---\n$out\n")
                return out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            }
        }

        val json = JSONArray(javaClass.classLoader!!.getResource("test_transcript.json").readText())
        val segments = (0 until json.length()).map {
            val o = json.getJSONObject(it)
            Segment(o.getInt("speakerId"), o.getDouble("start").toFloat(),
                o.getDouble("end").toFloat(), o.getString("text"))
        }
        val speakers = segments.map { it.speakerId }.distinct().sorted().map { Speaker(it, "Person ${it + 1}") }
        // Montag, 23.11.2026
        val result = ProtocolGenerator(model).generate("Teambesprechung", 1795431600000L,
            segments, speakers, wantSummary = true) { f, text -> println("[${(f * 100).toInt()}%] $text"); true }

        println("=== ZUSAMMENFASSUNG ===\n${result.summary.overview}")
        result.summary.topics.forEach { t -> println("## ${t.title}"); t.points.forEach { println(" - $it") } }
        println("Beschlüsse: ${result.summary.decisions}")
        println("Offen: ${result.summary.openPoints}")
        println("=== AUFGABEN ===")
        result.tasks.forEach { println(" * ${it.text} | ${it.owner} | ${it.due}") }
        LlamaNative.free(handle)

        assertTrue(result.summary.overview.isNotBlank())
        assertTrue(result.tasks.size >= 2)
    }
}
