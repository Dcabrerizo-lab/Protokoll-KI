# Interne Schnittstellen (für parallele Entwicklung)

Package root: `de.protokollki`. Kotlin, Jetpack Compose (Material 3, BOM 2025.06.00),
minSdk 28, targetSdk 36. Keine weiteren Bibliotheken hinzufügen außer den in
`app/build.gradle.kts` gelisteten. Alle UI-Texte auf Deutsch.

## Bereits vorhanden (nicht ändern, nur benutzen)
- `data/Model.kt`: `Meeting`, `Segment`, `Speaker`, `TaskItem`, `Summary`, `Topic`,
  `ProtocolMode` (ERGEBNIS, WORT; `.label`), `MeetingStatus`.
- `data/MeetingRepository` (object): `meetings: StateFlow<List<Meeting>>`, `create(title, status)`,
  `get(id)`, `save(m)`, `update(id, persist=true) { it.copy(...) }`, `delete(id)`, `dirFor(id)`.
- `data/AppSettings` (object): `state: StateFlow<SettingsState>`, `update { it.copy(...) }`.
  Felder: defaultMode, autoProcess, language, modelSetId, onboardingDone.
- `engine/ModelCatalog`: `sets: List<ModelSet>` (id, label, description, downloadBytes),
  `recommended(context)`, `totalRamGb(context)`.
- `engine/ModelManager` (object): `state: StateFlow<ModelState>` mit
  `set, recommended, installed, downloading, progress (0..1), status, error, ramGb`;
  `selectSet(id: String?)`, `freeBytes()`, `missingBytes()`, `deleteUnused()`.

## Vom Kern-Entwickler (wird parallel geschrieben)
- `service/DownloadService`: `DownloadService.start(context)`, `DownloadService.cancel(context)`.
  Startet den Modell-Download; Fortschritt über `ModelManager.state`.
- `service/ProcessingService`:
  - `ProcessingService.process(context, meetingId)` – komplette Verarbeitung
    (Sprechererkennung + Transkription + Protokoll + Aufgaben). Status/Fortschritt landen
    über `MeetingRepository.update` im Meeting (`status`, `progress`, `progressText`).
  - `ProcessingService.regenerate(context, meetingId)` – nur Protokoll/Aufgaben neu
    (z. B. nach Umbenennen der Sprecher oder Moduswechsel).
  - `ProcessingService.cancel(context)`.
- `ProtokollApp` (Application): initialisiert AppSettings, MeetingRepository, ModelManager
  und Notification-Channels: `ProtokollApp.CHANNEL_RECORDING`, `ProtokollApp.CHANNEL_WORK`.

## Agent „Audio & Export“
- `audio/WavIO.kt`:
  - `class WavWriter(file: File)` 16 kHz mono PCM16, `write(samples: ShortArray, n: Int)`,
    `close()` (Header korrigieren). Header muss auch nach Absturz reparierbar sein:
    `WavIO.repairHeader(file)`.
  - `object WavIO { fun readMono16k(path: String): FloatArray; fun durationSec(path: String): Float; fun repairHeader(file: File) }`
- `audio/Recorder.kt` + `audio/RecordingService.kt` (Foreground-Service, Typ microphone):
  - `object Recorder { val state: StateFlow<RecState>; fun start(context: Context): String /* meetingId */; fun pause(); fun resume(); fun stop(context: Context) }`
  - `data class RecState(val meetingId: String? = null, val active: Boolean = false, val paused: Boolean = false, val elapsedMs: Long = 0, val levels: List<Float> = emptyList() /* letzte ~120 Pegel 0..1 */)`
  - Aufnahme mit `AudioRecord` 16 kHz mono (Quelle `VOICE_RECOGNITION`, Fallback `MIC`),
    schreibt direkt in `meeting.audioFile`. Titel: "Besprechung TT.MM.JJJJ HH:mm".
  - Bei `stop`: Meeting auf RECORDED, `durationSec` setzen; wenn `AppSettings.autoProcess`
    → `ProcessingService.process(context, id)`.
- `audio/AudioImporter.kt`:
  `suspend fun importAudio(context: Context, uri: Uri): Meeting` – beliebige Audio/Video-Datei
  (MediaExtractor + MediaCodec) nach 16 kHz mono WAV dekodieren (Resampling + Downmix),
  Meeting anlegen (Titel = Dateiname ohne Endung), Status RECORDED, `durationSec`.
- `export/Exporter.kt`:
  - `enum class ExportFormat(val label: String, val ext: String, val mime: String) { PDF, DOCX, MARKDOWN, TEXT }`
  - `object Exporter { fun export(context: Context, m: Meeting, f: ExportFormat): File /* in cacheDir/exports */; fun share(context: Context, m: Meeting, f: ExportFormat); fun plainText(m: Meeting): String }`
  - FileProvider-Authority: `"${context.packageName}.fileprovider"`, Pfad `cacheDir/exports/`.
  - Inhalt: Titel, Datum, Dauer, Teilnehmende; bei ERGEBNIS: Zusammenfassung, Themen, Beschlüsse,
    offene Punkte, Aufgaben-Tabelle; bei WORT: Aufgaben + kompletter Wortlaut mit Sprecher und
    Zeitstempel. PDF über `android.graphics.pdf.PdfDocument` (A4, saubere Typografie, Seitenumbruch,
    Seitenzahlen). DOCX selbst als ZIP mit minimalem WordprocessingML schreiben (Überschriften,
    Listen, Tabelle).

## Agent „UI“
- `MainActivity` + `ui/…`: Navigation, Theme, alle Screens (siehe Auftrag).
- Nutzt nur die oben gelisteten APIs.
