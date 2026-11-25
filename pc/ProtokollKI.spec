# PyInstaller-Bauanleitung: pyinstaller ProtokollKI.spec  (Windows und Linux)
import importlib.util
import os
import sys

from PyInstaller.utils.hooks import collect_all

datas = [("pkpc/ui", "pkpc/ui")]
binaries = []
hidden = []
pkgs = ["sherpa_onnx", "av", "fpdf", "docx", "sounddevice"]
if sys.platform == "win32":
    pkgs.append("webview")          # eigenes Programmfenster (Linux: Fenster über den Browser)
for pkg in pkgs:
    if importlib.util.find_spec(pkg) is None:
        continue
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hidden += h
# mitgelieferte Teile, falls vorhanden: KI-Komponente, Schriften (PDF), PortAudio (Linux-Mikrofon)
for src, dst in (("build/llama", "llama"), ("build/fonts", "fonts"), ("build/lib", "lib"),
                 ("../app/src/main/assets/legal", "legal")):     # Datenschutz, Lizenzen, Impressum
    if os.path.isdir(src):
        datas.append((src, dst))

a = Analysis(["run.py"], pathex=["."], binaries=binaries, datas=datas, hiddenimports=hidden,
             excludes=["tkinter", "matplotlib", "IPython", "pytest"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="ProtokollKI", console=False,
          icon="build/icon.ico" if sys.platform == "win32" and os.path.exists("build/icon.ico") else None,
          version=None)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="ProtokollKI")
