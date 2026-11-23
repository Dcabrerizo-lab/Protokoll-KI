# Schnittstellen Version 2 (Zusatzfunktionen)

Ziel des Nutzers: **effizient und benutzerfreundlich**. Jede Funktion muss ohne Erklärung
verständlich sein, mit sinnvollen Voreinstellungen, so wenig Tippen wie möglich.
Alles bleibt 100 % offline.

## Datenmodell (bereits geändert, `data/Model.kt`, `data/AppSettings.kt`)
- `Speaker.profileId: String?` – Sprecher wurde per Stimmprofil automatisch erkannt.
- `TaskItem.id: String` (stabil), `TaskItem.ref: Int` (Index in `meeting.segments`, -1 = keiner),
  `TaskItem.doneNote: String?` (z. B. "Erledigt laut Besprechung vom 30.11.2026").
- `FollowUp(taskId, meetingId, text, owner, done, ref)` – Stand offener Aufgaben aus früheren
  Besprechungen; `Meeting.followUps`.
- `Topic.refs: List<Int>` (je Punkt, `topic.refAt(i)`), `Summary.decisionRefs`, `Summary.openPointRefs`.
- `Meeting.agenda: List<String>` – Tagesordnung.
- `Meeting.sensitiveTerms: List<String>` – zu schwärzende Begriffe dieser Besprechung
  (automatisch vom Sprachmodell erkannt, vom Nutzer bearbeitbar).
- `Meeting.audioDeleted: Boolean` – Audio per Löschfrist entfernt (Player ausblenden, Quellen-Sprung
  zeigt dann nur den Transkript-Abschnitt).
- `SettingsState`: `appLock`, `blockScreenshots`, `audioRetentionDays` (0 = nie), `customTerms`
  (immer schwärzen), `trackTasks`.

## Kern (Integrator schreibt)
- `engine/VoiceProfiles` (object):
  - `data class VoiceProfile(val id: String, val name: String, val createdAt: Long, val samples: Int)`
  - `val profiles: StateFlow<List<VoiceProfile>>`
  - `suspend fun enrollFromRecording(context: Context, name: String, samples: FloatArray /*16 kHz*/): VoiceProfile`
    (mind. 8 s Sprache, wirft `IllegalArgumentException` mit deutscher Meldung sonst)
  - `suspend fun enrollFromMeeting(context: Context, meetingId: String, speakerId: Int, name: String): VoiceProfile`
    (nutzt die bei der Verarbeitung gespeicherte Stimme dieses Sprechers; danach wird der Sprecher
    in der Besprechung umbenannt und `profileId` gesetzt)
  - `fun canEnrollFromMeeting(meetingId: String, speakerId: Int): Boolean`
  - `fun rename(id: String, name: String)`, `fun delete(id: String)`
- `ProcessingService` erkennt Stimmprofile, Aufgaben-Stand (followUps, markiert erledigte Aufgaben
  in den Ursprungs-Besprechungen mit `done=true, doneNote`), Quellen-Refs, sensible Begriffe.
  Nutzt `meeting.agenda`.
- `data/TaskTracker` (object): `fun openTasks(): List<Pair<Meeting, TaskItem>>` (alle offenen Aufgaben
  aller Besprechungen, neueste zuerst), `fun setDone(meetingId: String, taskId: String, done: Boolean)`.

## Agent „Datenschutz, Export, Vorlesen“
- `privacy/Redactor` (object, reine Logik ohne android.*):
  `fun redact(m: Meeting, extraTerms: List<String>): Meeting` – ersetzt in allen Texten
  (Segmente, Zusammenfassung, Themen, Beschlüsse, offene Punkte, Aufgaben, followUps, Titel)
  konsistent: Begriffe aus `m.sensitiveTerms + extraTerms` → "Person A", "Person B" … (Personen) bzw.
  "[geschwärzt]"; zusätzlich per Regex: Telefonnummern → "[Telefon]", E-Mail → "[E-Mail]",
  IBAN → "[IBAN]", Geburtsdaten ("geb. 01.02.1950", "geboren am …") → "[Geburtsdatum]",
  Straße + Hausnummer → "[Adresse]". Groß-/Kleinschreibung und Genitiv-s ("Müllers") beachten.
  Teilnehmernamen (Speaker) bleiben, außer sie stehen in der Liste.
  `fun preview(m: Meeting, extraTerms: List<String>): List<Pair<String, String>>` (Original → Ersatz).
- `export/Exporter.export(context, m, f, anonymized: Boolean = false)` und `share(..., anonymized)`;
  anonymisiert = `Redactor.redact` vor dem Export, Dateiname mit "-anonymisiert".
- `privacy/Retention` (object): `fun apply(context: Context)` – löscht `audio.wav` von Besprechungen,
  die älter als `audioRetentionDays` sind (setzt `audioDeleted = true`); `fun deleteAll(context)`.
- `privacy/PrivacyReport` (object): `fun create(context: Context): File` – PDF "Datenschutz-Nachweis":
  App-Version, Gerät, Verarbeitungsort (nur auf dem Gerät), Netzwerknutzung (nur einmaliger
  Modell-Download, Quellen-URLs aus `ModelCatalog`), Speicherort, Cloud-Backup deaktiviert,
  Verschlüsselung (Android-Dateiverschlüsselung), App-Sperre an/aus, Löschfrist, Anzahl Besprechungen,
  Liste installierter Modelle mit Größe, Datum. Teilen über `Exporter`-FileProvider-Mechanik.
- `privacy/AppLock` (object): framework `android.hardware.biometrics.BiometricPrompt` (API 28+;
  ab API 30 `setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`,
  auf 28/29 `setDeviceCredentialAllowed(true)`), `fun authenticate(activity: Activity, onSuccess: () -> Unit, onFail: (String) -> Unit)`,
  `val locked: StateFlow<Boolean>`, `fun onAppBackgrounded()`, `fun onAppForegrounded()` (sperrt nach
  > 60 s im Hintergrund), `fun isAvailable(context): Boolean`.
- `tts/ReadAloud` (object): Android `TextToSpeech`, deutsche Stimme, bevorzugt offline
  (`!voice.isNetworkConnectionRequired`), `QUEUE_ADD` in Sätzen ≤ 3000 Zeichen.
  `val state: StateFlow<ReadAloudState>` (`active, paused, title, index, total, error`),
  `fun speakMeeting(context, m: Meeting)` (Text: Titel, Datum, Zusammenfassung, Beschlüsse,
  Aufgaben „Frau Becker: Angebote einholen, bis Freitag, 27. November“; im Wortprotokoll-Modus nur
  Zusammenfassung falls vorhanden, sonst Aufgaben), `fun pause()`, `fun resume()` (ab aktuellem Satz),
  `fun stop()`, `fun setSpeed(f: Float)`, `fun skip(delta: Int)`.

## Agent „Oberfläche v2“
Siehe Auftrag. Nutzt nur die oben gelisteten APIs.
