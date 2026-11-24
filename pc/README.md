# Protokoll KI für Windows

Das eigenständige PC-Programm zur Handy-App. Gleiche Vorlagen, gleiche KI-Anweisungen, komplett offline.

**Installieren:** [ProtokollKI-PC-Setup.exe](../../../releases/tag/pc-latest) herunterladen und öffnen. Keine Administratorrechte nötig.
Beim ersten Start lädt das Programm einmalig die KI (je nach Qualität 2,5 bis 6 GB). Danach braucht es kein Internet mehr.

## Drei Wege
1. **Nur Handy**: wie bisher in der App.
2. **Nur PC**: im Programm auf „Aufnehmen“ klicken (PC-Mikrofon) oder eine Audio-/Videodatei hineinziehen.
3. **Handy aufnehmen, PC auswerten**: in der App „Am PC auswerten“ tippen und die `.protokoll`-Datei per Quick Share
   oder USB-Kabel an den PC schicken. Das Programm findet sie im Download-Ordner von selbst. Das fertige Protokoll
   kann als `… (fürs Handy).protokoll` zurück in die App.

Ergebnisse (Word, PDF, Markdown) liegen in `Dokumente\Protokoll KI`.

## Technik
Python, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper, pyannote, Sprecher-Embeddings) und
`llama-server` aus [llama.cpp](https://github.com/ggml-org/llama.cpp), der nur auf `127.0.0.1` lauscht. Oberfläche als
lokale Webseite im eigenen Fenster (pywebview / WebView2). `pkpc/generator.py` ist ein Port von
`ProtocolGenerator.kt`; `tests/test_parity.py` stellt sicher, dass Prompts und Grammatiken wortgleich bleiben.

Entwickeln: `pip install -r requirements.txt`, dann `python run.py` (Fenster) oder `python run.py --cli datei.wav`.
Gebaut wird per GitHub Actions (`.github/workflows/pc.yml`) auf Windows, inklusive Ende-zu-Ende-Test.
