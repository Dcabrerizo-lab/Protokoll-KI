# Schnittstellen Version 3 (Vorlagen, Fragen an alle Besprechungen, eigenes Wörterbuch)

Leitlinie unverändert: effizient und benutzerfreundlich, ohne Erklärung verständlich, 100 % offline.

## Datenmodell (bereits geändert)
- `data/Model.kt`: `enum class ProtocolTemplate(label, description, documentTitle, signatures: List<String>)`
  mit ALLGEMEIN, DIENSTBESPRECHUNG, FALLBESPRECHUNG, UEBERGABE, VEREINSSITZUNG; `ProtocolTemplate.of(name)`.
- `Meeting.template: ProtocolTemplate`, `Meeting.location`, `Meeting.chair` (Sitzungsleitung/Leitung),
  `Meeting.minuteTaker` (Protokollführung) – alles Strings, leer = nicht angegeben.
- `SettingsState.defaultTemplate`, `SettingsState.vocabulary: List<String>` (eigene Fachbegriffe/Namen),
  `SettingsState.organization` (Einrichtung, erscheint im Kopf formaler Protokolle).

## Engine (Integrator)
- `ProcessingService` berücksichtigt `meeting.template` (Gliederung, Abstimmungen bei Vereinssitzung) und das
  Wörterbuch (`settings.vocabulary` + Namen der Stimmprofile): phonetische Korrektur des Transkripts und
  korrekte Schreibweise im Protokoll.
- `engine/Vocabulary` (object): `fun correct(text: String, terms: List<String>): String`,
  `fun terms(): List<String>` (Wörterbuch + Stimmprofil-Namen).
- `engine/MeetingSearch` (object, reine Logik):
  `data class SearchHit(meetingId: String, title: String, createdAt: Long, segment: Int /*-1 = aus Protokoll*/,
  start: Float, speaker: String, text: String, score: Double)`;
  `fun search(meetings: List<Meeting>, query: String, limit: Int = 20): List<SearchHit>` – sofort, ohne KI.
- `engine/AskEngine` (object):
  `data class AskState(question: String = "", hits: List<SearchHit> = emptyList(), answer: String = "",
  answering: Boolean = false, progressText: String = "", error: String? = null)`.
  `answer` enthält Quellenmarker "[1]", "[2]" … = `hits[n-1]`.
  `val state: StateFlow<AskState>`, `fun ask(context: Context, question: String)` (Treffer sofort, KI-Antwort
  danach), `fun cancel()`, `fun clear()`, `fun release()` (Modell freigeben, beim Verlassen des Bildschirms),
  `fun citations(answer: String): List<Int>` (1-basierte Marker in Reihenfolge).
  Während eine Besprechung verarbeitet wird, liefert `ask` nur Treffer und setzt
  `error = "Die KI-Antwort ist verfügbar, sobald die laufende Verarbeitung fertig ist."`.
