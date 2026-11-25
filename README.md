# Protokoll KI

Eine Android-App, die Besprechungen **komplett offline auf dem Handy** protokolliert.
Keine Cloud, kein Konto, keine Daten verlassen das Gerät.

## Funktionen
- **Aufnahme** direkt in der App (läuft auch bei ausgeschaltetem Bildschirm) oder **Import** von Audio-/Videodateien
- **Transkription** mit Whisper (bis large-v3-turbo)
- **Sprechererkennung**: wer hat wann gesprochen, Personen umbenennen und zusammenführen
- **Ergebnisprotokoll** (Zusammenfassung, Themen, Beschlüsse, offene Punkte) oder **Wortprotokoll**
- **Aufgaben**: wer macht was bis wann, abhakbar
- **Export** als PDF, Word, Markdown oder Text
- **Aufgaben-Verfolgung** über Besprechungen hinweg: offene Aufgaben werden abgeglichen und automatisch abgehakt
- **Fragen an alle Besprechungen**: Volltextsuche plus KI-Antwort mit Quellen, ein Tipp springt zur Stelle
- **Quellenangabe**: jeder Protokollpunkt springt zur passenden Stelle in Aufnahme und Transkript
- **Formale Vorlagen**: Dienst-/Fallbesprechung, Übergabe, Vereinssitzung (Abstimmungen), mit Unterschriftsfeld
- **Tagesordnung** vorab eingeben, das Protokoll folgt den TOPs
- **Stimmprofile**: bekannte Stimmen werden automatisch mit Namen erkannt
- **Eigenes Wörterbuch** für Namen und Fachbegriffe
- **Vorlesen** von Protokoll und Aufgaben (Offline-Sprachausgabe)
- **Anonymisierter Export**: Namen, Telefon, E-Mail, IBAN, Adressen, Geburtsdaten werden geschwärzt
- **Import alter Protokolle** (PDF, Word, Text): durchsuchbar, Aufgaben werden übernommen
- **Datenschutz-Paket**: App-Sperre, Screenshot-Schutz, Löschfrist für Audio, Datenschutz-Nachweis als PDF, alles löschen

## Installation
1. Auf dem Handy die [neueste Version](../../releases/tag/latest) öffnen und `ProtokollKI.apk` herunterladen.
2. Datei öffnen und „Installieren“ tippen (einmalig „Aus dieser Quelle installieren“ erlauben).
3. Beim ersten Start die KI-Modelle herunterladen (einmalig, WLAN empfohlen). Danach läuft alles offline.

## Modellpakete
Die App wählt automatisch das passende Paket nach Arbeitsspeicher, in den Einstellungen änderbar.

| Paket | Passend für | Spracherkennung | Sprachmodell | Download |
|---|---|---|---|---|
| Ultra | z. B. Galaxy S24 Ultra, S25 Ultra, Pixel 9 Pro (ab 12 GB RAM) | Whisper large-v3-turbo | Qwen3 8B | ca. 5,6 GB |
| Premium | z. B. Galaxy S23, S23+, S24, Pixel 8 (8 GB RAM) | Whisper large-v3-turbo | Qwen3 4B Instruct | ca. 3,1 GB |
| Standard | z. B. Galaxy A54, A55, Pixel 6a (6 GB RAM) | Parakeet | Qwen3 4B (Q3) | ca. 2,8 GB |
| Kompakt | ältere Handys (4 GB RAM) | Whisper base | Qwen2.5 1.5B | ca. 1,4 GB |

## Datenschutz
- Die Internet-Berechtigung wird ausschließlich für den einmaligen Modell-Download genutzt.
- Aufnahmen, Transkripte und Protokolle liegen nur im privaten App-Speicher; Cloud-Backup ist deaktiviert.
- Datenschutzerklärung, Lizenzen und Impressum: in der App unter Einstellungen → Über (Texte in `app/src/main/assets/legal/`).

## Verkauf
Checkliste, Lizenz-Audit, Signatur und Play-Store-Texte: [docs/vertrieb](docs/vertrieb/README.md).

## Technik
Kotlin + Jetpack Compose · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper, pyannote-Segmentierung, Sprecher-Embeddings) · [llama.cpp](https://github.com/ggml-org/llama.cpp) für das lokale Sprachmodell (GGUF).
Gebaut wird automatisch per GitHub Actions (`.github/workflows/build.yml`).
