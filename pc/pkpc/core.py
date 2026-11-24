"""Besprechungen speichern und verarbeiten (wie MeetingRepository + ProcessingService der App)."""
from __future__ import annotations

import json
import logging
import os
import queue
import shutil
import threading
import time
import traceback
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

from . import catalog, export, package, vocabulary
from .model import FollowUp, Meeting, Speaker, meeting_from_json, meeting_to_json

log = logging.getLogger("pkpc")


# ------------------------------------------------------------------ Einstellungen

@dataclass
class Settings:
    language: str = "de"
    vocabulary: list[str] = field(default_factory=list)
    organization: str = ""
    model_set: str = ""
    default_template: str = "ALLGEMEIN"
    track_tasks: bool = True
    watch_downloads: bool = True
    seen_files: list[str] = field(default_factory=list)   # schon angebotene Dateien im Download-Ordner


class SettingsStore:
    def __init__(self, root: Path):
        self.file = root / "settings.json"
        self.lock = threading.Lock()
        self.value = Settings()
        if self.file.exists():
            try:
                data = json.loads(self.file.read_text("utf-8"))
                known = Settings.__dataclass_fields__
                self.value = Settings(**{k: v for k, v in data.items() if k in known})
            except (ValueError, TypeError):
                pass

    def update(self, **kw) -> Settings:
        with self.lock:
            for k, v in kw.items():
                if hasattr(self.value, k):
                    setattr(self.value, k, v)
            tmp = self.file.with_suffix(".tmp")
            tmp.write_text(json.dumps(asdict(self.value), ensure_ascii=False, indent=1), "utf-8")
            tmp.replace(self.file)
            return self.value


# ------------------------------------------------------------------ Ablage

class Library:
    def __init__(self, root: Path):
        self.root = root / "meetings"
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.meetings: dict[str, Meeting] = {}
        self.extra: dict[str, dict] = {}      # je Besprechung: Herkunft, Paketdaten, Ausgabedateien
        for d in self.root.iterdir():
            f = d / "meeting.json"
            if not f.exists():
                continue
            try:
                data = json.loads(f.read_text("utf-8"))
                m = meeting_from_json(data["meeting"] if "meeting" in data else data)
                if m.status in ("QUEUED", "TRANSCRIBING", "SUMMARIZING"):
                    m = m.copy(status="RECORDED")
                if m.status == "RECORDING":
                    m = m.copy(status="RECORDED" if Path(m.audio_file).exists() else "ERROR",
                               error=None if Path(m.audio_file).exists() else "Aufnahme wurde unterbrochen.")
                self.meetings[m.id] = m
                self.extra[m.id] = data.get("extra", {}) if "meeting" in data else {}
            except Exception:  # noqa: BLE001
                log.exception("Besprechung %s nicht lesbar", d)
        self.progress: dict[str, tuple[float, str]] = {}

    def dir_for(self, mid: str) -> Path:
        d = self.root / mid
        d.mkdir(parents=True, exist_ok=True)
        return d

    def all(self) -> list[Meeting]:
        with self.lock:
            return sorted(self.meetings.values(), key=lambda m: -m.created_at)

    def get(self, mid: str) -> Meeting | None:
        with self.lock:
            return self.meetings.get(mid)

    def save(self, m: Meeting, extra: dict | None = None) -> Meeting:
        with self.lock:
            if extra is not None:
                self.extra[m.id] = {**self.extra.get(m.id, {}), **extra}
            self.meetings[m.id] = m
            f = self.dir_for(m.id) / "meeting.json"
            tmp = f.with_suffix(".tmp")
            tmp.write_text(json.dumps({"meeting": meeting_to_json(m), "extra": self.extra.get(m.id, {})},
                                      ensure_ascii=False), "utf-8")
            tmp.replace(f)
            return m

    def update(self, mid: str, change, persist: bool = True) -> Meeting | None:
        with self.lock:
            cur = self.meetings.get(mid)
            if cur is None:
                return None
            new = change(cur)
            if persist:
                self.save(new)
            else:
                self.meetings[mid] = new
            return new

    def delete(self, mid: str) -> None:
        with self.lock:
            self.meetings.pop(mid, None)
            self.extra.pop(mid, None)
            shutil.rmtree(self.root / mid, ignore_errors=True)

    def create(self, title: str, status: str = "RECORDING", created_at: int | None = None) -> Meeting:
        mid = str(uuid.uuid4())
        m = Meeting(id=mid, title=title, created_at=created_at or int(time.time() * 1000),
                    audio_file=str(self.dir_for(mid) / "audio.wav"), status=status)
        return self.save(m)

    def candidates_for(self, meeting: Meeting, limit: int = 20, days: int = 90) -> list[tuple[Meeting, object]]:
        start = meeting.created_at - days * 24 * 3600 * 1000
        out = []
        for m in self.all():
            if m.id == meeting.id or not (start <= m.created_at < meeting.created_at):
                continue
            out += [(m, t) for t in m.tasks if not t.done]
        return out[:limit]

    def set_task_done(self, mid: str, task_id: str, done: bool, note: str | None = None) -> None:
        def ch(m: Meeting) -> Meeting:
            tasks = []
            for t in m.tasks:
                if t.id == task_id:
                    t = type(t)(**{**t.__dict__, "done": done, "done_note": note if done else None})
                tasks.append(t)
            return m.copy(tasks=tasks)
        self.update(mid, ch)


# ------------------------------------------------------------------ Verarbeitung

def output_folder(m: Meeting, docs: Path) -> Path:
    day = datetime.fromtimestamp(m.created_at / 1000).strftime("%Y-%m-%d %H-%M")
    return docs / f"{day} {export.sanitize_file_name(m.title)}"


class Processor:
    """Arbeitet Besprechungen nacheinander ab. Alles lokal."""

    def __init__(self, lib: Library, settings: SettingsStore, docs: Path):
        self.lib = lib
        self.settings = settings
        self.docs = docs
        self.q: "queue.Queue[tuple[str, bool]]" = queue.Queue()
        self.queued: set[str] = set()
        self.cancelled = threading.Event()
        self.current: str | None = None
        self._llm = None
        threading.Thread(target=self._worker, daemon=True).start()

    def model_set(self) -> catalog.ModelSet | None:
        s = catalog.by_id(self.settings.value.model_set)
        if s and catalog.is_installed(s):
            return s
        inst = catalog.installed_sets()
        return inst[0] if inst else None

    def enqueue(self, mid: str, summary_only: bool = False) -> None:
        if mid in self.queued:
            return
        self.queued.add(mid)
        self.lib.update(mid, lambda m: m.copy(status="QUEUED", error=None))
        self.lib.progress[mid] = (0.0, "Wartet auf Verarbeitung …")
        self.q.put((mid, summary_only))

    def cancel(self) -> None:
        self.cancelled.set()
        if self._llm is not None:
            self._llm.cancel()

    def _worker(self) -> None:
        while True:
            mid, summary_only = self.q.get()
            self.cancelled.clear()
            self.current = mid
            try:
                self._run(mid, summary_only)
            finally:
                self.current = None
                self.queued.discard(mid)
                self.lib.progress.pop(mid, None)

    def _prog(self, mid: str, status: str, f: float, text: str) -> None:
        self.lib.progress[mid] = (max(0.0, min(1.0, f)), text)
        m = self.lib.get(mid)
        if m and m.status != status:
            self.lib.update(mid, lambda x: x.copy(status=status), persist=False)

    def _run(self, mid: str, summary_only: bool) -> None:
        from .generator import LlmError
        m = self.lib.get(mid)
        if m is None:
            return
        s = self.model_set()
        if s is None:
            self._fail(mid, "Die KI ist noch nicht eingerichtet. Bitte zuerst die KI-Modelle laden.")
            return
        try:
            if not summary_only or not m.segments:
                self._transcribe(mid, s)
            if self.cancelled.is_set():
                return self._abort(mid)
            self._summarize(mid, s)
            if self.cancelled.is_set():
                return self._abort(mid)
            m = self.lib.update(mid, lambda x: x.copy(status="DONE", error=None))
            try:
                self.write_outputs(m)
            except Exception:  # noqa: BLE001  Protokoll ist fertig, nur die Dateien fehlen
                log.error("Export fehlgeschlagen\n%s", traceback.format_exc())
        except LlmError as e:
            if self.cancelled.is_set():
                self._abort(mid)
            else:
                self._fail(mid, str(e))
        except MemoryError:
            self._fail(mid, "Zu wenig Arbeitsspeicher. Bitte andere Programme schließen.")
        except Exception as e:  # noqa: BLE001
            log.error("Verarbeitung fehlgeschlagen\n%s", traceback.format_exc())
            self._fail(mid, f"Verarbeitung fehlgeschlagen: {e}")

    def _transcribe(self, mid: str, s: catalog.ModelSet) -> None:
        from . import audio, transcribe as tr
        self._prog(mid, "TRANSCRIBING", 0.0, "Lade Aufnahme …")
        m = self.lib.get(mid)
        if not m.audio_file or not Path(m.audio_file).exists():
            raise RuntimeError("Die Aufnahme fehlt.")
        samples = audio.read_mono16k(m.audio_file)
        if len(samples) < 16000:
            raise RuntimeError("Die Aufnahme ist zu kurz.")
        self.lib.update(mid, lambda x: x.copy(duration_sec=len(samples) / 16000))
        self._prog(mid, "TRANSCRIBING", 0.01, "Erkenne Sprecher …")
        turns = tr.diarize(samples, m.expected_speakers,
                           lambda f: (self._prog(mid, "TRANSCRIBING", 0.2 * f, "Erkenne Sprecher …"),
                                      not self.cancelled.is_set())[1])
        if self.cancelled.is_set():
            return
        chunks = tr.plan_chunks(turns, samples)
        count = max((c.speaker for c in chunks), default=0) + 1
        speakers = [Speaker(i, f"Person {i + 1}") for i in range(count)]
        self.lib.update(mid, lambda x: x.copy(speakers=speakers, segments=[]))

        def on_prog(done: int, total: int, partial) -> bool:
            self.lib.update(mid, lambda x: x.copy(segments=list(partial)), persist=False)
            self._prog(mid, "TRANSCRIBING", 0.2 + 0.8 * done / total,
                       f"Schreibe mit … {int(100 * done / total)} %")
            return not self.cancelled.is_set()

        lang = self.lib.extra.get(mid, {}).get("language") or self.settings.value.language
        raw = tr.transcribe(samples, chunks, s.whisper, lang, on_prog)
        if self.cancelled.is_set():
            return
        vocab = self.vocab(mid)
        segs = [type(g)(g.speaker_id, g.start, g.end, vocabulary.correct(g.text, vocab)) for g in raw] if vocab else raw
        used = {g.speaker_id for g in segs}
        self.lib.update(mid, lambda x: x.copy(segments=segs, speakers=[p for p in speakers if p.id in used]))
        if not segs:
            raise RuntimeError("In der Aufnahme wurde keine Sprache erkannt.")

    def vocab(self, mid: str) -> list[str]:
        ex = self.lib.extra.get(mid, {})
        return vocabulary.clean_terms(list(ex.get("vocabulary") or []) + self.settings.value.vocabulary)

    def _summarize(self, mid: str, s: catalog.ModelSet) -> None:
        from .generator import OpenTask, ProtocolGenerator
        from .llm import LocalLlm
        m = self.lib.get(mid)
        if not m.segments:
            return
        self._prog(mid, "SUMMARIZING", 0.0, "Lade Sprachmodell …")
        ex = self.lib.extra.get(mid, {})
        if ex.get("openTasks"):          # vom Handy mitgeschickte offene Aufgaben
            open_tasks = [OpenTask(f"A{i + 1}", str(t.get("taskId")), str(t.get("meetingId")),
                                   str(t.get("text")), str(t.get("owner") or "offen"))
                          for i, t in enumerate(ex["openTasks"])]
        elif self.settings.value.track_tasks:
            open_tasks = [OpenTask(f"A{i + 1}", t.id, om.id, t.text, t.owner)
                          for i, (om, t) in enumerate(self.lib.candidates_for(m))]
        else:
            open_tasks = []
        llm = LocalLlm(catalog.path(s.llm.name), s.context, s.no_think)
        self._llm = llm
        try:
            gen = ProtocolGenerator(llm)
            res = gen.generate(
                title=m.title, date_millis=m.created_at, segments=m.segments, speakers=m.speakers,
                want_summary=m.mode == "ERGEBNIS", agenda=m.agenda, open_tasks=open_tasks,
                template=m.template, vocabulary=self.vocab(mid),
                progress=lambda f, t: (self._prog(mid, "SUMMARIZING", f, t), not self.cancelled.is_set())[1],
            )
        finally:
            self._llm = None
            llm.close()
        date_text = datetime.fromtimestamp(m.created_at / 1000).strftime("%d.%m.%Y")
        done_ids = {t.task_id for t in res.completed}
        for t in res.completed:
            if self.lib.get(t.meeting_id):
                self.lib.set_task_done(t.meeting_id, t.task_id, True,
                                       f"Erledigt laut Besprechung „{m.title}“ vom {date_text}")
        follow = [FollowUp(t.task_id, t.meeting_id, t.text, t.owner, t.task_id in done_ids,
                           res.completed_refs.get(t.task_id, -1)) for t in open_tasks]

        def apply(x: Meeting) -> Meeting:
            done_texts = {t.text for t in x.tasks if t.done}
            tasks = [type(t)(**{**t.__dict__, "done": True}) if t.text in done_texts else t for t in res.tasks]
            seen, terms = set(), []
            for term in x.sensitive_terms + res.sensitive_terms:
                if term.lower() not in seen:
                    seen.add(term.lower())
                    terms.append(term)
            return x.copy(summary=res.summary if x.mode == "ERGEBNIS" else x.summary, tasks=tasks,
                          follow_ups=follow, sensitive_terms=terms)
        self.lib.update(mid, apply)

    def write_outputs(self, m: Meeting) -> dict[str, str]:
        """Word, PDF, Markdown und die Datei fürs Handy in den Ergebnisordner schreiben."""
        old = self.lib.extra.get(m.id, {}).get("folder")
        folder = output_folder(m, self.docs)
        if old and Path(old).exists() and Path(old) != folder:
            try:
                Path(old).rename(folder)
            except OSError:
                pass
        files = export.export_all(m, folder, self.settings.value.organization)
        ex = self.lib.extra.get(m.id, {})
        pkg = package.Package(m, ex.get("language") or self.settings.value.language,
                              self.vocab(m.id), self.settings.value.organization, created_by="pc")
        audio_path = Path(m.audio_file) if m.audio_file and Path(m.audio_file).exists() else None
        phone = package.write(folder / f"{export.sanitize_file_name(m.title)} (fürs Handy){package.EXT}",
                              pkg, audio_path)
        out = {k: str(v) for k, v in files.items()} | {"phone": str(phone)}
        self.lib.save(m, {"folder": str(folder), "files": out})
        return out

    def _fail(self, mid: str, msg: str) -> None:
        self.lib.update(mid, lambda m: m.copy(status="ERROR", error=msg))

    def _abort(self, mid: str) -> None:
        def ch(m: Meeting) -> Meeting:
            back = "DONE" if m.segments and m.speakers and not m.summary.is_empty else "RECORDED"
            return m.copy(status=back)
        self.lib.update(mid, ch)


def import_file(lib: Library, path: Path, default_template: str) -> tuple[Meeting, bool]:
    """Datei übernehmen: .protokoll (vom Handy) oder Audio/Video. Rückgabe: (Besprechung, schon fertig)."""
    if package.is_package(path):
        tmp_id = str(uuid.uuid4())
        target = lib.dir_for(tmp_id) / "audio.wav"
        pkg = package.read(path, target)
        m = pkg.meeting
        existing = lib.get(m.id)
        if existing is not None:
            # dieselbe Besprechung erneut vom Handy: Audio übernehmen, Inhalt aktualisieren
            shutil.rmtree(lib.root / tmp_id, ignore_errors=True)
            pkg2 = package.read(path, lib.dir_for(m.id) / "audio.wav")
            m = pkg2.meeting
        else:
            os.replace(lib.root / tmp_id, lib.root / m.id)
            m.audio_file = str(lib.dir_for(m.id) / "audio.wav") if Path(lib.root / m.id / "audio.wav").exists() else ""
        m = m.copy(status="DONE" if m.status == "DONE" and m.segments else "RECORDED", error=None)
        lib.save(m, {"source": "phone", "sourceFile": str(path), "language": pkg.language,
                     "vocabulary": pkg.vocabulary, "openTasks": pkg.open_tasks})
        return m, m.status == "DONE"
    from .audio import AUDIO_EXT
    if path.suffix.lower() not in AUDIO_EXT:
        raise ValueError("Dieses Dateiformat wird nicht unterstützt. Bitte eine Audio- oder Videodatei "
                         "oder eine .protokoll-Datei vom Handy verwenden.")
    created = int(path.stat().st_mtime * 1000)
    m = lib.create(path.stem.replace("_", " ").strip() or "Besprechung", status="RECORDED", created_at=created)
    from . import audio
    samples = audio.read_mono16k(path)
    if len(samples) < 16000:
        lib.delete(m.id)
        raise ValueError("Die Aufnahme ist zu kurz oder enthält keinen Ton.")
    audio.write_wav(m.audio_file, samples)
    m = lib.save(m.copy(duration_sec=len(samples) / 16000, template=default_template),
                 {"source": "file", "sourceFile": str(path)})
    return m, False
