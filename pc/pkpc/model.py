"""Datenmodell, identisch zur Android-App (data/Model.kt), inkl. gleichem JSON-Format.

So kann eine Besprechung ohne Umwandlung zwischen Handy und PC hin- und hergehen.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field, replace

PROTOCOL_MODES = {"ERGEBNIS": "Ergebnisprotokoll", "WORT": "Wortprotokoll"}


@dataclass(frozen=True)
class Template:
    name: str
    label: str
    description: str
    document_title: str
    signatures: tuple[str, ...]


TEMPLATES: dict[str, Template] = {t.name: t for t in [
    Template("ALLGEMEIN", "Allgemein", "Für jede Art von Besprechung", "Ergebnisprotokoll", ()),
    Template("DIENSTBESPRECHUNG", "Dienstbesprechung", "Team, Organisation, Termine",
             "Protokoll der Dienstbesprechung", ("Protokollführung", "Leitung")),
    Template("FALLBESPRECHUNG", "Fallbesprechung", "Je Fall: Lage, Einschätzung, Maßnahmen",
             "Protokoll der Fallbesprechung", ("Protokollführung", "Fallverantwortliche Fachkraft")),
    Template("UEBERGABE", "Übergabe", "Schicht- oder Dienstübergabe je Person", "Übergabeprotokoll",
             ("Übergebende Fachkraft", "Übernehmende Fachkraft")),
    Template("VEREINSSITZUNG", "Vereinssitzung", "TOPs, Abstimmungen, Beschlussfähigkeit",
             "Sitzungsprotokoll", ("Sitzungsleitung", "Protokollführung")),
]}


def template_of(name: str | None) -> Template:
    return TEMPLATES.get(name or "", TEMPLATES["ALLGEMEIN"])


@dataclass
class Segment:
    speaker_id: int
    start: float
    end: float
    text: str


@dataclass
class Speaker:
    id: int
    name: str
    profile_id: str | None = None


@dataclass
class TaskItem:
    text: str
    owner: str = "offen"
    due: str = "offen"
    done: bool = False
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    ref: int = -1
    done_note: str | None = None


@dataclass
class FollowUp:
    task_id: str
    meeting_id: str
    text: str
    owner: str
    done: bool
    ref: int = -1


@dataclass
class Topic:
    title: str
    points: list[str]
    refs: list[int] = field(default_factory=list)


@dataclass
class Summary:
    overview: str = ""
    topics: list[Topic] = field(default_factory=list)
    decisions: list[str] = field(default_factory=list)
    open_points: list[str] = field(default_factory=list)
    decision_refs: list[int] = field(default_factory=list)
    open_point_refs: list[int] = field(default_factory=list)

    @property
    def is_empty(self) -> bool:
        return not self.overview.strip() and not self.topics


@dataclass
class Meeting:
    id: str
    title: str
    created_at: int                 # Epoch-Millis
    duration_sec: float = 0.0
    audio_file: str = ""
    status: str = "RECORDED"
    mode: str = "ERGEBNIS"
    expected_speakers: int = 0
    segments: list[Segment] = field(default_factory=list)
    speakers: list[Speaker] = field(default_factory=list)
    summary: Summary = field(default_factory=Summary)
    tasks: list[TaskItem] = field(default_factory=list)
    error: str | None = None
    agenda: list[str] = field(default_factory=list)
    follow_ups: list[FollowUp] = field(default_factory=list)
    sensitive_terms: list[str] = field(default_factory=list)
    audio_deleted: bool = False
    template: str = "ALLGEMEIN"
    location: str = ""
    chair: str = ""
    minute_taker: str = ""
    imported_from: str | None = None

    def speaker_name(self, sid: int) -> str:
        for s in self.speakers:
            if s.id == sid:
                return s.name
        return f"Person {sid + 1}"

    def copy(self, **kw) -> "Meeting":
        return replace(self, **kw)


# ---------------------------------------------------------------- JSON (wie Model.kt)

def meeting_to_json(m: Meeting) -> dict:
    s = m.summary
    return {
        "id": m.id, "title": m.title, "createdAt": m.created_at,
        "durationSec": float(m.duration_sec), "audioFile": m.audio_file,
        "status": m.status, "mode": m.mode, "expectedSpeakers": m.expected_speakers,
        "error": m.error, "agenda": list(m.agenda), "sensitiveTerms": list(m.sensitive_terms),
        "audioDeleted": m.audio_deleted, "template": m.template,
        "location": m.location, "chair": m.chair, "minuteTaker": m.minute_taker,
        "importedFrom": m.imported_from,
        "followUps": [{"taskId": f.task_id, "meetingId": f.meeting_id, "text": f.text,
                       "owner": f.owner, "done": f.done, "ref": f.ref} for f in m.follow_ups],
        "segments": [{"s": g.speaker_id, "a": float(g.start), "b": float(g.end), "t": g.text}
                     for g in m.segments],
        "speakers": [{"id": p.id, "name": p.name, "profileId": p.profile_id} for p in m.speakers],
        "summary": {
            "overview": s.overview,
            "topics": [{"title": t.title, "points": list(t.points), "refs": list(t.refs)}
                       for t in s.topics],
            "decisions": list(s.decisions), "openPoints": list(s.open_points),
            "decisionRefs": list(s.decision_refs), "openPointRefs": list(s.open_point_refs),
        },
        "tasks": [{"text": t.text, "owner": t.owner, "due": t.due, "done": t.done, "id": t.id,
                   "ref": t.ref, "doneNote": t.done_note} for t in m.tasks],
    }


def _strs(v) -> list[str]:
    return [str(x) for x in (v or [])]


def _ints(v) -> list[int]:
    out = []
    for x in v or []:
        try:
            out.append(int(x))
        except (TypeError, ValueError):
            out.append(-1)
    return out


def meeting_from_json(o: dict) -> Meeting:
    sm = o.get("summary") or {}
    mid = str(o["id"])
    return Meeting(
        id=mid,
        title=str(o.get("title") or ""),
        created_at=int(o.get("createdAt") or 0),
        duration_sec=float(o.get("durationSec") or 0.0),
        audio_file=str(o.get("audioFile") or ""),
        status=str(o.get("status") or "RECORDED"),
        mode=o.get("mode") if o.get("mode") in PROTOCOL_MODES else "ERGEBNIS",
        expected_speakers=int(o.get("expectedSpeakers") or 0),
        error=o.get("error"),
        segments=[Segment(int(g.get("s", 0)), float(g.get("a", 0)), float(g.get("b", 0)),
                          str(g.get("t", ""))) for g in o.get("segments") or []],
        speakers=[Speaker(int(p.get("id", 0)), str(p.get("name", "")), p.get("profileId"))
                  for p in o.get("speakers") or []],
        summary=Summary(
            overview=str(sm.get("overview") or ""),
            topics=[Topic(str(t.get("title") or ""), _strs(t.get("points")), _ints(t.get("refs")))
                    for t in sm.get("topics") or []],
            decisions=_strs(sm.get("decisions")),
            open_points=_strs(sm.get("openPoints")),
            decision_refs=_ints(sm.get("decisionRefs")),
            open_point_refs=_ints(sm.get("openPointRefs")),
        ),
        tasks=[TaskItem(str(t.get("text") or ""), str(t.get("owner") or "offen"),
                        str(t.get("due") or "offen"), bool(t.get("done")),
                        id=str(t.get("id") or f"{mid}-{i}"), ref=int(t.get("ref", -1)),
                        done_note=t.get("doneNote"))
               for i, t in enumerate(o.get("tasks") or [])],
        agenda=_strs(o.get("agenda")),
        sensitive_terms=_strs(o.get("sensitiveTerms")),
        audio_deleted=bool(o.get("audioDeleted")),
        template=template_of(o.get("template")).name,
        location=str(o.get("location") or ""),
        chair=str(o.get("chair") or ""),
        minute_taker=str(o.get("minuteTaker") or ""),
        imported_from=o.get("importedFrom"),
        follow_ups=[FollowUp(str(f.get("taskId", "")), str(f.get("meetingId", "")),
                             str(f.get("text", "")), str(f.get("owner", "")),
                             bool(f.get("done")), int(f.get("ref", -1)))
                    for f in o.get("followUps") or []],
    )
