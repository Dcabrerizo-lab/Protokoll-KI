"""Audio der Stress-Szenarien (wird von make_audio.py mit --stress / --nur-stress / --marathon aufgerufen).

Effekte, alle synthetisch und reproduzierbar (fester Zufallsstartwert):
  hall     Faltung mit einer synthetischen Raumimpulsantwort (frühe Reflexionen + exponentiell
           abklingender Nachhall, RT60 ~0,9 s, Direkt-/Hallanteil ~0 dB)
  fern     Fernmikrofon: leichter Hall, Tiefpass 3 kHz, Pegel -30 dB, Rauschen (~20 dB SNR, Mikrofonrauschen)
  cafe     Stimmengewirr (viele leise, gefilterte TTS-Stimmen) + Geschirrklappern, ~8 dB SNR
  strasse  Verkehrsrauschen, vorbeifahrende Autos, Hupe, ~6 dB SNR
  alles    hall + fern + cafe
Überlappende Sätze: dritter Eintrag einer Dialogzeile = Sekunden, die der Satz früher beginnt.
"""
import json

import numpy as np
import scipy.signal as ss
import sherpa_onnx
import soundfile as sf

from scenarios import MARATHON_MINUTES, SCENARIOS, STRESS, STRESS_ACOUSTIC, STRESS_VOICES

SR = 16000
rng = np.random.default_rng(4711)   # eigener Zufall: Basis-Szenarien bleiben bitgleich

BABBLE = [
    "Hast du schon gehört, dass das Café am Markt bald schließt?",
    "Ich nehme noch einen Milchkaffee und ein Stück Apfelkuchen.",
    "Am Wochenende fahren wir zu meiner Schwester nach Hamburg.",
    "Die Bahn hatte heute wieder zwanzig Minuten Verspätung.",
    "Kannst du mir mal den Zucker herüberreichen?",
    "Das Wetter soll ja ab Mittwoch besser werden.",
    "Mein Sohn fängt nächstes Jahr mit der Ausbildung an.",
    "Wir müssen unbedingt mal wieder zusammen essen gehen.",
]


def _load_tts(voice_dir, name):
    d = f"{voice_dir}/vits-piper-de_DE-{name}"
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=f"{d}/de_DE-{name}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
        num_threads=4))
    return sherpa_onnx.OfflineTts(cfg)


class Voices:
    """Basisstimmen (aus make_audio) + Stress-Stimmen (andere Piper-Stimme oder Tonhöhe/Tempo)."""

    def __init__(self, voice_dir, base_tts):
        self.dir, self.base, self.extra = voice_dir, base_tts, {}

    def say(self, spk, text, speed=1.05):
        if spk in self.base:
            tts, sid, tempo, pitch = self.base[spk], 0, speed, 1.0
        else:
            name, sid, tempo, pitch = STRESS_VOICES[spk]
            if name not in self.extra:
                self.extra[name] = _load_tts(self.dir, name)
            tts = self.extra[name]
        # Tonhöhe: Audio „schneller abspielen“ (Resampling) und Tempo vorab ausgleichen
        a = tts.generate(text, sid=sid, speed=tempo / pitch)
        src = int(round(a.sample_rate * pitch))
        s = ss.resample_poly(np.array(a.samples, dtype=np.float32), SR, src).astype(np.float32)
        return s / (max(1e-6, np.abs(s).max()) / 0.6)


def synth_overlap(voices, lines):
    """Wie make_audio.synth, aber Zeilen (spk, text, overlap) dürfen sich überlappen."""
    pieces, ref, t = [], [], 0.0
    prev_end = 0.0
    for ln in lines:
        spk, text = ln[0], ln[1]
        ov = ln[2] if len(ln) > 2 else 0
        s = voices.say(spk, text)
        dur = len(s) / SR
        start = max(0.0, prev_end - ov) if ov > 0 else prev_end + float(rng.uniform(0.35, 0.9))
        if not ref:
            start = 0.0
        pieces.append((start, s))
        ref.append({"spk": spk, "start": round(start, 2), "end": round(start + dur, 2), "text": text})
        prev_end = max(prev_end, start + dur)
        t = prev_end
    audio = np.zeros(int(SR * (t + 0.6)), dtype=np.float32)
    for start, s in pieces:
        i = int(start * SR)
        audio[i:i + len(s)] += s
    audio /= max(1.0, np.abs(audio).max() / 0.7)
    audio += rng.standard_normal(len(audio)).astype(np.float32) * 0.003
    return audio, ref


# ------------------------------------------------------------------------------- Effekte
def room_ir(rt60=0.9, drr_db=0.0, length=1.2):
    n = int(SR * length)
    t = np.arange(n) / SR
    tail = rng.standard_normal(n) * 10 ** (-3 * t / rt60)
    tail[: int(0.004 * SR)] = 0          # Nachhall beginnt nach dem Direktschall
    for d, g in [(0.007, 0.6), (0.011, 0.5), (0.017, 0.4), (0.023, 0.35), (0.031, 0.3)]:   # frühe Reflexionen
        tail[int(d * SR)] += g * (1 if rng.random() > 0.5 else -1)
    tail = ss.lfilter(*ss.butter(1, 5000 / (SR / 2)), tail)   # Wände schlucken Höhen
    tail *= np.sqrt(10 ** (-drr_db / 10) / np.sum(tail ** 2))
    h = tail
    h[0] += 1.0
    return h.astype(np.float32)


def reverb(x, rt60=0.9, drr_db=0.0):
    y = ss.fftconvolve(x, room_ir(rt60, drr_db))[: len(x)].astype(np.float32)
    return y / (max(1e-6, np.abs(y).max()) / 0.6)


def mix_snr(x, noise, snr_db):
    p_sig = np.mean(x ** 2)
    noise = noise[: len(x)]
    noise = noise * np.sqrt(p_sig / (10 ** (snr_db / 10)) / max(1e-12, np.mean(noise ** 2)))
    return (x + noise).astype(np.float32)


def pink(n):
    w = rng.standard_normal(n)
    b, a = [0.049922035, -0.095993537, 0.050612699, -0.004408786], [1, -2.494956002, 2.017265875, -0.522189400]
    return ss.lfilter(b, a, w).astype(np.float32)


def babble(voices, n):
    out = np.zeros(n, dtype=np.float32)
    keys = list(voices.base)
    clips = [voices.say(keys[i % len(keys)], BABBLE[i]) for i in range(len(BABBLE))]
    for layer in range(6):                      # 6 Gesprächsfetzen gleichzeitig
        pos = int(rng.uniform(0, 2) * SR)
        while pos < n:
            c = clips[int(rng.integers(len(clips)))] * float(rng.uniform(0.5, 1.0))
            c = ss.lfilter(*ss.butter(2, [200 / (SR / 2), 3500 / (SR / 2)], "band"), c).astype(np.float32)
            e = min(n, pos + len(c))
            out[pos:e] += c[: e - pos]
            pos = e + int(rng.uniform(0.2, 1.5) * SR)
    out = reverb(out, rt60=0.7, drr_db=-6)
    # Geschirr/Besteck: kurze, schnell abklingende hohe Töne
    for _ in range(int(n / SR / 3)):
        i = int(rng.integers(0, max(1, n - SR // 4)))
        tt = np.arange(SR // 4) / SR
        f = rng.uniform(2000, 6000)
        out[i:i + len(tt)] += (0.8 * np.sin(2 * np.pi * f * tt) * np.exp(-tt * 40)).astype(np.float32)
    return out


def traffic(n):
    base = ss.lfilter(*ss.butter(2, 400 / (SR / 2)), rng.standard_normal(n)).astype(np.float32)
    base += 0.3 * pink(n)
    t = np.arange(n) / SR
    pos = rng.uniform(2, 8)
    while pos < n / SR:                           # vorbeifahrende Autos
        env = np.exp(-((t - pos) / 1.5) ** 2) * rng.uniform(2, 5)
        car = ss.lfilter(*ss.butter(2, [80 / (SR / 2), 1500 / (SR / 2)], "band"), rng.standard_normal(n))
        base += (env * car).astype(np.float32)
        pos += rng.uniform(6, 15)
    for _ in range(max(1, int(n / SR / 60))):     # gelegentlich eine Hupe
        i = int(rng.integers(0, max(1, n - SR)))
        th = np.arange(int(0.6 * SR)) / SR
        horn = 3 * (np.sign(np.sin(2 * np.pi * 400 * th)) + np.sign(np.sin(2 * np.pi * 500 * th)))
        base[i:i + len(th)] += ss.lfilter(*ss.butter(2, 3000 / (SR / 2)), horn).astype(np.float32)
    return base


def effect(voices, x, kind):
    if kind == "hall":
        return reverb(x, 0.9, 0.0)
    if kind == "fern":
        y = reverb(x, 0.5, -3.0)
        y = ss.lfilter(*ss.butter(4, 3000 / (SR / 2)), y).astype(np.float32)
        y = mix_snr(y, pink(len(y)), 20)
        return (y * 0.03 + rng.standard_normal(len(y)).astype(np.float32) * 0.0001).astype(np.float32)
    if kind == "cafe":
        return mix_snr(x, babble(voices, len(x)), 8)
    if kind == "strasse":
        return mix_snr(x, traffic(len(x)), 6)
    if kind == "alles":
        y = mix_snr(reverb(x, 0.8, -2.0), babble(voices, len(x)), 12)
        y = ss.lfilter(*ss.butter(4, 3000 / (SR / 2)), y).astype(np.float32)
        return (y * 0.05 + rng.standard_normal(len(y)).astype(np.float32) * 0.0001).astype(np.float32)
    raise ValueError(kind)


def run(voice_dir, base_tts, cache, write, written, marathon, out_dir):
    voices = Voices(voice_dir, base_tts)
    produced = list(written)
    for name, sc in STRESS.items():
        audio, ref = synth_overlap(voices, sc["lines"])
        write(name, audio, {"scenario": name, "template": sc["template"], "agenda": sc.get("agenda", []),
                            "ref": ref, "expect": sc["expect"], "stress": True})
        produced.append((name, audio, ref))
    for name, (base, kind, exp) in STRESS_ACOUSTIC.items():
        audio, ref = cache[base]
        sc = SCENARIOS[base]
        y = np.clip(effect(voices, audio, kind), -1, 1)
        write(name, y, {"scenario": name, "template": sc["template"], "agenda": sc.get("agenda", []),
                        "ref": ref, "expect": {**sc["expect"], **exp}, "stress": True, "effect": kind})
        produced.append((name, y, ref))
    if not marathon:
        return
    # Sehr lange Aufnahme: alle Szenarien reihum, bis MARATHON_MINUTES erreicht sind (nur Host-Benchmark)
    target = MARATHON_MINUTES * 60 * SR
    parts, refs, off, i = [], [], 0, 0
    while off < target:
        _, a, r = produced[i % len(produced)]
        parts.append(a)
        refs += [{**x, "start": round(x["start"] + off / SR, 2), "end": round(x["end"] + off / SR, 2)} for x in r]
        off += len(a)
        i += 1
    audio = np.concatenate(parts)
    n_spk = len({x["spk"] for x in refs})
    sf.write(f"{out_dir}/stress_marathon.wav", np.clip(audio, -1, 1), SR, subtype="PCM_16")
    json.dump({"scenario": "stress_marathon", "template": "ALLGEMEIN", "agenda": [], "ref": refs, "stress": True,
               "host_only": True, "vocabulary": [],
               "expect": {"speakers": [max(2, n_spk - 4), n_spk + 2], "keywords": ["Sommerfest", "Dekubitus"],
                          "task_owners": [], "min_tasks": 5, "decisions_any": []}},
              open(f"{out_dir}/stress_marathon.json", "w"), ensure_ascii=False, indent=1)
    print(f"stress_marathon: {len(audio) / SR / 60:.1f} min, {n_spk} Sprecher")
