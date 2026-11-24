# PyInstaller-Bauanleitung: pyinstaller ProtokollKI.spec
from PyInstaller.utils.hooks import collect_all, collect_dynamic_libs

datas = [("pkpc/ui", "pkpc/ui")]
binaries = []
hidden = []
for pkg in ("sherpa_onnx", "av", "webview", "fpdf", "docx", "sounddevice"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hidden += h
import os
if os.path.isdir("build/llama"):
    datas.append(("build/llama", "llama"))

a = Analysis(["run.py"], pathex=["."], binaries=binaries, datas=datas, hiddenimports=hidden,
             excludes=["tkinter", "matplotlib", "IPython", "pytest"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="ProtokollKI", console=False,
          icon="build/icon.ico" if os.path.exists("build/icon.ico") else None,
          version=None)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="ProtokollKI")
