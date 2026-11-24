"""Ablageorte. Windows: Modelle unter %LOCALAPPDATA%, Ergebnisse unter Dokumente."""
from __future__ import annotations

import os
import sys
from pathlib import Path


def app_data() -> Path:
    if os.environ.get("PK_DATA_DIR"):
        p = Path(os.environ["PK_DATA_DIR"])
    elif sys.platform == "win32":
        p = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "ProtokollKI"
    else:
        p = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "protokoll-ki"
    p.mkdir(parents=True, exist_ok=True)
    return p


def models_dir() -> Path:
    p = Path(os.environ["PK_MODELS_DIR"]) if os.environ.get("PK_MODELS_DIR") else app_data() / "models"
    p.mkdir(parents=True, exist_ok=True)
    return p


def documents_dir() -> Path:
    if os.environ.get("PK_OUTPUT_DIR"):
        p = Path(os.environ["PK_OUTPUT_DIR"])
    else:
        docs = Path.home() / "Documents"
        if sys.platform == "win32":
            try:
                import ctypes.wintypes
                buf = ctypes.create_unicode_buffer(ctypes.wintypes.MAX_PATH)
                ctypes.windll.shell32.SHGetFolderPathW(None, 5, None, 0, buf)  # CSIDL_PERSONAL
                if buf.value:
                    docs = Path(buf.value)
            except Exception:
                pass
        p = docs / "Protokoll KI"
    p.mkdir(parents=True, exist_ok=True)
    return p


def downloads_dir() -> Path | None:
    if sys.platform == "win32":
        try:
            import ctypes
            from ctypes import wintypes
            import uuid as _uuid

            class GUID(ctypes.Structure):
                _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD),
                            ("Data3", wintypes.WORD), ("Data4", wintypes.BYTE * 8)]

            u = _uuid.UUID("{374DE290-123F-4565-9164-39C4925E467B}")
            g = GUID(u.fields[0], u.fields[1], u.fields[2],
                     (wintypes.BYTE * 8).from_buffer_copy(u.bytes[8:]))
            out = ctypes.c_wchar_p()
            if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(out)) == 0:
                path = Path(out.value)
                ctypes.windll.ole32.CoTaskMemFree(out)
                if path.exists():
                    return path
        except Exception:
            pass
    p = Path.home() / "Downloads"
    return p if p.exists() else None


def resource(rel: str) -> Path:
    """Mitgelieferte Dateien (im PyInstaller-Paket unter sys._MEIPASS)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / rel
