"""Erkennt die Hardware des PCs (Arbeitsspeicher, Prozessorkerne, Grafikkarte).

Daraus wählt das Programm selbst das beste Modell und die schnellste Einstellung:
- eigene Grafikkarte mit genug Speicher: Sprachmodell läuft komplett auf der Grafikkarte
  (Vulkan, NVIDIA/AMD/Intel Arc) und es reicht auch für das große Modell,
- sonst auf dem Prozessor mit allen echten Kernen.
Die Grafikkarte wird über llama-server --list-devices erkannt (gleiche Sicht wie beim Rechnen),
ersatzweise über nvidia-smi.
"""
from __future__ import annotations

import logging
import os
import re
import shutil
import subprocess
import sys
import threading
from dataclasses import asdict, dataclass, field

log = logging.getLogger(__name__)

# Bezeichnungen von Grafik im Prozessor oder Software-Grafik: geteilter Speicher, meist nicht schneller
_INTEGRATED = re.compile(r"llvmpipe|lavapipe|swiftshader|microsoft basic|"
                         r"intel\(r\) (uhd|hd|iris)|intel (uhd|hd|iris)|iris xe|"
                         r"radeon\(tm\) graphics|radeon graphics|vega \d+ graphics|"
                         r"(amd|ati) radeon\(tm\) [0-9]+m|adreno|mali", re.I)


@dataclass
class Gpu:
    name: str
    vram_mb: int
    free_mb: int = 0
    dedicated: bool = True


@dataclass
class Hardware:
    ram_gb: float
    cores: int                         # echte Kerne
    threads: int                       # logische Kerne
    gpus: list[Gpu] = field(default_factory=list)

    @property
    def gpu(self) -> Gpu | None:
        """Beste eigene Grafikkarte (oder None)."""
        ded = [g for g in self.gpus if g.dedicated]
        return max(ded, key=lambda g: g.vram_mb) if ded else None

    @property
    def vram_gb(self) -> float:
        g = self.gpu
        return g.vram_mb / 1024 if g else 0.0

    def as_dict(self) -> dict:
        d = asdict(self)
        g = self.gpu
        d["gpu"] = asdict(g) if g else None
        d["vramGb"] = round(self.vram_gb, 1)
        return d


def _ram_gb() -> float:
    from .catalog import total_ram_gb
    return total_ram_gb()


def _cores() -> tuple[int, int]:
    logical = os.cpu_count() or 4
    try:
        import psutil
        phys = psutil.cpu_count(logical=False) or logical
    except Exception:  # noqa: BLE001
        phys = max(1, logical // 2) if logical >= 4 else logical
    return phys, logical


_DEV = re.compile(r"^\s*(\w+?)(\d+)\s*:\s*(.+?)\s*\((\d+)\s*MiB(?:,\s*(\d+)\s*MiB free)?\)", re.M)


def parse_list_devices(text: str) -> list[Gpu]:
    """Ausgabe von llama-server --list-devices, z. B. „Vulkan0: NVIDIA GeForce RTX 3060 (12288 MiB, 11800 MiB free)“."""
    out = []
    for m in _DEV.finditer(text):
        backend, name = m.group(1), m.group(3)
        if backend.upper() == "CPU":
            continue
        vram, free = int(m.group(4)), int(m.group(5) or 0)
        out.append(Gpu(name, vram, free, dedicated=not _INTEGRATED.search(name)))
    return out


def _run(args: list[str], env: dict | None = None, cwd: str | None = None) -> str:
    flags = 0x08000000 if sys.platform == "win32" else 0   # CREATE_NO_WINDOW
    try:
        r = subprocess.run(args, capture_output=True, timeout=30, env=env, cwd=cwd,
                           stdin=subprocess.DEVNULL, creationflags=flags)
        return (r.stdout + b"\n" + r.stderr).decode("utf-8", "replace")
    except (OSError, subprocess.SubprocessError) as e:
        log.info("%s: %s", args[0], e)
        return ""


def _gpus_llama() -> list[Gpu]:
    from .llm import server_binary, server_env
    b = server_binary()
    if b is None:
        return []
    return parse_list_devices(_run([str(b), "--list-devices"], env=server_env(b), cwd=str(b.parent)))


def _gpus_nvidia() -> list[Gpu]:
    exe = shutil.which("nvidia-smi")
    if not exe:
        return []
    out = []
    for line in _run([exe, "--query-gpu=name,memory.total,memory.free",
                      "--format=csv,noheader,nounits"]).splitlines():
        p = [x.strip() for x in line.split(",")]
        if len(p) == 3 and p[1].isdigit():
            out.append(Gpu(p[0], int(p[1]), int(p[2]) if p[2].isdigit() else 0))
    return out


def detect() -> Hardware:
    phys, logical = _cores()
    gpus: list[Gpu] = []
    if os.environ.get("PK_CPU_ONLY") != "1":
        gpus = _gpus_llama() or _gpus_nvidia()
    hw = Hardware(round(_ram_gb(), 1), phys, logical, gpus)
    log.info("Hardware: %s", hw.as_dict())
    return hw


_lock = threading.Lock()
_cached: Hardware | None = None


def info() -> Hardware:
    """Einmal erkennen und merken (die Erkennung dauert ca. 1 Sekunde)."""
    global _cached
    with _lock:
        if _cached is None:
            _cached = detect()
        return _cached


def warm_up() -> None:
    threading.Thread(target=info, daemon=True).start()


# ------------------------------------------------------------------ Einstellungen

def choose_set_id(hw: Hardware) -> str:
    """So schnell wie möglich bei bestmöglicher Qualität.

    - eigene Grafikkarte ab 7,5 GB: großes Modell (Qwen3 8B) auf der Grafikkarte, trotzdem schnell
    - Grafikkarte ab 4 GB oder Prozessor mit genug Arbeitsspeicher: Qwen3 4B (fast gleich gut,
      auf dem Prozessor doppelt so schnell wie 8B)
    - wenig Arbeitsspeicher: kleineres Modell
    """
    if hw.vram_gb >= 7.5 and hw.ram_gb >= 7.5:
        return "ultra"
    if hw.vram_gb >= 3.5 or hw.ram_gb >= 6.5:
        return "premium"
    return "standard"


def llm_threads(hw: Hardware) -> tuple[int, int]:
    """(Threads fürs Erzeugen, Threads fürs Einlesen). Echte Kerne sind am schnellsten."""
    gen = max(1, min(16, hw.cores))
    batch = max(1, min(32, hw.threads))
    return gen, batch


def asr_threads(hw: Hardware) -> int:
    return max(1, min(16, hw.cores))


def gpu_fits(hw: Hardware, model_bytes: int, context: int) -> bool:
    """Passt das Sprachmodell samt Zwischenspeicher komplett in die Grafikkarte?"""
    g = hw.gpu
    if g is None:
        return False
    need_mb = model_bytes / 1024 ** 2 + context * 0.07 + 600    # KV q8_0 (~70 KB/Token bei 8B) + Puffer
    return g.vram_mb >= need_mb


def summary_text(hw: Hardware) -> str:
    g = hw.gpu
    s = f"{hw.ram_gb:.0f} GB Arbeitsspeicher, {hw.cores} Prozessorkerne".replace(".", ",")
    if g:
        s += f", Grafikkarte {g.name} ({g.vram_mb / 1024:.0f} GB)"
    return s
