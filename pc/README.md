# Protokoll KI für Windows und Linux

Das eigenständige PC-Programm zur Handy-App. Gleiche Vorlagen, gleiche KI-Anweisungen, komplett offline.

**Installieren** (alles unter [pc-latest](../../../releases/tag/pc-latest)):
- **Windows 10/11:** `ProtokollKI-PC-Setup.exe` herunterladen und öffnen. Keine Administratorrechte nötig.
- **Ubuntu, Linux Mint, Debian:** `protokoll-ki_….deb` herunterladen und doppelklicken (oder `sudo apt install ./protokoll-ki_*.deb`).
  Danach im Startmenü „Protokoll KI“.
- **Andere Linux-Systeme:** `ProtokollKI-x86_64.AppImage` herunterladen, Rechtsklick → Eigenschaften →
  „Als Programm ausführen“ erlauben, doppelklicken. Unter Linux öffnet sich das Programm als Fenster von
  Chrome/Chromium (falls vorhanden) oder im Standardbrowser; beenden über Einstellungen → „Programm beenden“.

Beim ersten Start erkennt das Programm Arbeitsspeicher, Prozessor und Grafikkarte und wählt selbst die schnellste
Einstellung bei bester Qualität: mit eigener Grafikkarte (ab 8 GB) das große Modell auf der Grafikkarte, sonst das
etwas kleinere, auf dem Prozessor doppelt so schnelle Modell. Dann lädt es einmalig die KI (2,5 bis 6 GB).
Danach braucht es kein Internet mehr.

## Drei Wege
1. **Nur Handy**: wie bisher in der App.
2. **Nur PC**: im Programm auf „Aufnehmen“ klicken (PC-Mikrofon) oder eine Audio-/Videodatei hineinziehen.
3. **Handy aufnehmen, PC auswerten**: in der App „Am PC auswerten“ tippen und die `.protokoll`-Datei per Quick Share
   oder USB-Kabel an den PC schicken. Das Programm findet sie im Download-Ordner von selbst. Das fertige Protokoll
   kann als `… (fürs Handy).protokoll` zurück in die App.

Ergebnisse (Word, PDF, Markdown) liegen in `Dokumente/Protokoll KI`.

## Technik
Python, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper, pyannote, Sprecher-Embeddings) und
`llama-server` aus [llama.cpp](https://github.com/ggml-org/llama.cpp), der nur auf `127.0.0.1` lauscht. Oberfläche als
lokale Webseite im eigenen Fenster (pywebview / WebView2). `pkpc/generator.py` ist ein Port von
`ProtocolGenerator.kt`; `tests/test_parity.py` stellt sicher, dass Prompts und Grammatiken wortgleich bleiben.

Entwickeln: `pip install -r requirements.txt`, dann `python run.py` (Fenster) oder `python run.py --cli datei.wav`.
Gebaut wird per GitHub Actions (`.github/workflows/pc.yml`) auf Windows und Linux (`tools/build_linux.sh`),
jeweils mit Ende-zu-Ende-Test einer Testbesprechung.
