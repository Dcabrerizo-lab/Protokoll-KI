"""Lädt llama-server für Windows aus den Releases von ggml-org/llama.cpp (CPU + Vulkan).

Sucht in den neuesten Releases das erste mit passenden Windows-x64-Archiven und entpackt
llama-server.exe samt DLLs nach build/llama/. Benötigt GH_TOKEN (in GitHub Actions vorhanden).
"""
import io
import json
import os
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = "ggml-org/llama.cpp"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/llama")


def get(url: str, accept: str = "application/vnd.github+json") -> bytes:
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "protokoll-ki-build"})
    if os.environ.get("GH_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GH_TOKEN"])
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def pick(assets: list[dict], kind: str) -> dict | None:
    for a in assets:
        n = a["name"].lower()
        if n.endswith(".zip") and "win" in n and "x64" in n and kind in n and "arm64" not in n:
            return a
    return None


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
        data = get(a["browser_download_url"], "application/octet-stream")
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for name in z.namelist():
                base = Path(name).name
                if base.lower() == "llama-server.exe" or base.lower().endswith(".dll"):
                    (OUT / base).write_bytes(z.read(name))
    (OUT / "VERSION.txt").write_text(rel["tag_name"])
    if not (OUT / "llama-server.exe").exists():
        sys.exit("llama-server.exe fehlt im Archiv")
    print(sorted(p.name for p in OUT.iterdir()))
    sys.exit(0)

print("Kein Release mit Windows-Archiv gefunden. Vorhandene Namen:")
for rel in releases[:3]:
    print(rel["tag_name"], [a["name"] for a in rel.get("assets", [])])
sys.exit(1)
