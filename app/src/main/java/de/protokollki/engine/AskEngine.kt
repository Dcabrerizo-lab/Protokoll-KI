package de.protokollki.engine

import android.content.Context
import de.protokollki.data.MeetingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AskState(
    val question: String = "",
    val hits: List<SearchHit> = emptyList(),
    val answer: String = "",          // mit Quellenmarkern [1], [2] … = hits[n-1]
    val answering: Boolean = false,
    val progressText: String = "",
    val error: String? = null,
)

/**
 * Fragen an alle Besprechungen: Treffer kommen sofort aus [MeetingSearch], danach formuliert das
 * lokale Sprachmodell eine kurze Antwort mit Quellenangaben. Alles offline.
 */
object AskEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state.asStateFlow()

    private var job: Job? = null
    private val lock = Any()
    @Volatile private var llm: LocalLlm? = null
    private var llmSetId: String? = null

    const val BUSY_MESSAGE = "Die KI-Antwort ist verfügbar, sobald die laufende Verarbeitung fertig ist."

    fun ask(context: Context, question: String) {
        val q = question.trim()
        cancel()
        if (q.isEmpty()) { clear(); return }
        val meetings = MeetingRepository.meetings.value
        val hits = MeetingSearch.search(meetings, q, limit = 20)
        val busy = meetings.any { it.isBusy }
        val set = ModelManager.state.value.set
        val canAnswer = !busy && hits.isNotEmpty() && ModelManager.isInstalled(set)
        _state.value = AskState(
            question = q, hits = hits, answering = canAnswer,
            progressText = if (canAnswer) "Lade Sprachmodell …" else "",
            answer = if (hits.isEmpty()) AskPrompt.NOTHING_FOUND else "",
            error = when {
                hits.isEmpty() -> null
                busy -> BUSY_MESSAGE
                !ModelManager.isInstalled(set) -> "Für KI-Antworten bitte zuerst die KI-Modelle herunterladen."
                else -> null
            },
        )
        if (!canAnswer) return
        val app = context.applicationContext
        job = scope.launch {
            val answer = runCatching {
              // Sperre über Laden UND Rechnen: das Modell darf nicht während einer Antwort freigegeben werden
              synchronized(lock) {
                if (_state.value.question != q) throw LlmException("Abgebrochen")
                val model = llm?.takeIf { llmSetId == set.id } ?: run {
                    llm?.close()
                    llm = null
                    LocalLlm(app, set).also { llm = it; llmSetId = set.id }
                }
                update { it.copy(progressText = "Durchsuche ${hits.distinctBy { h -> h.meetingId }.size} Besprechungen …") }
                val top = hits.take(AskPrompt.MAX_SOURCES)
                fun attempt(strict: Boolean): String {
                    val prompt = AskPrompt.build(q, top, meetings, strict)
                    val raw = model.complete(AskPrompt.SYSTEM, prompt, 450, 0.2f, null) { _, done, total ->
                        if (total > 0) update { s ->
                            s.copy(progressText = if (done < total) "Lese Fundstellen …" else "Formuliere Antwort …")
                        }
                        _state.value.question == q
                    }
                    return AskPrompt.clean(raw, top.size)
                }
                val first = attempt(strict = true)
                // Ausweg gewählt, obwohl es Treffer gibt: einmal ohne Ausweg nachfragen
                if (first.startsWith(AskPrompt.NOTHING_FOUND) && _state.value.question == q) {
                    val second = attempt(strict = false)
                    if (AskPrompt.citations(second).isNotEmpty()) second else first
                } else first
              }
            }
            if (_state.value.question != q) return@launch
            answer.onSuccess { a ->
                update { it.copy(answer = a, answering = false, progressText = "") }
            }.onFailure { e ->
                update {
                    it.copy(answering = false, progressText = "",
                        error = if (e is LlmException && e.message == "Abgebrochen") null
                        else (e.message ?: "Die KI-Antwort ist fehlgeschlagen."))
                }
            }
        }
    }

    private inline fun update(f: (AskState) -> AskState) { _state.value = f(_state.value) }

    fun cancel() {
        llm?.cancel()
        job?.cancel()
        job = null
        if (_state.value.answering) update { it.copy(answering = false, progressText = "") }
    }

    fun clear() {
        cancel()
        _state.value = AskState()
    }

    /** Gibt das Sprachmodell frei (mehrere GB RAM). Beim Verlassen des Bildschirms aufrufen. */
    fun release() {
        cancel()
        scope.launch { releaseNow() }
    }

    /** Wie [release], aber blockierend (vor dem Laden eines anderen Sprachmodells). */
    fun releaseNow() {
        cancel()
        synchronized(lock) {
            llm?.close()
            llm = null
            llmSetId = null
        }
    }

    fun citations(answer: String): List<Int> = AskPrompt.citations(answer)
}
