"""Aufnahme über das PC-Mikrofon (16 kHz mono, wird laufend in die WAV-Datei geschrieben)."""
from __future__ import annotations

import threading
import time
import wave
from pathlib import Path

import numpy as np

RATE = 16000


def _preload(libdir) -> None:
    """Mitgelieferte Hilfsbibliotheken von PortAudio vorab laden (Reihenfolge egal, mehrere Durchgänge)."""
    import ctypes
    todo = [p for p in libdir.iterdir() if ".so" in p.name and not p.name.startswith("libportaudio")]
    for _ in range(4):
        rest = []
        for p in todo:
            try:
                ctypes.CDLL(str(p), mode=ctypes.RTLD_GLOBAL)
            except OSError:
                rest.append(p)
        if not rest or len(rest) == len(todo):
            break
        todo = rest


def _import_sd():
    """sounddevice laden. Linux: PortAudio des Systems, sonst die mitgelieferte Bibliothek."""
    import sys
    if sys.platform.startswith("linux"):
        import ctypes.util
        from .paths import resource
        bundled = resource("lib/libportaudio.so.2")
        orig = ctypes.util.find_library
        if bundled.exists() and not orig("portaudio"):
            _preload(bundled.parent)

            def find(name):
                return str(bundled) if name == "portaudio" else orig(name)
            ctypes.util.find_library = find
            try:
                import sounddevice as sd
            finally:
                ctypes.util.find_library = orig
            return sd
    import sounddevice as sd
    return sd


def devices() -> list[dict]:
    try:
        sd = _import_sd()
        default_in = sd.default.device[0] if sd.default.device else None
        out = []
        for i, d in enumerate(sd.query_devices()):
            if d.get("max_input_channels", 0) > 0:
                out.append({"id": i, "name": d["name"], "default": i == default_in})
        return out
    except Exception:  # noqa: BLE001
        return []


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.active = False
        self.paused = False
        self.meeting_id: str | None = None
        self.frames = 0
        self.level = 0.0
        self.error: str | None = None
        self._stream = None
        self._wav: wave.Wave_write | None = None

    def state(self) -> dict:
        return {"active": self.active, "paused": self.paused, "meetingId": self.meeting_id,
                "seconds": self.frames / RATE, "level": round(self.level, 3), "error": self.error}

    def start(self, meeting_id: str, path: Path, device: int | None = None) -> None:
        sd = _import_sd()
        with self.lock:
            if self.active:
                raise RuntimeError("Es läuft bereits eine Aufnahme.")
            self.error = None
            self.frames = 0
            self.level = 0.0
            self.paused = False
            w = wave.open(str(path), "wb")
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            self._wav = w

            def cb(indata, frames, _time, status):
                x = indata[:, 0] if indata.ndim > 1 else indata
                self.level = float(min(1.0, np.sqrt(np.mean(x.astype(np.float32) ** 2)) * 6))
                if self.paused:
                    return
                pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
                try:
                    self._wav.writeframes(pcm)   # schreibt laufend, auch der Kopf wird aktualisiert
                    self.frames += frames
                except Exception as e:  # noqa: BLE001
                    self.error = str(e)

            try:
                self._stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32",
                                              device=device, blocksize=1600, callback=cb)
                self._stream.start()
            except Exception:
                # manche Geräte können kein 16 kHz: mit Gerätestandard aufnehmen und umrechnen
                self._stream = None
                self._start_resampling(sd, device, cb)
            self.meeting_id = meeting_id
            self.active = True

    def _start_resampling(self, sd, device, cb) -> None:
        info = sd.query_devices(device, "input")
        src = int(info["default_samplerate"])

        def cb2(indata, frames, t, status):
            x = indata[:, 0]
            n = int(round(len(x) * RATE / src))
            y = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
            cb(y.reshape(-1, 1), n, t, status)

        self._stream = sd.InputStream(samplerate=src, channels=1, dtype="float32", device=device,
                                      blocksize=src // 10, callback=cb2)
        self._stream.start()

    def pause(self, paused: bool) -> None:
        self.paused = paused

    def stop(self) -> tuple[str | None, float]:
        with self.lock:
            if not self.active:
                return None, 0.0
            try:
                if self._stream is not None:
                    self._stream.stop()
                    self._stream.close()
            finally:
                time.sleep(0.05)
                if self._wav is not None:
                    self._wav.close()
                self._stream = self._wav = None
                self.active = False
                mid, self.meeting_id = self.meeting_id, None
            return mid, self.frames / RATE
