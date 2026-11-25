# Lizenz-Audit: kommerzielle Nutzbarkeit

Stand 25.11.2026. Keine Rechtsberatung; bei Unsicherheit anwaltlich prüfen lassen.

## KI-Modelle (werden beim ersten Start heruntergeladen)
| Modell | Paket | Lizenz | Kommerziell | Pflichten |
|---|---|---|---|---|
| Qwen3 8B (Q4_K_M) | Ultra | Apache-2.0 | ✅ | Lizenzhinweis |
| Qwen3 4B Instruct 2507 (Q4_K_M) | Premium | Apache-2.0 | ✅ | Lizenzhinweis |
| Qwen3 4B Instruct 2507 (Q3_K_M) | Standard (neu) | Apache-2.0 | ✅ | Lizenzhinweis |
| ~~Qwen2.5 3B Instruct~~ | Standard (alt) | Qwen Research License | ❌ **nur nicht-kommerziell** | ersetzt |
| Qwen2.5 1.5B Instruct | Kompakt | Apache-2.0 | ✅ | Lizenzhinweis |
| NVIDIA Parakeet TDT 0.6B v3 | alle | CC BY 4.0 | ✅ | Namensnennung, Änderungen angeben (ONNX/int8) |
| pyannote segmentation 3.0 | alle | MIT | ✅ | Lizenzhinweis |
| WeSpeaker ResNet34-LM (VoxCeleb) | alle | CC BY 4.0 (wie Datensatz) | ✅ | Namensnennung |
| Whisper (nur Spiegel, derzeit ungenutzt) | – | MIT | ✅ | Lizenzhinweis |

## Bibliotheken
| Komponente | Lizenz | Kommerziell | Hinweis |
|---|---|---|---|
| llama.cpp / ggml | MIT | ✅ | |
| sherpa-onnx, kaldi-native-fbank | Apache-2.0 | ✅ | |
| ONNX Runtime | MIT | ✅ | |
| AndroidX, Compose, Kotlin, Coroutines | Apache-2.0 | ✅ | |
| Apache Commons Compress, PdfBox-Android | Apache-2.0 | ✅ | |
| Python, NumPy, psutil, pywebview | PSF / BSD | ✅ | |
| python-docx, sounddevice, PortAudio | MIT | ✅ | |
| PyAV + FFmpeg | BSD + **LGPL 2.1+** | ✅ mit Auflagen | FFmpeg-DLLs liegen austauschbar im Programmordner; Quelltext-Hinweis steht in den Lizenzen |
| fpdf2 | **LGPL 3.0** | ✅ mit Auflagen | Austauschbarkeit: Option für später, fpdf2 als lose Dateien statt im PyInstaller-Archiv auszuliefern, oder Wechsel auf reportlab (BSD) |
| PyInstaller-Bootloader | GPL-2.0 mit Ausnahme | ✅ | Ausnahme erlaubt Weitergabe |
| DejaVu-Schriften | Bitstream Vera | ✅ | |
| Inno Setup | Inno Setup License | ✅ | auch kommerziell frei |

## Modell-Downloads
Die App lädt Modelle direkt von Hugging Face bzw. GitHub (k2-fsa/sherpa-onnx und Spiegel `models-v1` dieses Repos). Weitergabe über den eigenen Spiegel ist bei Apache/MIT/CC BY erlaubt, solange die Lizenzhinweise mitgeliefert werden (erledigt über die Lizenzseite in App und PC).
