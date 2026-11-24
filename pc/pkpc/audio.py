"""Audio lesen (beliebige Formate über PyAV/FFmpeg), Pegel angleichen, WAV schreiben."""
from __future__ import annotations

import wave
from pathlib import Path

import numpy as np

RATE = 16000
AUDIO_EXT = {".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wma", ".amr", ".3gp",
             ".mp4", ".mov", ".mkv", ".webm", ".avi"}


def read_mono16k(path: str | Path) -> np.ndarray:
    """Liest Audio/Video und liefert float32-Samples, 16 kHz mono, Bereich -1..1."""
    path = Path(path)
    if path.suffix.lower() == ".wav":
        try:
            return _read_wav(path)
        except (wave.Error, ValueError):
            pass
    import av

    chunks = []
    with av.open(str(path)) as c:
        stream = next((s for s in c.streams if s.type == "audio"), None)
        if stream is None:
            raise ValueError("Die Datei enthält keine Tonspur.")
        res = av.AudioResampler(format="flt", layout="mono", rate=RATE)
        for frame in c.decode(stream):
            for f in res.resample(frame):
                chunks.append(f.to_ndarray().reshape(-1))
        for f in res.resample(None):
            chunks.append(f.to_ndarray().reshape(-1))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.clip(np.concatenate(chunks).astype(np.float32), -1, 1)


def _read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("nicht 16 Bit")
        ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        x = x[: len(x) // ch * ch].reshape(-1, ch).mean(axis=1)
    if rate != RATE:
        x = resample(x, rate, RATE)
    return x.astype(np.float32)


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst or len(x) == 0:
        return x
    n = int(round(len(x) * dst / src))
    t = np.linspace(0, len(x) - 1, n)
    return np.interp(t, np.arange(len(x)), x).astype(np.float32)


def write_wav(path: str | Path, samples: np.ndarray) -> None:
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def gain(samples: np.ndarray) -> float:
    """Wie AudioLevel.kt: lautere Hälfte der 25-ms-Abschnitte auf ca. -20 dBFS, max. 100-fach."""
    frame = 400
    frames = len(samples) // frame
    if frames == 0:
        return 1.0
    rms = np.sqrt((samples[: frames * frame].astype(np.float64).reshape(frames, frame) ** 2).mean(axis=1))
    median = np.sort(rms)[frames // 2]
    loud = rms[rms >= median]
    if len(loud) == 0:
        return 1.0
    level = max(float(loud.mean()), 1e-5)
    return min(0.1 / level, 100.0)


def normalize(samples: np.ndarray) -> np.ndarray:
    g = gain(samples)
    if 0.9 <= g <= 1.1:
        return samples
    return np.clip(samples * g, -1, 1).astype(np.float32)
