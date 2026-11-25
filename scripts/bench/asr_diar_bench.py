#!/usr/bin/env python3
"""Host-Benchmark für Sprechererkennung (Diarisierung) und Spracherkennung (Parakeet) wie in der App.

Misst je Szenario (WAV + JSON aus scripts/e2e/scenarios/make_audio.py):
  WER         Wortfehlerquote der Parakeet-Transkription gegen den Szenario-Text (Text normalisiert:
              klein, ohne Satzzeichen, Zahlen als Wörter). ASR wie die App seit 314924e auf
              pegelangeglichenem Audio (AudioLevel.gain einmal für die Aufnahme, Clipping ±1)
  WER roh     dasselbe auf dem Rohsignal (Verhalten der App vor 314924e)
  Wort-Spk    Anteil der erkannten Wörter beim falschen Sprecher nach optimaler Zuordnung
              (Zuordnung wie in der App: planChunks + TokenAlign.speakerAt + mergeTiny)
  DER         Diarisierungsfehler auf 10-ms-Rastern (Verpasst + Fehlalarm + Verwechslung, Collar 0,25 s)
  Kleinstspr. Versuch: Sprecher mit < 15 s und < 2 % der Redezeit entfernen, Wörter dem nächsten großen Sprecher geben
  Spk         erkannte Sprecher (in den Segmenten, wie check.py zählt) / echte Sprecher
  Zeiten      Diarisierung, ASR (s) und Echtzeitfaktor (Rechenzeit / Audiodauer)

Gleiche Parameter wie die App (Transcriber.kt, seit 6a87791): Segmentierung pyannote fp32 (model.onnx),
Embedding 3dspeaker CAM++ en voxceleb, Clustering-Schwelle 0,6, minDurationOn 0,3,
minDurationOff 0,5, Pegelangleich vor der Diarisierung (AudioLevel.kt), ASR in Fenstern 15–25 s,
geschnitten am leisesten 100-ms-Punkt. Bis 6a87791: Segmentierung model.int8.onnx + wespeaker resnet34_LM bei 0,35 (mit --seg/--emb vergleichbar).
Abweichung: ASR-Fenster werden nicht nach Sprache gefiltert
(damit die ASR für alle Embedding-Modelle nur einmal läuft).

Beispiel:
  pip install sherpa-onnx==2.0.3 soundfile scipy numpy
  python asr_diar_bench.py --scenarios out/ --models models/ \\
      --emb wespeaker_en_voxceleb_resnet34_LM.onnx:0.35 --emb nemo_en_titanet_small.onnx:0.3,0.4,0.5
Modelle: <models>/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx,
         <models>/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/*, Embeddings unter <models>/emb/ (oder Pfad).
"""
import argparse
import glob
import json
import os
import re
import sys
import time

import numpy as np
import sherpa_onnx
import soundfile as sf
from scipy.optimize import linear_sum_assignment

SR = 16000


# ----------------------------------------------------------------------------- wie in der App
def audio_gain(x):
    """AudioLevel.gain: Pegel der lauteren Hälfte der 25-ms-Rahmen auf RMS 0,1, max. 100-fach."""
    frame = 400
    n = len(x) // frame
    if n == 0:
        return 1.0
    rms = np.sqrt(np.mean(x[: n * frame].reshape(n, frame).astype(np.float64) ** 2, axis=1))
    med = np.sort(rms)[n // 2]
    loud = rms[rms >= med]
    if len(loud) == 0:
        return 1.0
    return float(min(0.1 / max(loud.mean(), 1e-5), 100.0))


def normalize(x):
    g = audio_gain(x)
    return x if 0.9 <= g <= 1.1 else np.clip(x * g, -1, 1).astype(np.float32)


def quietest_point(x, a, b):
    win = SR // 10
    best, best_e = b, np.inf
    pos, end = int(a * SR), min(int(b * SR), len(x) - win)
    while pos < end:
        e = float(np.sum(x[pos:pos + win].astype(np.float64) ** 2))
        if e < best_e:
            best_e, best = e, (pos + win / 2) / SR
        pos += win // 2
    return best


def windows(x, min_len=15.0, max_len=25.0):
    total = len(x) / SR
    out, start = [], 0.0
    while total - start > max_len:
        cut = min(max(quietest_point(x, start + min_len, start + max_len), start + min_len), start + max_len)
        out.append((start, cut))
        start = cut
    if total - start > 0.05:
        out.append((start, total))
    return out


def plan_chunks(turns):
    """Transcriber.planChunks ohne splitLong (ändert die Sprecher nicht)."""
    merged = []
    for s, e, k in turns:
        if merged and s < merged[-1][1] and k != merged[-1][2]:
            s = merged[-1][1]
        if e - s <= 0:
            continue
        if merged and merged[-1][2] == k and s - merged[-1][1] < 1.0:
            merged[-1] = (merged[-1][0], max(merged[-1][1], e), k)
        elif merged and e <= merged[-1][1]:
            continue
        else:
            merged.append((s, e, k))
    cleaned = []
    for t in merged:
        if t[1] - t[0] < 0.6 and cleaned:
            p = cleaned[-1]
            cleaned[-1] = (p[0], max(p[1], t[1]), p[2])
        else:
            cleaned.append(t)
    return cleaned


def speaker_at(t, turns):
    if not turns:
        return 0
    for s, e, k in turns:
        if s <= t <= e:
            return k
    return min(turns, key=lambda q: min(abs(t - q[0]), abs(t - q[1])))[2]


def assign_words(words, turns):
    """TokenAlign.segments (Sprecherwechsel, Pause > 2 s nach Satzende) + mergeTiny -> Sprecher je Wort."""
    segs, cur, cur_spk = [], [], -1
    for i, (w, s, e) in enumerate(words):
        spk = speaker_at((s + e) / 2, turns)
        if cur:
            last = words[cur[-1]]
            sentence_end = last[0][-1:] in ".?!"
            if spk != cur_spk or (s - last[2] > 2.0 and sentence_end):
                segs.append((cur_spk, cur))
                cur = []
        if not cur:
            cur_spk = spk
        cur.append(i)
    if cur:
        segs.append((cur_spk, cur))
    out, i = [], 0
    while i < len(segs):          # mergeTiny
        spk, idx = segs[i]
        if out and i + 1 < len(segs) and len(idx) <= 1 and out[-1][0] == segs[i + 1][0] and \
                words[idx[-1]][2] - words[idx[0]][1] < 1.0 and len(segs) >= 3:
            out[-1] = (out[-1][0], out[-1][1] + idx + segs[i + 1][1])
            i += 2
            continue
        out.append((spk, list(idx)))
        i += 1
    res = [0] * len(words)
    for spk, idx in out:
        for j in idx:
            res[j] = spk
    return res


def tokens_to_words(tokens, timestamps, durations, offset):
    """TokenAlign.words. Ein reines Leerzeichen-Token (Parakeet vor Ziffern) ist eine Wortgrenze."""
    out, text, start, end, pending_space = [], "", 0.0, 0.0, False
    for i, tok in enumerate(tokens):
        raw = tok.replace("▁", " ")
        if not raw.strip():
            pending_space = True
            continue
        if i >= len(timestamps):
            continue
        ts = offset + timestamps[i]
        dur = durations[i] if durations is not None and i < len(durations) and durations[i] > 0 else 0.08
        new = raw.startswith(" ") or pending_space or not text
        pending_space = False
        if new:
            if text.strip():
                out.append((text.strip(), start, end))
            text, start = "", ts
        text += raw.lstrip() if new else raw
        end = ts + dur
    if text.strip():
        out.append((text.strip(), start, end))
    return out


# ----------------------------------------------------------------------------- Textnormalisierung
_ONES = ["null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf",
         "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"]
_TENS = ["", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig"]


def num_de(n):
    if n < 20:
        return _ONES[n]
    if n < 100:
        o, t = n % 10, n // 10
        return (("ein" if o == 1 else _ONES[o]) + "und" if o else "") + _TENS[t]
    if n < 1000:
        h, r = divmod(n, 100)
        return ("ein" if h == 1 else _ONES[h]) + "hundert" + (num_de(r) if r else "")
    if n < 1_000_000:
        k, r = divmod(n, 1000)
        return ("ein" if k == 1 else num_de(k)) + "tausend" + (num_de(r) if r else "")
    return " ".join(_ONES[int(c)] for c in str(n))


def ordinal_de(n):
    special = {1: "ersten", 3: "dritten", 7: "siebten", 8: "achten"}
    if n in special:
        return special[n]
    w = num_de(n)
    return w + ("sten" if n >= 20 else "ten")


def norm_text(s):
    s = s.lower()
    s = re.sub(r"(\d+):(\d+)", lambda m: f"{num_de(int(m.group(1)))} uhr {num_de(int(m.group(2)))}", s)
    s = re.sub(r"(\d+),(\d+)", lambda m: f"{num_de(int(m.group(1)))} komma {' '.join(_ONES[int(c)] for c in m.group(2))}", s)
    s = s.replace("%", " prozent").replace("€", " euro")
    s = re.sub(r"\b(\d{1,3})\.(?=\s|$)", lambda m: ordinal_de(int(m.group(1))), s)

    def card(m):
        d = m.group(0)
        if len(d) > 4 or (d.startswith("0") and len(d) > 1):
            return " ".join(_ONES[int(c)] for c in d)
        return num_de(int(d))
    s = re.sub(r"\d+", card, s)
    s = re.sub(r"[-–/]", " ", s)
    s = re.sub(r"[^\wäöüß ]", " ", s)
    return s.split()


def edit_ops(ref, hyp):
    """Levenshtein-Distanz auf Wortlisten (zeilenweise mit numpy, auch für 60-min-Aufnahmen)."""
    if not ref:
        return len(hyp)
    vocab = {w: i for i, w in enumerate(set(ref) | set(hyp))}
    r = np.array([vocab[w] for w in ref])
    prev = np.arange(len(r) + 1, dtype=np.int32)
    idx = np.arange(len(r) + 1, dtype=np.int32)
    for w in hyp:
        h = vocab[w]
        cand = np.empty_like(prev)
        cand[0] = prev[0] + 1
        cand[1:] = np.minimum(prev[1:] + 1, prev[:-1] + (r != h))
        prev = np.minimum.accumulate(cand - idx) + idx
    return int(prev[-1])


# ----------------------------------------------------------------------------- Metriken
def ref_speakers_at(t, ref, tol=0.2):
    hit = [x["spk"] for x in ref if x["start"] - tol <= t <= x["end"] + tol]
    if hit:
        return hit
    return [min(ref, key=lambda x: min(abs(t - x["start"]), abs(t - x["end"])))["spk"]]


def word_speaker_error(words, hyp_spk, ref):
    if not words or not ref:
        return float("nan")
    refs = [ref_speakers_at((s + e) / 2, ref) for _, s, e in words]
    rl = sorted({k for r in refs for k in r})
    hl = sorted(set(hyp_spk))
    m = np.zeros((len(rl), len(hl)))
    for r, h in zip(refs, hyp_spk):
        for k in r:
            m[rl.index(k), hl.index(h)] += 1 / len(r)
    ri, hi = linear_sum_assignment(-m)
    mapping = {hl[j]: rl[i] for i, j in zip(ri, hi)}
    wrong = sum(1 for r, h in zip(refs, hyp_spk) if mapping.get(h) not in r)
    return wrong / len(words)


def der(turns, ref, total, collar=0.25, step=0.01):
    n = int(total / step) + 1
    rl = sorted({x["spk"] for x in ref})
    R = np.zeros((len(rl), n), bool)
    mask = np.ones(n, bool)
    for x in ref:
        a, b = int(x["start"] / step), int(x["end"] / step)
        R[rl.index(x["spk"]), a:b] = True
        c = int(collar / step)
        mask[max(0, a - c):a + c] = False
        mask[max(0, b - c):b + c] = False
    hl = sorted({k for _, _, k in turns})
    H = np.zeros((max(1, len(hl)), n), bool)
    for s, e, k in turns:
        H[hl.index(k), int(s / step):int(e / step)] = True
    R, H = R[:, mask], H[:, mask]
    n_ref = R.sum(0)
    n_hyp = H.sum(0)
    co = R.astype(np.float32) @ H.T.astype(np.float32)
    ri, hi = linear_sum_assignment(-co)
    correct = np.zeros(R.shape[1])
    for i, j in zip(ri, hi):
        correct += R[i] & H[j]
    err = np.maximum(n_ref, n_hyp) - correct
    return float(err.sum() / max(1, n_ref.sum()))


# ----------------------------------------------------------------------------- Modelle
class Asr:
    def __init__(self, models, threads):
        p = f"{models}/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        self.rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f"{p}/encoder.int8.onnx", decoder=f"{p}/decoder.int8.onnx", joiner=f"{p}/joiner.int8.onnx",
            tokens=f"{p}/tokens.txt", num_threads=threads, model_type="nemo_transducer",
            decoding_method="greedy_search")

    def run(self, x):
        words, texts, t0 = [], [], time.perf_counter()
        for a, b in windows(x):
            ia, ib = int(a * SR), int(b * SR)
            if ib - ia < SR // 4:
                continue
            st = self.rec.create_stream()
            st.accept_waveform(SR, x[ia:ib])
            self.rec.decode_stream(st)
            r = st.result
            words += tokens_to_words(list(r.tokens), list(r.timestamps), list(r.durations) or None, a)
            texts.append(r.text)
        return words, " ".join(texts), time.perf_counter() - t0


def diarize(models, emb_path, thr, x, threads, seg="model.onnx", num_clusters=-1):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=f"{models}/sherpa-onnx-pyannote-segmentation-3-0/{seg}"),
            num_threads=threads),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb_path, num_threads=threads),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_clusters, threshold=thr),
        min_duration_on=0.3, min_duration_off=0.5)
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    t0 = time.perf_counter()
    res = sd.process(normalize(x)).sort_by_start_time()
    return [(r.start, r.end, r.speaker) for r in res], time.perf_counter() - t0


def refine_sentences(x, words, spk, emb_path, threads, iters=3):
    """Versuch „Nachclustering auf Satzebene“: je Satz (gleicher Sprecher, bis Satzende oder Pause
    > 0,5 s, mind. 1 s) ein Stimm-Embedding; Sätze werden iterativ dem nächsten Sprecher-Mittelwert
    (Kosinus) zugeordnet. Sprecherzahl bleibt höchstens gleich. Liefert neue Sprecher je Wort."""
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb_path, num_threads=threads))
    sents, cur = [], []
    for i, (w, s_, e_) in enumerate(words):
        if cur and (spk[i] != spk[cur[-1]] or s_ - words[cur[-1]][2] > 0.5):
            sents.append(cur)
            cur = []
        cur.append(i)
        if w[-1:] in ".?!":
            sents.append(cur)
            cur = []
    if cur:
        sents.append(cur)
    embs, lab = {}, [spk[c[0]] for c in sents]
    for j, c in enumerate(sents):
        a_, b_ = words[c[0]][1], words[c[-1]][2]
        if b_ - a_ < 1.0:
            continue
        st = ext.create_stream()
        st.accept_waveform(SR, x[int(a_ * SR):int(b_ * SR)])
        st.input_finished()
        v = np.array(ext.compute(st))
        embs[j] = (v / max(1e-9, np.linalg.norm(v)), b_ - a_)
    for _ in range(iters):
        cent = {}
        for j, (v, d) in embs.items():
            cent[lab[j]] = cent.get(lab[j], 0) + v * d
        if len(cent) < 2:
            break
        keys = list(cent)
        C = np.stack([cent[k] / np.linalg.norm(cent[k]) for k in keys])
        changed = False
        for j, (v, _) in embs.items():
            k = keys[int(np.argmax(C @ v))]
            changed |= k != lab[j]
            lab[j] = k
        if not changed:
            break
    out = list(spk)
    for j, c in enumerate(sents):
        for i in c:
            out[i] = lab[j]
    return out


# ----------------------------------------------------------------------------- Ablauf
def fmt(v, p=1, pct=False):
    if v is None or (isinstance(v, float) and np.isnan(v)):
        return "–"
    return f"{v * 100:.{p}f} %" if pct else f"{v:.{p}f}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scenarios", required=True, help="Ordner mit <name>.wav + <name>.json")
    ap.add_argument("--models", required=True)
    ap.add_argument("--emb", action="append", default=[],
                    help="Embedding-Modell[:Schwelle[,Schwelle…]] (mehrfach). Standard: App-Modell bei 0,35")
    ap.add_argument("--seg", action="append", default=[],
                    help="Segmentierungsmodell im pyannote-Ordner (mehrfach): model.onnx (App, fp32) oder model.int8.onnx")
    ap.add_argument("--oracle-spk", action="store_true",
                    help="echte Sprecherzahl vorgeben (wie „Anzahl Personen“ in der App) – obere Schranke")
    ap.add_argument("--refine", action="store_true",
                    help="Versuch: Nachclustering auf Satzebene (Embedding je Satz, k-Means-artige Neuzuordnung)")
    ap.add_argument("--fallback-thr", type=float, default=0,
                    help="Versuch: findet die Diarisierung nur 1 Sprecher (Aufnahme > 20 s), mit dieser Schwelle wiederholen")
    ap.add_argument("--only", default="", help="nur diese Szenarien (Komma-Liste, Präfix erlaubt)")
    ap.add_argument("--skip", default="stress_marathon,stille,kurz", help="Szenarien auslassen")
    ap.add_argument("--threads", type=int, default=4, help="wie App: min(4, Kerne-2)")
    ap.add_argument("--no-asr", action="store_true", help="nur Diarisierung (Wort-Spk entfällt)")
    ap.add_argument("--no-asr-raw", action="store_true", help="ASR nicht zusätzlich auf dem Rohsignal")
    ap.add_argument("--asr-cache", default="", help="JSON-Datei: ASR-Ergebnisse wiederverwenden/speichern")
    ap.add_argument("--out", default="", help="Markdown zusätzlich in diese Datei schreiben")
    ap.add_argument("--json", default="", help="Rohdaten als JSON")
    ap.add_argument("--detail", action="store_true", help="Tabelle je Szenario und Modell (sonst nur Basis)")
    a = ap.parse_args()

    embs = []
    for seg in a.seg or ["model.onnx"]:
        tag = "" if seg == "model.onnx" else f" +seg {seg.removesuffix('.onnx')}"
        for spec in a.emb or ["3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx:0.6"]:
            path, _, thr = spec.partition(":")
            if not os.path.exists(path):
                path = f"{a.models}/emb/{path}"
            for t in (thr or "0.6").split(","):
                embs.append((os.path.basename(path).removesuffix(".onnx") + tag, path, float(t), seg))

    names = sorted(os.path.basename(p)[:-5] for p in glob.glob(f"{a.scenarios}/*.json"))
    skip = set(filter(None, a.skip.split(",")))
    only = list(filter(None, a.only.split(",")))
    names = [n for n in names if (not only and n not in skip) or any(n == o or n.startswith(o) for o in only)]
    names = [n for n in names if os.path.exists(f"{a.scenarios}/{n}.wav")]

    cache = json.load(open(a.asr_cache)) if a.asr_cache and os.path.exists(a.asr_cache) else {}
    asr = None
    rows = []
    for name in names:
        meta = json.load(open(f"{a.scenarios}/{name}.json"))
        ref = meta.get("ref", [])
        if not ref:
            continue
        x, sr = sf.read(f"{a.scenarios}/{name}.wav", dtype="float32")
        assert sr == SR, (name, sr)
        dur = len(x) / SR
        ref_words = norm_text(" ".join(r["text"] for r in sorted(ref, key=lambda r: r["start"])))
        n_true = len({r["spk"] for r in ref})
        lo, hi = meta["expect"].get("speakers", [n_true, n_true])
        info = {"scenario": name, "dur": dur, "n_true": n_true, "range": [lo, hi], "stress": meta.get("stress", False)}
        if not a.no_asr:
            if name not in cache:
                asr = asr or Asr(a.models, a.threads)
                w, txt, t = asr.run(normalize(x))
                c = {"words": w, "text": txt, "t_asr": t}
                if not a.no_asr_raw:
                    _, txt_r, tr = asr.run(x)
                    c.update(text_raw=txt_r, t_asr_raw=tr)
                cache[name] = c
                if a.asr_cache:
                    json.dump(cache, open(a.asr_cache, "w"))
            c = cache[name]
            words = [tuple(w) for w in c["words"]]
            hyp = norm_text(" ".join(w[0] for w in words))
            info.update(wer=edit_ops(ref_words, hyp) / max(1, len(ref_words)), t_asr=c["t_asr"],
                        n_ref_words=len(ref_words))
            if "text_raw" in c:
                info["wer_raw"] = edit_ops(ref_words, norm_text(c["text_raw"])) / max(1, len(ref_words))
            text = c["text"].lower()
            kws = meta["expect"].get("keywords", [])
            info["kw"] = (sum(1 for k in kws if k.lower()[: max(4, len(k) - 2)] in text), len(kws))
        else:
            words = []
        for emb_name, emb_path, thr, seg in embs:
            turns, t_d = diarize(a.models, emb_path, thr, x, a.threads, seg, n_true if a.oracle_spk else -1)
            if a.fallback_thr and dur > 20 and len({k for *_, k in turns}) <= 1:
                turns, t2 = diarize(a.models, emb_path, a.fallback_thr, x, a.threads, seg)
                t_d += t2
            chunks = plan_chunks(turns)
            row = dict(info, emb=emb_name, thr=thr, t_diar=t_d, rtf_diar=t_d / dur,
                       n_raw=len({k for *_, k in turns}), der=der(turns, ref, dur))
            if words:
                spk = assign_words(words, chunks)
                row["n_det"] = len(set(spk))
                row["wspk"] = word_speaker_error(words, spk, ref)
                # Versuch „Kleinstsprecher“: Sprecher mit < 15 s und < 2 % der Redezeit werden
                # entfernt, ihre Wörter dem zeitlich nächsten großen Sprecher zugeordnet
                talk = {}
                for s_, e_, k in chunks:
                    talk[k] = talk.get(k, 0) + e_ - s_
                limit = min(15.0, 0.02 * sum(talk.values()))
                major = [t for t in chunks if talk[t[2]] >= limit]
                if major and len(major) < len(chunks):
                    spk2 = assign_words(words, major)
                    row["n_major"] = len(set(spk2))
                    row["wspk_major"] = word_speaker_error(words, spk2, ref)
                else:
                    row["n_major"], row["wspk_major"] = row["n_det"], row["wspk"]
                if a.refine:
                    t0 = time.perf_counter()
                    spk3 = refine_sentences(x, words, spk, emb_path, a.threads)
                    row["t_refine"] = time.perf_counter() - t0
                    row["n_refine"] = len(set(spk3))
                    row["wspk_refine"] = word_speaker_error(words, spk3, ref)
            else:
                row["n_det"] = len({k for *_, k in chunks})
            rows.append(row)
            print(f"[{name}] {emb_name}@{thr}: DER {row['der']:.3f} Wort-Spk {row.get('wspk', float('nan')):.3f} "
                  f"Spk {row['n_det']}/{n_true} (ohne Kleinstsprecher {row.get('n_major', '-')}, Wort-Spk {row.get('wspk_major', float('nan')):.3f}; Nachclustering {row.get('n_refine', '-')}, Wort-Spk {row.get('wspk_refine', float('nan')):.3f}) WER {row.get('wer', float('nan')):.3f} "
                  f"Diar {t_d:.1f}s ASR {row.get('t_asr', 0):.1f}s ({dur:.0f}s Audio)", file=sys.stderr, flush=True)

    md = report(rows, embs, a.detail)
    print(md)
    if a.out:
        open(a.out, "w").write(md)
    if a.json:
        json.dump(rows, open(a.json, "w"), ensure_ascii=False, indent=1)


def report(rows, embs, detail):
    lines = []
    base = embs[0]
    lines.append(f"### Je Szenario ({base[0]}, Schwelle {base[2]})\n")
    lines.append("| Szenario | Dauer | Spk erkannt/echt (erwartet) | WER | WER roh | Wort-Spk-Fehler | DER | Diar s (RTF) | ASR s (RTF) | Schlüsselw. |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        if (r["emb"], r["thr"]) != (base[0], base[2]):
            continue
        ok = "" if r["range"][0] <= r["n_det"] <= r["range"][1] else " ✗"
        kw = f"{r['kw'][0]}/{r['kw'][1]}" if "kw" in r else "–"
        lines.append(f"| {r['scenario']} | {r['dur']:.0f} s | {r['n_det']}/{r['n_true']} ({r['range'][0]}–{r['range'][1]}){ok} | "
                     f"{fmt(r.get('wer'), 1, True)} | {fmt(r.get('wer_raw'), 1, True)} | {fmt(r.get('wspk'), 1, True)} | "
                     f"{fmt(r['der'], 1, True)} | {r['t_diar']:.1f} ({r['rtf_diar']:.3f}) | "
                     f"{fmt(r.get('t_asr'))} ({fmt(r.get('t_asr', float('nan')) / r['dur'], 3)}) | {kw} |")
    lines.append("\n### Je Embedding-Modell und Schwelle (alle Szenarien)\n")
    lines.append("| Modell | Schwelle | Ø Wort-Spk-Fehler | Ø DER | Spk exakt | Spk im Bereich | Ø Abw. Spk | Diar gesamt s | RTF Diar | Tempo vs. Basis | Ø Wort-Spk ohne Kleinstspr. | Spk im Bereich ohne Kleinstspr. | Ø Wort-Spk Nachclustering |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    tot_base = sum(r["t_diar"] for r in rows if (r["emb"], r["thr"]) == (base[0], base[2])) or 1
    for e in embs:
        rs = [r for r in rows if (r["emb"], r["thr"]) == (e[0], e[2])]
        if not rs:
            continue
        ws = [r["wspk"] for r in rs if "wspk" in r]
        t = sum(r["t_diar"] for r in rs)
        d = sum(r["dur"] for r in rs)
        lines.append(f"| {e[0]} | {e[2]} | {fmt(np.mean(ws) if ws else float('nan'), 1, True)} | "
                     f"{fmt(np.mean([r['der'] for r in rs]), 1, True)} | "
                     f"{sum(r['n_det'] == r['n_true'] for r in rs)}/{len(rs)} | "
                     f"{sum(r['range'][0] <= r['n_det'] <= r['range'][1] for r in rs)}/{len(rs)} | "
                     f"{np.mean([abs(r['n_det'] - r['n_true']) for r in rs]):.2f} | {t:.0f} | {t / d:.3f} | "
                     f"{tot_base / t:.2f}× | "
                     f"{fmt(np.mean([r['wspk_major'] for r in rs if 'wspk_major' in r]) if ws else float('nan'), 1, True)} | "
                     f"{sum(r['range'][0] <= r.get('n_major', r['n_det']) <= r['range'][1] for r in rs)}/{len(rs)} | "
                     f"{fmt(np.mean([r['wspk_refine'] for r in rs if 'wspk_refine' in r]) if any('wspk_refine' in r for r in rs) else float('nan'), 1, True)} |")
    if detail:
        lines.append("\n### Detail (Wort-Spk-Fehler / DER / Sprecher erkannt-echt)\n")
        keys = [(e[0], e[2]) for e in embs]
        lines.append("| Szenario | " + " | ".join(f"{k[0].replace('_', ' ')}@{k[1]}" for k in keys) + " |")
        lines.append("|---|" + "---:|" * len(keys))
        for sc in dict.fromkeys(r["scenario"] for r in rows):
            cells = []
            for k in keys:
                r = next((r for r in rows if r["scenario"] == sc and (r["emb"], r["thr"]) == k), None)
                cells.append("–" if r is None else f"{fmt(r.get('wspk'), 0, True)} / {fmt(r['der'], 0, True)} / {r['n_det']}-{r['n_true']}")
            lines.append(f"| {sc} | " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
