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


def _xdg_user_dir(name: str) -> Path | None:
    """Linux: lokalisierte Benutzerordner (z. B. „Dokumente“) über xdg-user-dir bzw. user-dirs.dirs."""
    if sys.platform.startswith("linux"):
        cfg = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "user-dirs.dirs"
        try:
            for line in cfg.read_text("utf-8").splitlines():
                if line.startswith(f"XDG_{name}_DIR="):
                    v = line.split("=", 1)[1].strip().strip('"').replace("$HOME", str(Path.home()))
                    p = Path(v)
                    if p.exists() and p != Path.home():
                        return p
        except OSError:
            pass
    return None


def documents_dir() -> Path:
    if os.environ.get("PK_OUTPUT_DIR"):
        p = Path(os.environ["PK_OUTPUT_DIR"])
    else:
        docs = _xdg_user_dir("DOCUMENTS") or Path.home() / "Documents"
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
    x = _xdg_user_dir("DOWNLOAD")
    if x:
        return x
    for name in ("Downloads", "Download"):
        p = Path.home() / name
        if p.exists():
            return p
    return None


def clean_env() -> dict:
    """Umgebung für fremde Programme (xdg-open, Browser) ohne die Bibliothekspfade des Pakets."""
    env = dict(os.environ)
    for var in ("LD_LIBRARY_PATH", "QT_PLUGIN_PATH", "GDK_PIXBUF_MODULE_FILE"):
        orig = env.pop(var + "_ORIG", None)
        if orig is not None:
            env[var] = orig
        elif getattr(sys, "frozen", False):
            env.pop(var, None)
    return env


def resource(rel: str) -> Path:
    """Mitgelieferte Dateien (im PyInstaller-Paket unter sys._MEIPASS)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / rel
