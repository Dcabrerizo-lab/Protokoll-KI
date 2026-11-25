"""Sprechererkennung und Transkription mit sherpa-onnx (Port von engine/Transcriber.kt)."""
from __future__ import annotations

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
                                           threshold=catalog.CLUSTER_THRESHOLD),
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


# ------------------------------------------------------------------ Parakeet (Port von TokenAlign.kt)

@dataclass
class Word:
    text: str
    start: float
    end: float


def tokens_to_words(tokens, timestamps, durations, offset: float) -> list[Word]:
    """Teilwörter zu Wörtern; führendes Leerzeichen (oder „▁“) beginnt ein neues Wort."""
    out: list[Word] = []
    text, start, end = "", 0.0, 0.0

    def flush():
        t = text.strip()
        if t:
            out.append(Word(t, start, end))

    pending_space = False  # Parakeet: eigenes Leerzeichen-Token vor Ziffern („am“, „ “, „1“, „8“)
    for i, tok in enumerate(tokens):
        raw = tok.replace("▁", " ")
        if not raw:
            continue
        if not raw.strip():
            pending_space = True
            continue
        if i >= len(timestamps):
            continue
        ts = offset + float(timestamps[i])
        dur = float(durations[i]) if durations is not None and i < len(durations) and durations[i] > 0 else 0.08
        new_word = raw.startswith(" ") or pending_space or not text
        pending_space = False
        if new_word:
            flush()
            text, start = "", ts
        text += raw.lstrip() if new_word else raw
        end = ts + dur
    flush()
    return out


def speaker_at(t: float, turns: list[Turn]) -> int:
    if not turns:
        return 0
    for x in turns:
        if x.start <= t <= x.end:
            return x.speaker
    return min(turns, key=lambda x: min(abs(t - x.start), abs(t - x.end))).speaker


def _join(ws: list[Word]) -> str:
    return re.sub(r" ([.,!?;:])", r"\1", " ".join(w.text for w in ws)).strip()


def words_to_segments(words: list[Word], turns: list[Turn], max_gap: float = 2.0,
                      renumber: bool = True) -> list[Segment]:
    remap: dict[int, int] = {}
    out: list[Segment] = []
    cur: list[Word] = []
    cur_spk = -1

    def flush():
        if cur:
            sid = remap.setdefault(cur_spk, len(remap)) if renumber else cur_spk
            out.append(Segment(sid, cur[0].start, cur[-1].end, _join(cur)))

    for w in words:
        spk = speaker_at((w.start + w.end) / 2, turns)
        last = cur[-1] if cur else None
        sentence_end = last is not None and last.text[-1] in ".?!"
        if last is not None and (spk != cur_spk or (w.start - last.end > max_gap and sentence_end)):
            flush()
            cur = []
        if not cur:
            cur_spk = spk
        cur.append(w)
    flush()
    return _merge_tiny(out)


def _merge_tiny(segs: list[Segment]) -> list[Segment]:
    """Einzelne Wörter, die die Sprechererkennung einem anderen Sprecher zuschlägt, dem Umfeld zuordnen."""
    if len(segs) < 3:
        return segs
    out: list[Segment] = []
    i = 0
    while i < len(segs):
        s, prev = segs[i], (out[-1] if out else None)
        nxt = segs[i + 1] if i + 1 < len(segs) else None
        if (prev and nxt and len(s.text.split(" ")) <= 1 and prev.speaker_id == nxt.speaker_id
                and s.end - s.start < 1.0):
            out[-1] = Segment(prev.speaker_id, prev.start, nxt.end, prev.text + " " + s.text + " " + nxt.text)
            i += 2
            continue
        out.append(s)
        i += 1
    return out


def windows(total: float, quietest: Callable[[float, float], float], min_len: float = 15.0,
            max_len: float = 25.0) -> list[tuple[float, float]]:
    out, start = [], 0.0
    while total - start > max_len:
        cut = min(max(quietest(start + min_len, start + max_len), start + min_len), start + max_len)
        out.append((start, cut))
        start = cut
    if total - start > 0.05:
        out.append((start, total))
    return out


def transcribe_parakeet(samples: np.ndarray, chunks: list[Turn],
                        on_progress: Callable[[int, int, list[Segment]], bool]) -> list[Segment]:
    """Fenster bis 25 s am Stück erkennen, jedes Wort über seinen Zeitstempel dem Sprecher zuordnen."""
    import sherpa_onnx as so
    rec = so.OfflineRecognizer.from_transducer(
        encoder=str(catalog.path("parakeet-encoder.int8.onnx")),
        decoder=str(catalog.path("parakeet-decoder.int8.onnx")),
        joiner=str(catalog.path("parakeet-joiner.int8.onnx")),
        tokens=str(catalog.path("parakeet-tokens.txt")),
        num_threads=_threads(), decoding_method="greedy_search", model_type="nemo_transducer",
    )
    total = len(samples) / RATE
    wins = [w for w in windows(total, lambda a, b: _quietest(samples, a, b))
            if not chunks or any(c.end > w[0] and c.start < w[1] for c in chunks)]
    g = audio.gain(samples)  # Pegelangleich wie in der App (leise Aufnahmen)
    g = 1.0 if 0.9 <= g <= 1.1 else g
    words: list[Word] = []
    segments: list[Segment] = []
    for i, (wa, wb) in enumerate(wins):
        a = min(max(int(wa * RATE), 0), len(samples))
        b = min(max(int(wb * RATE), a), len(samples))
        if b - a >= RATE // 4:
            st = rec.create_stream()
            piece = samples[a:b] if g == 1.0 else np.clip(samples[a:b] * g, -1, 1).astype(np.float32)
            st.accept_waveform(RATE, piece)
            rec.decode_stream(st)
            r = st.result
            words += tokens_to_words(list(r.tokens), list(r.timestamps),
                                     list(getattr(r, "durations", []) or []) or None, wa)
        segments = [Segment(x.speaker_id, x.start, x.end, clean(x.text))
                    for x in words_to_segments(words, chunks, renumber=False)]
        segments = [x for x in segments if x.text]
        if not on_progress(i + 1, len(wins), segments):
            break
    return segments


def transcribe(samples: np.ndarray, chunks: list[Turn], whisper_prefix: str, language: str,
               on_progress: Callable[[int, int, list[Segment]], bool]) -> list[Segment]:
    if whisper_prefix == "parakeet":
        return transcribe_parakeet(samples, chunks, on_progress)
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
