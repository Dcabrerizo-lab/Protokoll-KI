"""Sprechererkennung und Transkription mit sherpa-onnx (Port von engine/Transcriber.kt)."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, replace
from typing import Callable

import numpy as np

from . import audio, catalog
from .model import Segment

RATE = 16000


def _threads() -> int:
    from . import hardware
    return hardware.asr_threads(hardware.info())


@dataclass
class Turn:
    start: float
    end: float
    speaker: int


def diarize(samples: np.ndarray, num_speakers: int, on_progress: Callable[[float], bool]) -> list[Turn]:
    import sherpa_onnx as so

    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(catalog.path(catalog.SEGMENTATION))),
            num_threads=_threads(),
        ),
        embedding=so.SpeakerEmbeddingExtractorConfig(
            model=str(catalog.path(catalog.EMBEDDING)), num_threads=_threads()),
        clustering=so.FastClusteringConfig(num_clusters=num_speakers if num_speakers > 0 else -1,
                                           threshold=0.35),
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    if not cfg.validate():
        raise RuntimeError("Modelle für die Sprechererkennung fehlen oder sind beschädigt.")
    sd = so.OfflineSpeakerDiarization(cfg)

    def cb(done: int, total: int) -> int:
        return 0 if on_progress(done / total if total else 0.0) else 1

    result = sd.process(audio.normalize(samples), callback=cb).sort_by_start_time()
    return [Turn(r.start, r.end, r.speaker) for r in result]


def plan_chunks(turns: list[Turn], samples: np.ndarray) -> list[Turn]:
    total = len(samples) / RATE
    if not turns:
        return _split_long(Turn(0.0, total, 0), samples)
    merged: list[Turn] = []
    for raw in turns:
        last = merged[-1] if merged else None
        t = replace(raw, start=last.end) if last and raw.start < last.end and raw.speaker != last.speaker else raw
        if t.end - t.start <= 0:
            continue
        if last and last.speaker == t.speaker and t.start - last.end < 1.0:
            merged[-1] = replace(last, end=max(last.end, t.end))
        elif last and t.end <= last.end:
            continue
        else:
            merged.append(t)
    cleaned: list[Turn] = []
    for t in merged:
        if t.end - t.start < 0.6 and cleaned:
            cleaned[-1] = replace(cleaned[-1], end=max(cleaned[-1].end, t.end))
        else:
            cleaned.append(t)
    remap: dict[int, int] = {}
    out = []
    for t in cleaned:
        sid = remap.setdefault(t.speaker, len(remap))
        out += _split_long(replace(t, speaker=sid), samples)
    return out


def _split_long(t: Turn, samples: np.ndarray) -> list[Turn]:
    out, start = [], t.start
    while t.end - start > 28.0:
        cut = _quietest(samples, start + 18.0, start + 26.0)
        out.append(Turn(start, cut, t.speaker))
        start = cut
    out.append(Turn(start, t.end, t.speaker))
    return out


def _quietest(samples: np.ndarray, a: float, b: float) -> float:
    win = RATE // 10
    best, best_e = b, float("inf")
    pos, end = int(a * RATE), min(int(b * RATE), len(samples) - win)
    while pos < end:
        e = float(np.dot(samples[pos:pos + win], samples[pos:pos + win]))
        if e < best_e:
            best_e, best = e, (pos + win / 2) / RATE
        pos += win // 2
    return best


class Recognizer:
    def __init__(self, whisper_prefix: str, language: str = "de"):
        import sherpa_onnx as so

        p = whisper_prefix
        self.rec = so.OfflineRecognizer.from_whisper(
            encoder=str(catalog.path(f"{p}-encoder.int8.onnx")),
            decoder=str(catalog.path(f"{p}-decoder.int8.onnx")),
            tokens=str(catalog.path(f"{p}-tokens.txt")),
            language=language, task="transcribe", num_threads=_threads(), tail_paddings=1000,
        )

    def text(self, samples: np.ndarray) -> str:
        s = self.rec.create_stream()
        s.accept_waveform(RATE, samples)
        self.rec.decode_stream(s)
        return s.result.text


def transcribe(samples: np.ndarray, chunks: list[Turn], whisper_prefix: str, language: str,
               on_progress: Callable[[int, int, list[Segment]], bool]) -> list[Segment]:
    rec = Recognizer(whisper_prefix, language)
    segments: list[Segment] = []
    for i, c in enumerate(chunks):
        a = min(max(int(c.start * RATE), 0), len(samples))
        b = min(max(int(c.end * RATE), a), len(samples))
        if b - a >= RATE // 4:
            text = clean(rec.text(samples[a:b]))
            if text and not is_hallucination(text, b - a):
                last = segments[-1] if segments else None
                if last and last.speaker_id == c.speaker and c.start - last.end < 0.3:
                    last.end, last.text = c.end, last.text + " " + text
                else:
                    segments.append(Segment(c.speaker, c.start, c.end, text))
        if not on_progress(i + 1, len(chunks), segments):
            break
    return segments


_HALLUCINATIONS = ["untertitel", "vielen dank fürs zuschauen", "vielen dank für's zuschauen",
                   "bis zum nächsten mal", "copyright", "swr ", "zdf", "ard ", "wdr",
                   "abonniert", "amara.org", "musik", "♪"]


def clean(raw: str) -> str:
    t = re.sub(r"\s+", " ", raw)
    t = re.sub(r"\[.*?]|\(.*?(Musik|Applaus|Lachen).*?\)", "", t, flags=re.IGNORECASE)
    return t.strip()


def is_hallucination(text: str, n_samples: int) -> bool:
    t = text.lower()
    seconds = n_samples / RATE
    if any(h in t for h in _HALLUCINATIONS) and (len(t) < 80 or seconds < 4):
        return True
    words = [w for w in t.split(" ") if w.strip()]
    return len(words) >= 8 and len(set(words)) <= 2
