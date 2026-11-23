"""Gibt alle Modell-URLs aus ModelCatalog.kt aus (für die Erreichbarkeitsprüfung in der CI)."""
import re, pathlib
src = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/de/protokollki/engine/ModelCatalog.kt"
s = src.read_text()
consts = dict(re.findall(r'const val (\w+) = "([^"]+)"', s))
consts.update(dict(re.findall(r'private const val (\w+) = "([^"]+)"', s)))
for raw in re.findall(r'url = "([^"]+)"', s):
    url = re.sub(r'\$(\w+)', lambda m: consts.get(m.group(1), m.group(0)), raw)
    if "$name" in raw or "{" in url:
        continue
    print(url)
# Whisper-Archive (werden per Funktion zusammengesetzt)
for name in re.findall(r'whisper\("(\w+)"', s):
    print(f'{consts["GH"]}/asr-models/sherpa-onnx-whisper-{name}.tar.bz2')
