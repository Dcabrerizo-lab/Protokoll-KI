"""Erzeugt die Test-Audios aller Szenarien mit Offline-TTS (sherpa-onnx + Piper-Stimmen).

Aufruf: python make_audio.py <voices_dir> <out_dir>
Ergebnis je Szenario: <name>.wav (16 kHz mono), <name>.json (Referenz: Sprecher, Zeiten, Text, Erwartungen).
Varianten: *_laerm (Störgeräusch ~10 dB SNR), *_leise (-26 dB), lang (~7 min), stille, kurz.
"""
import json
import os
import sys

import numpy as np
import scipy.signal as ss
import sherpa_onnx
import soundfile as sf

sys.path.insert(0, os.path.dirname(__file__))
from scenarios import LONG_PARTS, SCENARIOS, VOICES  # noqa: E402

VOICE_DIR, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(42)

tts = {}
for key, v in VOICES.items():
    d = f"{VOICE_DIR}/vits-piper-de_DE-{v}"
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=f"{d}/de_DE-{v}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
        num_threads=4))
    tts[key] = sherpa_onnx.OfflineTts(cfg)


def synth(lines, speed=1.05):
    out, ref, t = [], [], 0.0
    for spk, text in lines:
        a = tts[spk].generate(text, sid=0, speed=speed)
        s = ss.resample_poly(np.array(a.samples, dtype=np.float32), 16000, a.sample_rate).astype(np.float32)
        s /= max(1e-6, np.abs(s).max()) / 0.6
        ref.append({"spk": spk, "start": round(t, 2), "end": round(t + len(s) / 16000, 2), "text": text})
        out.append(s)
        t += len(s) / 16000
        gap = float(rng.uniform(0.35, 0.9))
        out.append(np.zeros(int(16000 * gap), dtype=np.float32))
        t += gap
    audio = np.concatenate(out)
    audio += rng.standard_normal(len(audio)).astype(np.float32) * 0.003
    return audio, ref


def write(name, audio, meta):
    sf.write(f"{OUT}/{name}.wav", np.clip(audio, -1, 1), 16000, subtype="PCM_16")
    json.dump(meta, open(f"{OUT}/{name}.json", "w"), ensure_ascii=False, indent=1)
    print(f"{name}: {len(audio) / 16000:.1f} s")


cache = {}
for name, sc in SCENARIOS.items():
    audio, ref = synth(sc["lines"])
    cache[name] = (audio, ref)
    write(name, audio, {"scenario": name, "template": sc["template"], "agenda": sc.get("agenda", []),
                        "after": sc.get("after"), "ref": ref, "expect": sc["expect"]})

# Störgeräusche: Rauschen + tieffrequentes Brummen + "Stimmengewirr" (gefilterte Rauschbursts), SNR ~10 dB
base, ref = cache["drei_personen"]
p_sig = np.mean(base ** 2)
noise = rng.standard_normal(len(base)).astype(np.float32)
noise = ss.lfilter(*ss.butter(2, [300 / 8000, 3000 / 8000], "band"), noise).astype(np.float32)
hum = 0.5 * np.sin(2 * np.pi * 50 * np.arange(len(base)) / 16000).astype(np.float32)
mix = noise + hum
mix *= np.sqrt(p_sig / (10 ** (10 / 10)) / np.mean(mix ** 2))
sc = SCENARIOS["drei_personen"]
write("drei_personen_laerm", base + mix, {"scenario": "drei_personen_laerm", "template": "ALLGEMEIN", "agenda": [],
                                          "ref": ref, "expect": {**sc["expect"], "speakers": [2, 4]}})
write("drei_personen_leise", base * 0.05, {"scenario": "drei_personen_leise", "template": "ALLGEMEIN", "agenda": [],
                                           "ref": ref, "expect": sc["expect"]})

# Lange Besprechung
parts, refs, off = [], [], 0.0
for p in LONG_PARTS:
    a, r = cache[p]
    parts.append(a)
    refs += [{**x, "start": x["start"] + off, "end": x["end"] + off} for x in r]
    off += len(a) / 16000
long_audio = np.concatenate(parts)
write("lang", long_audio, {"scenario": "lang", "template": "ALLGEMEIN", "agenda": [], "ref": refs,
                           "expect": {"speakers": [3, 8], "keywords": ["Sommerfest", "Tour", "Rollator"],
                                      "task_owners": ["Becker"], "min_tasks": 4, "decisions_any": []}})

# Randfälle
write("stille", rng.standard_normal(16000 * 20).astype(np.float32) * 0.002,
      {"scenario": "stille", "template": "ALLGEMEIN", "agenda": [], "ref": [], "expect": {"error": True}})
write("kurz", cache["zwei_personen"][0][: int(16000 * 0.6)],
      {"scenario": "kurz", "template": "ALLGEMEIN", "agenda": [], "ref": [], "expect": {"error": True}})
