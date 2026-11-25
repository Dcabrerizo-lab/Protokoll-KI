"""Lädt llama-server aus den Releases von ggml-org/llama.cpp (CPU + Vulkan für Grafikkarten).

  python tools/get_llama.py build/llama            Windows x64 (llama-server.exe + DLLs)
  python tools/get_llama.py build/llama linux      Linux x64 (llama-server + lib*.so*)

Sucht in den neuesten Releases das erste mit passenden Archiven. GH_TOKEN wird genutzt, falls vorhanden.
"""
import io
import json
import os
import re
import shutil
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO = "ggml-org/llama.cpp"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/llama")
PLATFORM = sys.argv[2] if len(sys.argv) > 2 else ("windows" if sys.platform == "win32" else "linux")
EXE = "llama-server.exe" if PLATFORM == "windows" else "llama-server"


def get(url: str, accept: str = "application/vnd.github+json") -> bytes:
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "protokoll-ki-build"})
    if os.environ.get("GH_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GH_TOKEN"])
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def pick(assets: list[dict], kind: str) -> dict | None:
    for a in assets:
        n = a["name"].lower()
        if "arm64" in n or "aarch64" in n or "x64" not in n:
            continue
        if PLATFORM == "windows":
            if n.endswith(".zip") and "win" in n and kind in n:
                return a
        else:
            if not (n.endswith(".zip") or n.endswith(".tar.gz")) or "ubuntu" not in n:
                continue
            others = ("vulkan", "rocm", "cuda", "sycl", "openvino", "kompute", "s390x", "hip")
            if kind == "cpu" and not any(o in n for o in others):
                return a              # z. B. llama-b1234-bin-ubuntu-x64.tar.gz
            if kind != "cpu" and kind in n:
                return a
    return None


def want(base: str) -> bool:
    b = base.lower()
    if PLATFORM == "windows":
        return b == EXE or b.endswith(".dll")
    return b == EXE or ".so" in b


def extract(data: bytes, name: str) -> None:
    if name.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for n in z.namelist():
                base = Path(n).name
                if base and want(base):
                    (OUT / base).write_bytes(z.read(n))
        return
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
        links = []
        for m in t.getmembers():
            base = Path(m.name).name
            if not base or not want(base):
                continue
            if m.issym() or m.islnk():
                links.append((base, Path(m.linkname).name))
            elif m.isfile():
                (OUT / base).write_bytes(t.extractfile(m).read())
        for base, target in links:          # libfoo.so -> libfoo.so.0 -> libfoo.so.0.1: als echte Kopie
            src = OUT / target
            if src.exists() and not (OUT / base).exists():
                shutil.copyfile(src, OUT / base)


releases = json.loads(get(f"https://api.github.com/repos/{REPO}/releases?per_page=30"))
for rel in releases:
    assets = rel.get("assets", [])
    cpu = pick(assets, "cpu")
    if not cpu:
        continue
    vulkan = pick(assets, "vulkan")
    print("llama.cpp", rel["tag_name"], "->", cpu["name"], vulkan["name"] if vulkan else "(kein Vulkan)")
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    for a in [cpu] + ([vulkan] if vulkan else []):
        extract(get(a["browser_download_url"], "application/octet-stream"), a["name"].lower())
    (OUT / "VERSION.txt").write_text(rel["tag_name"])
    if not (OUT / EXE).exists():
        sys.exit(f"{EXE} fehlt im Archiv")
    if PLATFORM != "windows":
        (OUT / EXE).chmod(0o755)
    print(sorted(p.name for p in OUT.iterdir()))
    sys.exit(0)

print(f"Kein Release mit {PLATFORM}-Archiv gefunden. Vorhandene Namen:")
for rel in releases[:3]:
    print(rel["tag_name"], [a["name"] for a in rel.get("assets", [])])
sys.exit(1)
