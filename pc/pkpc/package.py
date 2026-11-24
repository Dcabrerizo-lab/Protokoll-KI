"""Austauschdatei zwischen Handy und PC: <Titel>.protokoll

Ein ZIP-Archiv mit
- protokoll.json: {"format": "protokoll-ki", "version": 1, "createdBy": "android"|"pc",
                   "settings": {"language", "vocabulary", "organization"},
                   "openTasks": [{"taskId", "meetingId", "text", "owner"}],
                   "meeting": <Besprechung im JSON-Format der App>}
- audio.wav (16 kHz mono), entfällt bei gelöschtem Audio.
"""
from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from .model import Meeting, meeting_from_json, meeting_to_json

EXT = ".protokoll"
FORMAT = "protokoll-ki"


@dataclass
class Package:
    meeting: Meeting
    language: str = "de"
    vocabulary: list[str] = field(default_factory=list)
    organization: str = ""
    open_tasks: list[dict] = field(default_factory=list)
    created_by: str = "pc"


def is_package(path: str | Path) -> bool:
    try:
        with zipfile.ZipFile(path) as z:
            return "protokoll.json" in z.namelist()
    except (zipfile.BadZipFile, OSError):
        return False


def meeting_id(path: str | Path) -> str | None:
    try:
        with zipfile.ZipFile(path) as z:
            return str(json.loads(z.read("protokoll.json").decode("utf-8"))["meeting"]["id"])
    except Exception:  # noqa: BLE001
        return None


def read(path: str | Path, audio_target: Path | None) -> Package:
    """Liest das Paket; audio.wav wird (falls vorhanden) nach audio_target entpackt."""
    with zipfile.ZipFile(path) as z:
        info = json.loads(z.read("protokoll.json").decode("utf-8"))
        if info.get("format") != FORMAT:
            raise ValueError("Keine Datei von Protokoll KI.")
        if int(info.get("version", 1)) > 1:
            raise ValueError("Diese Datei stammt aus einer neueren Version. Bitte Protokoll KI aktualisieren.")
        m = meeting_from_json(info["meeting"])
        if audio_target is not None and "audio.wav" in z.namelist():
            audio_target.parent.mkdir(parents=True, exist_ok=True)
            with z.open("audio.wav") as src, open(audio_target, "wb") as out:
                while True:
                    b = src.read(1 << 20)
                    if not b:
                        break
                    out.write(b)
            m.audio_file = str(audio_target)
        s = info.get("settings") or {}
        return Package(m, str(s.get("language") or "de"), [str(v) for v in s.get("vocabulary") or []],
                       str(s.get("organization") or ""), list(info.get("openTasks") or []),
                       str(info.get("createdBy") or ""))


def write(path: str | Path, pkg: Package, audio: Path | None) -> Path:
    path = Path(path)
    tmp = path.with_suffix(path.suffix + ".tmp")
    info = {
        "format": FORMAT, "version": 1, "createdBy": pkg.created_by,
        "settings": {"language": pkg.language, "vocabulary": pkg.vocabulary,
                     "organization": pkg.organization},
        "openTasks": pkg.open_tasks,
        "meeting": meeting_to_json(pkg.meeting.copy(audio_file="audio.wav")),
    }
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        z.writestr("protokoll.json", json.dumps(info, ensure_ascii=False, indent=1))
        if audio is not None and Path(audio).exists():
            z.write(audio, "audio.wav")
    tmp.replace(path)
    return path
