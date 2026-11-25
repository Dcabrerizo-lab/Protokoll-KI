"""Lokale Oberfläche: kleiner HTTP-Server nur auf 127.0.0.1, Fenster über pywebview."""
from __future__ import annotations

import json
import logging
import mimetypes
import os
import re
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import __version__, catalog, core, hardware, package, recorder
from .model import PROTOCOL_MODES, TEMPLATES, Meeting, meeting_to_json
from .paths import app_data, clean_env, documents_dir, downloads_dir, resource

log = logging.getLogger("pkpc")


class App:
    def __init__(self):
        root = app_data()
        self.settings = core.SettingsStore(root)
        self.lib = core.Library(root)
        self.docs = documents_dir()
        self.proc = core.Processor(self.lib, self.settings, self.docs)
        self.rec = recorder.Recorder()
        self.dl = catalog.Downloader()
        self.pending: list[dict] = []
        self.token = secrets.token_urlsafe(16)
        self.focus_requested = 0.0
        self._ids: dict[tuple[str, float], str | None] = {}
        self.last_seen = time.time()
        threading.Thread(target=self._watch_downloads, daemon=True).start()
        hardware.warm_up()

    def busy(self) -> bool:
        """Läuft gerade etwas, das nicht abgebrochen werden darf?"""
        return bool(self.rec.active or self.proc.queued or self.dl.snapshot().get("running"))

    def quit_when_idle(self, idle_sec: float = 300) -> None:
        """Im Browser-Betrieb (Linux): beenden, wenn kein Fenster mehr offen ist und nichts mehr läuft."""
        def loop():
            while True:
                time.sleep(15)
                if time.time() - self.last_seen > idle_sec and not self.busy():
                    logging.info("Kein Fenster mehr offen, Programm beendet sich.")
                    os._exit(0)
        threading.Thread(target=loop, daemon=True).start()

    # ------------------------------------------------------------ Download-Ordner beobachten

    def _watch_downloads(self) -> None:
        while True:
            try:
                self._scan()
            except Exception:  # noqa: BLE001
                log.exception("Download-Ordner")
            time.sleep(3)

    def _scan(self) -> None:
        d = downloads_dir()
        if not d or not self.settings.value.watch_downloads:
            self.pending = []
            return
        known = {self.lib.extra.get(m.id, {}).get("sourceFile") for m in self.lib.all()}
        seen = set(self.settings.value.seen_files)
        found = []
        limit = time.time() - 14 * 24 * 3600
        for f in d.glob(f"*{package.EXT}"):
            try:
                st = f.stat()
            except OSError:
                continue
            if st.st_mtime < limit or str(f) in known or str(f) in seen or "(fürs Handy)" in f.name:
                continue
            if time.time() - st.st_mtime < 2:      # wird evtl. noch geschrieben
                continue
            key = (str(f), st.st_mtime)
            if key not in self._ids:
                self._ids[key] = package.meeting_id(f)
            if self._ids[key] and self.lib.get(self._ids[key]):
                continue
            found.append({"path": str(f), "name": f.stem, "size": st.st_size, "time": int(st.st_mtime * 1000)})
        self.pending = sorted(found, key=lambda x: -x["time"])

    # ------------------------------------------------------------ Zustand

    def meeting_brief(self, m: Meeting) -> dict:
        f, text = self.lib.progress.get(m.id, (0.0, ""))
        return {"id": m.id, "title": m.title, "createdAt": m.created_at, "durationSec": m.duration_sec,
                "status": m.status, "progress": f, "progressText": text, "error": m.error,
                "mode": m.mode, "template": m.template,
                "taskCount": len(m.tasks), "openTasks": sum(1 for t in m.tasks if not t.done),
                "speakerCount": len(m.speakers),
                "source": self.lib.extra.get(m.id, {}).get("source", "")}

    def state(self) -> dict:
        s = self.settings.value
        rec_set = catalog.recommended()
        active = self.proc.model_set()
        return {
            "version": __version__,
            "meetings": [self.meeting_brief(m) for m in self.lib.all()],
            "recorder": self.rec.state(),
            "pending": self.pending,
            "models": {
                "ready": active is not None, "active": active.id if active else None,
                "recommended": rec_set.id, "ramGb": round(catalog.total_ram_gb(), 1),
                "hardware": hardware.summary_text(hardware.info()),
                "onGpu": hardware.gpu_fits(hardware.info(), (active or rec_set).llm.size, (active or rec_set).context),
                "sets": [{"id": x.id, "label": x.label, "description": x.description,
                          "bytes": x.download_bytes, "installed": catalog.is_installed(x)}
                         for x in catalog.SETS if x.id != "kompakt" or os.environ.get("PK_MODEL_SET") == "kompakt"],
                "download": self.dl.snapshot(),
            },
            "settings": {"language": s.language, "vocabulary": s.vocabulary, "organization": s.organization,
                         "defaultTemplate": s.default_template, "trackTasks": s.track_tasks,
                         "watchDownloads": s.watch_downloads},
            "templates": [{"id": t.name, "label": t.label, "description": t.description} for t in TEMPLATES.values()],
            "modes": [{"id": k, "label": v} for k, v in PROTOCOL_MODES.items()],
            "outputDir": str(self.docs),
            "downloadsDir": str(downloads_dir() or ""),
            "focus": self.focus_requested,
            "platform": sys.platform,
        }

    def meeting_full(self, mid: str) -> dict | None:
        m = self.lib.get(mid)
        if m is None:
            return None
        d = meeting_to_json(m)
        d["brief"] = self.meeting_brief(m)
        d["files"] = self.lib.extra.get(mid, {}).get("files", {})
        d["folder"] = self.lib.extra.get(mid, {}).get("folder", "")
        d["hasAudio"] = bool(m.audio_file) and Path(m.audio_file).exists()
        return d

    # ------------------------------------------------------------ Aktionen

    def import_path(self, path: Path, auto: bool = True) -> dict:
        m, done = core.import_file(self.lib, path, self.settings.value.default_template)
        if str(path) not in self.settings.value.seen_files:
            self.settings.update(seen_files=(self.settings.value.seen_files + [str(path)])[-200:])
        self._scan()
        if done:
            self.proc.write_outputs(m)
        return {"id": m.id, "done": done}

    def process(self, body: dict) -> None:
        mid = body["id"]

        def ch(m: Meeting) -> Meeting:
            kw = {}
            if body.get("title", "").strip():
                kw["title"] = body["title"].strip()
            if body.get("mode") in PROTOCOL_MODES:
                kw["mode"] = body["mode"]
            if body.get("template") in TEMPLATES:
                kw["template"] = body["template"]
            if "expectedSpeakers" in body:
                kw["expected_speakers"] = max(0, min(20, int(body.get("expectedSpeakers") or 0)))
            if isinstance(body.get("agenda"), list):
                kw["agenda"] = [str(a).strip() for a in body["agenda"] if str(a).strip()]
            for k, attr in (("location", "location"), ("chair", "chair"), ("minuteTaker", "minute_taker")):
                if k in body:
                    kw[attr] = str(body[k]).strip()
            return m.copy(**kw)
        self.lib.update(mid, ch)
        self.proc.enqueue(mid, summary_only=bool(body.get("summaryOnly")))

    def update_meeting(self, mid: str, body: dict) -> None:
        def ch(m: Meeting) -> Meeting:
            kw = {}
            if "title" in body and str(body["title"]).strip():
                kw["title"] = str(body["title"]).strip()
            if isinstance(body.get("speakers"), list):
                names = {int(s["id"]): str(s["name"]).strip() for s in body["speakers"] if str(s.get("name", "")).strip()}
                old = {s.id: s.name for s in m.speakers}
                kw["speakers"] = [type(s)(s.id, names.get(s.id, s.name), s.profile_id) for s in m.speakers]
                # Namen auch in Protokolltext und Aufgaben ersetzen ("Person 2" -> "Anna")
                rep = {old[i]: n for i, n in names.items() if i in old and old[i] != n}
                if rep:
                    def r(t: str) -> str:
                        for a, b in rep.items():
                            t = re.sub(rf"\b{re.escape(a)}\b", b, t)
                        return t
                    s = m.summary
                    kw["summary"] = type(s)(r(s.overview),
                                            [type(t)(r(t.title), [r(p) for p in t.points], t.refs) for t in s.topics],
                                            [r(x) for x in s.decisions], [r(x) for x in s.open_points],
                                            s.decision_refs, s.open_point_refs)
                    kw["tasks"] = [type(t)(**{**t.__dict__, "text": r(t.text), "owner": r(t.owner)}) for t in m.tasks]
            if isinstance(body.get("taskDone"), dict):
                td = body["taskDone"]
                base = kw.get("tasks", m.tasks)
                kw["tasks"] = [type(t)(**{**t.__dict__, "done": bool(td.get(t.id, t.done))}) for t in base]
            if isinstance(body.get("tasks"), list):
                from .model import TaskItem
                kw["tasks"] = [TaskItem(str(t.get("text", "")).strip(), str(t.get("owner") or "offen").strip() or "offen",
                                        str(t.get("due") or "offen").strip() or "offen", bool(t.get("done")),
                                        id=str(t.get("id") or "") or TaskItem("").id, ref=int(t.get("ref", -1)))
                               for t in body["tasks"] if str(t.get("text", "")).strip()]
            for k, attr in (("location", "location"), ("chair", "chair"), ("minuteTaker", "minute_taker")):
                if k in body:
                    kw[attr] = str(body[k]).strip()
            if body.get("template") in TEMPLATES:
                kw["template"] = body["template"]
            return m.copy(**kw)
        m = self.lib.update(mid, ch)
        if m and m.status == "DONE":
            self.proc.write_outputs(m)

    def stop_recording(self) -> dict:
        mid, sec = self.rec.stop()
        if not mid:
            return {}
        if sec < 1.0:
            self.lib.update(mid, lambda m: m.copy(status="ERROR", error="Die Aufnahme ist leer."))
            return {"id": mid}
        self.lib.update(mid, lambda m: m.copy(status="RECORDED", duration_sec=sec))
        return {"id": mid, "seconds": sec}

    def open(self, mid: str | None, kind: str) -> None:
        if kind == "outputDir":
            _reveal(self.docs)
            return
        if kind == "downloads":
            d = downloads_dir()
            if d:
                _reveal(d)
            return
        ex = self.lib.extra.get(mid or "", {})
        if kind == "folder" and ex.get("folder"):
            _reveal(Path(ex["folder"]))
        elif kind in ("docx", "pdf", "md") and ex.get("files", {}).get(kind):
            _open_file(Path(ex["files"][kind]))
        elif kind == "phone" and ex.get("files", {}).get("phone"):
            _reveal(Path(ex["files"]["phone"]), select=True)


def _open_file(p: Path) -> None:
    if sys.platform == "win32":
        os.startfile(str(p))  # noqa: S606
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(p)])
    else:
        subprocess.Popen(["xdg-open", str(p)], env=clean_env(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _reveal(p: Path, select: bool = False) -> None:
    if sys.platform == "win32":
        if select:
            subprocess.Popen(["explorer", "/select,", str(p)])
        else:
            os.startfile(str(p))  # noqa: S606
    else:
        _open_file(p if not select else p.parent)


# ------------------------------------------------------------------ Rechtstexte

def legal_text(name: str) -> str:
    """Datenschutz, Lizenzen, Impressum – dieselben Dateien wie in der Android-App.

    Zeilen mit dem Platzhalter „[BITTE AUSFÜLLEN …]“ werden nicht angezeigt.
    """
    for base in (resource("legal"),
                 Path(__file__).resolve().parents[2] / "app/src/main/assets/legal"):
        f = base / f"{name}.md"
        if f.exists():
            lines = f.read_text("utf-8").splitlines()
            return "\n".join(line for line in lines if "[BITTE AUSFÜLLEN" not in line)
    return ""


# ------------------------------------------------------------------ HTTP

def make_handler(app: App):
    ui = resource("pkpc/ui")
    if not ui.exists():
        ui = Path(__file__).resolve().parent / "ui"

    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # noqa: D401
            log.debug(fmt, *args)

        def _send(self, code: int, body: bytes, ctype: str = "application/json", extra: dict | None = None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, obj, code: int = 200):
            self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

        def _authorized(self) -> bool:
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            tok = self.headers.get("X-Token") or (q.get("t") or [""])[0]
            return secrets.compare_digest(tok, app.token)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if not n:
                return {}
            return json.loads(self.rfile.read(n).decode("utf-8"))

        def do_GET(self):  # noqa: N802
            url = urllib.parse.urlparse(self.path)
            p = url.path
            if p in ("/", "/index.html"):
                if not self._authorized():
                    return self._send(403, b"Nicht erlaubt", "text/plain")
                html = (ui / "index.html").read_text("utf-8").replace("__TOKEN__", app.token)
                return self._send(200, html.encode("utf-8"), "text/html; charset=utf-8")
            if p == "/health":
                return self._json({"ok": True, "app": "protokoll-ki"})
            if not self._authorized():
                return self._json({"error": "forbidden"}, 403)
            if p == "/api/state":
                app.last_seen = time.time()
                return self._json(app.state())
            m = re.fullmatch(r"/api/meeting/([\w-]+)", p)
            if m:
                d = app.meeting_full(m.group(1))
                return self._json(d) if d else self._json({"error": "not found"}, 404)
            m = re.fullmatch(r"/api/audio/([\w-]+)", p)
            if m:
                return self._audio(m.group(1))
            if p == "/api/devices":
                return self._json(recorder.devices())
            m = re.fullmatch(r"/api/legal/(datenschutz|lizenzen|impressum)", p)
            if m:
                return self._json({"text": legal_text(m.group(1))})
            return self._json({"error": "not found"}, 404)

        def _audio(self, mid: str):
            mt = app.lib.get(mid)
            if not mt or not mt.audio_file or not Path(mt.audio_file).exists():
                return self._json({"error": "no audio"}, 404)
            f = Path(mt.audio_file)
            size = f.stat().st_size
            rng = self.headers.get("Range")
            start, end = 0, size - 1
            code = 200
            if rng:
                mm = re.match(r"bytes=(\d*)-(\d*)", rng)
                if mm:
                    if mm.group(1):
                        start = int(mm.group(1))
                    if mm.group(2):
                        end = min(int(mm.group(2)), size - 1)
                    code = 206
            end = min(end, start + 8 * 1024 * 1024 - 1)
            with open(f, "rb") as fh:
                fh.seek(start)
                data = fh.read(end - start + 1)
            extra = {"Accept-Ranges": "bytes"}
            if code == 206:
                extra["Content-Range"] = f"bytes {start}-{start + len(data) - 1}/{size}"
            self._send(code, data, "audio/wav", extra)

        def do_POST(self):  # noqa: N802
            if not self._authorized():
                return self._json({"error": "forbidden"}, 403)
            p = urllib.parse.urlparse(self.path).path
            try:
                if p == "/api/upload":
                    return self._upload()
                b = self._body()
                if p == "/api/import-path":
                    return self._json(app.import_path(Path(b["path"])))
                if p == "/api/dismiss":
                    app.settings.update(seen_files=(app.settings.value.seen_files + [b["path"]])[-200:])
                    app._scan()
                    return self._json({})
                if p == "/api/process":
                    app.process(b)
                    return self._json({})
                if p == "/api/focus":
                    app.focus_requested = time.time()
                    w = getattr(app, "window", None)
                    if w is not None:
                        try:
                            w.restore()
                            w.show()
                        except Exception:  # noqa: BLE001
                            pass
                    return self._json({})
                if p == "/api/quit":
                    if app.rec.active:
                        app.stop_recording()
                    app.proc.cancel()
                    threading.Timer(0.5, lambda: os._exit(0)).start()
                    return self._json({})
                if p == "/api/cancel":
                    app.proc.cancel()
                    return self._json({})
                if p == "/api/record/start":
                    title = (b.get("title") or "").strip() or time.strftime("Besprechung %d.%m.%Y %H:%M")
                    mt = app.lib.create(title, status="RECORDING")
                    mt = app.lib.save(mt.copy(template=app.settings.value.default_template), {"source": "pc-mic"})
                    try:
                        dev = b.get("device")
                        app.rec.start(mt.id, Path(mt.audio_file), int(dev) if dev not in (None, "") else None)
                    except Exception as e:  # noqa: BLE001
                        app.lib.delete(mt.id)
                        return self._json({"error": "Das Mikrofon konnte nicht gestartet werden. "
                                                    f"Ist ein Mikrofon angeschlossen und freigegeben? ({e})"}, 400)
                    return self._json({"id": mt.id})
                if p == "/api/record/pause":
                    app.rec.pause(bool(b.get("paused")))
                    return self._json({})
                if p == "/api/record/stop":
                    return self._json(app.stop_recording())
                m = re.fullmatch(r"/api/meeting/([\w-]+)/(update|delete)", p)
                if m:
                    if m.group(2) == "delete":
                        app.lib.delete(m.group(1))
                    else:
                        app.update_meeting(m.group(1), b)
                    return self._json({})
                if p == "/api/open":
                    app.open(b.get("id"), b.get("kind", "folder"))
                    return self._json({})
                if p == "/api/models/download":
                    s = catalog.by_id(b.get("set")) or catalog.recommended()
                    app.settings.update(model_set=s.id)
                    app.dl.start(s)
                    return self._json({})
                if p == "/api/models/cancel":
                    app.dl.cancel = True
                    return self._json({})
                if p == "/api/models/use":
                    s = catalog.by_id(b.get("set"))
                    if s and catalog.is_installed(s):
                        app.settings.update(model_set=s.id)
                    return self._json({})
                if p == "/api/settings":
                    kw = {}
                    if "vocabulary" in b:
                        kw["vocabulary"] = [v.strip() for v in b["vocabulary"] if str(v).strip()]
                    for k, attr in (("organization", "organization"), ("defaultTemplate", "default_template"),
                                    ("trackTasks", "track_tasks"), ("watchDownloads", "watch_downloads"),
                                    ("language", "language")):
                        if k in b:
                            kw[attr] = b[k]
                    app.settings.update(**kw)
                    return self._json({})
                return self._json({"error": "not found"}, 404)
            except Exception as e:  # noqa: BLE001
                log.exception("Fehler bei %s", p)
                return self._json({"error": str(e)}, 400)

        def _upload(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            name = Path((q.get("name") or ["Aufnahme.wav"])[0]).name
            n = int(self.headers.get("Content-Length") or 0)
            tmpdir = Path(tempfile.mkdtemp(prefix="pk-upload-", dir=app_data()))
            target = tmpdir / name
            with open(target, "wb") as out:
                left = n
                while left > 0:
                    chunk = self.rfile.read(min(left, 1 << 20))
                    if not chunk:
                        break
                    out.write(chunk)
                    left -= len(chunk)
            try:
                mt = int((q.get("mtime") or ["0"])[0] or 0) / 1000
                if mt > 0:
                    os.utime(target, (mt, mt))
            except (ValueError, OSError):
                pass
            try:
                res = app.import_path(target)
            finally:
                try:
                    target.unlink()
                    tmpdir.rmdir()
                except OSError:
                    pass
            return self._json(res)

    return H


def serve(app: App, port: int = 0) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app))
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    inst = app_data() / "instance.json"
    inst.write_text(json.dumps({"port": srv.server_address[1], "token": app.token, "pid": os.getpid()}))
    return srv
