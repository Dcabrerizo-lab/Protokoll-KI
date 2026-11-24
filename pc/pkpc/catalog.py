"""KI-Modelle für den PC: gleiche Modelle wie die App, Auswahl nach Arbeitsspeicher."""
from __future__ import annotations

import hashlib
import os
import shutil
import tarfile
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .paths import models_dir

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
HF = "https://huggingface.co"
MIRROR = "https://github.com/Dcabrerizo-lab/Protokoll-KI/releases/download/models-v1"

SEGMENTATION = "pyannote-segmentation-3-0.int8.onnx"
EMBEDDING = "wespeaker_en_voxceleb_resnet34_LM.onnx"


@dataclass
class ModelFile:
    name: str                       # Zieldatei unter models/
    urls: list[str]                 # werden der Reihe nach versucht
    size: int
    archive_member: str | None = None   # bei .tar.bz2: Pfad im Archiv


@dataclass
class ModelSet:
    id: str
    label: str
    description: str
    min_ram_gb: float
    whisper: str                    # Präfix: turbo, small, base
    llm: ModelFile
    context: int
    no_think: bool = False
    files: list[ModelFile] = field(default_factory=list)

    @property
    def download_bytes(self) -> int:
        return sum(f.size for f in self.all_files())

    def all_files(self) -> list[ModelFile]:
        return common_files() + whisper_files(self.whisper) + [self.llm]


def common_files() -> list[ModelFile]:
    return [
        ModelFile(SEGMENTATION, [f"{MIRROR}/{SEGMENTATION}",
                                 f"{GH}/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"],
                  1_500_000, "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"),
        ModelFile(EMBEDDING, [f"{GH}/speaker-recongition-models/{EMBEDDING}"], 26_530_550),
    ]


_WHISPER_SIZES = {"turbo": (674_000_000, 361_000_000), "small": (112_000_000, 262_000_000),
                  "base": (29_000_000, 130_000_000)}


def whisper_files(prefix: str) -> list[ModelFile]:
    enc, dec = _WHISPER_SIZES[prefix]
    arch = f"{GH}/asr-models/sherpa-onnx-whisper-{prefix}.tar.bz2"
    out = []
    for part, size in (("encoder.int8.onnx", enc), ("decoder.int8.onnx", dec), ("tokens.txt", 800_000)):
        name = f"{prefix}-{part}"
        out.append(ModelFile(name, [f"{MIRROR}/{name}", arch], size,
                             f"sherpa-onnx-whisper-{prefix}/{name}"))
    return out


SETS = [
    ModelSet("ultra", "Höchste Qualität",
             "Whisper large-v3-turbo und Qwen3 8B. Für PCs ab 16 GB Arbeitsspeicher.", 13.0, "turbo",
             ModelFile("Qwen3-8B-Q4_K_M.gguf",
                       [f"{HF}/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"], 5_027_783_488),
             16384, no_think=True),
    ModelSet("premium", "Sehr gute Qualität",
             "Whisper large-v3-turbo und Qwen3 4B. Für PCs mit 8 GB Arbeitsspeicher.", 6.5, "turbo",
             ModelFile("Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                       [f"{HF}/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"],
                       2_497_280_000), 16384),
    ModelSet("standard", "Gute Qualität",
             "Whisper small und Qwen2.5 3B. Für ältere PCs.", 0.0, "small",
             ModelFile("qwen2.5-3b-instruct-q4_k_m.gguf",
                       [f"{HF}/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"],
                       2_104_932_768), 8192),
    # Nur für automatische Tests (klein und schnell)
    ModelSet("kompakt", "Kompakt (Test)", "Whisper base und Qwen2.5 1.5B.", -1.0, "base",
             ModelFile("qwen2.5-1.5b-instruct-q4_k_m.gguf",
                       [f"{HF}/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"],
                       1_117_320_736), 8192),
]


def by_id(i: str | None) -> ModelSet | None:
    return next((s for s in SETS if s.id == i), None)


def total_ram_gb() -> float:
    try:
        import psutil
        return psutil.virtual_memory().total / 1024 ** 3
    except Exception:
        pass
    if os.name == "nt":
        import ctypes

        class MS(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
        m = MS()
        m.dwLength = ctypes.sizeof(MS)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return m.ullTotalPhys / 1024 ** 3
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1024 ** 3
    except (ValueError, OSError, AttributeError):
        return 8.0


def recommended() -> ModelSet:
    forced = by_id(os.environ.get("PK_MODEL_SET"))
    if forced:
        return forced
    ram = total_ram_gb()
    return next(s for s in SETS if ram >= s.min_ram_gb)


def path(name: str) -> Path:
    return models_dir() / name


def is_installed(s: ModelSet) -> bool:
    return all(path(f.name).exists() and path(f.name).stat().st_size > 0 for f in s.all_files())


def installed_sets() -> list[ModelSet]:
    return [s for s in SETS if is_installed(s)]


# ------------------------------------------------------------------ Download

class Downloader:
    """Lädt fehlende Dateien eines Pakets (fortsetzbar). Einziger Netzwerkzugriff des Programms."""

    def __init__(self):
        self.lock = threading.Lock()
        self.running = False
        self.cancel = False
        self.done_bytes = 0
        self.total_bytes = 0
        self.status = ""
        self.error: str | None = None
        self.finished_set: str | None = None

    def snapshot(self) -> dict:
        with self.lock:
            return {"running": self.running, "done": self.done_bytes, "total": self.total_bytes,
                    "status": self.status, "error": self.error, "finished": self.finished_set}

    def start(self, s: ModelSet, on_done: Callable[[], None] | None = None) -> None:
        with self.lock:
            if self.running:
                return
            self.running, self.cancel, self.error, self.finished_set = True, False, None, None
        threading.Thread(target=self._run, args=(s, on_done), daemon=True).start()

    def _run(self, s: ModelSet, on_done) -> None:
        try:
            files = [f for f in s.all_files() if not path(f.name).exists()]
            self.total_bytes = sum(f.size for f in files)
            self.done_bytes = 0
            for i, f in enumerate(files):
                self.status = f"Lade {f.name} ({i + 1}/{len(files)}) …"
                self._fetch(f)
                if self.cancel:
                    self.status = "Abgebrochen"
                    return
            self.status = "Fertig"
            self.finished_set = s.id
            if on_done:
                on_done()
        except Exception as e:  # noqa: BLE001
            self.error = (f"Download fehlgeschlagen: {e}. Bitte Internetverbindung prüfen und "
                          "erneut versuchen. Bereits geladene Teile bleiben erhalten.")
        finally:
            self.running = False

    def _fetch(self, f: ModelFile) -> None:
        last_err: Exception | None = None
        base_done = self.done_bytes
        for url in f.urls:
            self.done_bytes = base_done
            try:
                if url.endswith(".tar.bz2"):
                    self._fetch_archive(url, f)
                else:
                    self._fetch_direct(url, path(f.name))
                return
            except Exception as e:  # noqa: BLE001
                last_err = e
                if self.cancel:
                    return
        raise last_err or RuntimeError("keine Quelle")

    def _open(self, url: str, offset: int = 0):
        req = urllib.request.Request(url, headers={"User-Agent": "ProtokollKI-PC"})
        if offset:
            req.add_header("Range", f"bytes={offset}-")
        return urllib.request.urlopen(req, timeout=60)

    def _fetch_direct(self, url: str, target: Path) -> None:
        part = target.with_suffix(target.suffix + ".part")
        offset = part.stat().st_size if part.exists() else 0
        for attempt in range(6):
            try:
                with self._open(url, offset) as r:
                    if offset and r.status != 206:
                        offset = 0
                    mode = "ab" if offset else "wb"
                    self.done_bytes += offset
                    with open(part, mode) as out:
                        while True:
                            if self.cancel:
                                return
                            buf = r.read(1 << 20)
                            if not buf:
                                break
                            out.write(buf)
                            self.done_bytes += len(buf)
                break
            except Exception:
                if attempt == 5 or self.cancel:
                    raise
                time.sleep(3 * (attempt + 1))
                offset = part.stat().st_size if part.exists() else 0
                self.done_bytes -= 0
        if part.stat().st_size < 1000:
            part.unlink(missing_ok=True)
            raise RuntimeError(f"{target.name} ist leer")
        os.replace(part, target)

    def _fetch_archive(self, url: str, f: ModelFile) -> None:
        tmp = models_dir() / ("_" + hashlib.md5(url.encode()).hexdigest() + ".tar.bz2")
        if not tmp.exists():
            self._fetch_direct(url, tmp)
        if self.cancel:
            return
        self.status = f"Entpacke {f.name} …"
        with tarfile.open(tmp, "r:bz2") as tar:
            wanted = {m.name: m for m in tar.getmembers()}
            # alle Dateien dieses Archivs übernehmen, die zu einem Paket gehören
            for s in SETS:
                for mf in s.all_files():
                    if mf.archive_member in wanted and not path(mf.name).exists():
                        src = tar.extractfile(wanted[mf.archive_member])
                        if src:
                            with open(path(mf.name).with_suffix(".tmp"), "wb") as out:
                                shutil.copyfileobj(src, out)
                            os.replace(path(mf.name).with_suffix(".tmp"), path(mf.name))
        tmp.unlink(missing_ok=True)
        if not path(f.name).exists():
            raise RuntimeError(f"{f.name} fehlt im Archiv")
