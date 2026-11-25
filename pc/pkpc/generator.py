"""Protokoll, Aufgaben, Quellenbelege, Aufgaben-Abgleich und sensible Begriffe aus dem Transkript.

Port von engine/ProtocolGenerator.kt der Android-App. Prompts und Grammatiken sind wortgleich
(geprüft durch tests/test_parity.py), damit Handy und PC gleich gute Protokolle schreiben.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Protocol

from . import dates
from .model import Segment, Speaker, Summary, TaskItem, Topic, template_of


class LlmError(Exception):
    pass


class TextModel(Protocol):
    context_size: int

    def count_tokens(self, text: str) -> int: ...

    def complete(self, system: str, user: str, max_tokens: int, temperature: float,
                 grammar: str | None, progress: Callable[[float], bool] | None) -> str: ...


@dataclass
class OpenTask:
    key: str
    task_id: str
    meeting_id: str
    text: str
    owner: str


@dataclass
class Result:
    summary: Summary
    tasks: list[TaskItem]
    completed: list[OpenTask] = field(default_factory=list)
    completed_refs: dict[str, int] = field(default_factory=dict)
    sensitive_terms: list[str] = field(default_factory=list)


_WEEKDAY_LONG = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]

SYSTEM = ("Du bist ein erfahrener, sorgfältiger Protokollführer in deutschen "
          "Unternehmen und Einrichtungen. Du schreibst sachlich, präzise und in korrektem "
          "Deutsch. Du hältst dich strikt an das Transkript und erfindest nichts. Erkennbare "
          "Fehler der automatischen Spracherkennung korrigierst du stillschweigend sinngemäß.")

NOTES_PROMPT = """%s

Hier ist Teil %d von %d des Transkripts. Notiere knapp in Stichpunkten:
- besprochene Themen mit den wesentlichen Aussagen (wer vertritt welche Position),
- Entscheidungen und Beschlüsse,
- offene Fragen,
- vereinbarte Aufgaben (wer, was, bis wann),
- Aussagen, dass frühere Aufgaben erledigt sind,
- Namen von Klienten, Patienten oder Bewohnern sowie Adressen und Diagnosen.
Behalte hinter jedem Stichpunkt die Zeilennummer aus dem Transkript in eckigen Klammern bei, z. B. [12]. Keine Einleitung.

Transkript:
%s"""

CITE_RULE = """Quellenangabe: Setze an das Ende JEDES Punkts, Beschlusses, offenen Punkts und jeder Aufgabe die Nummer der Transkriptzeile, die ihn belegt, in eckigen Klammern, z. B. "Ab März wird die Tour Nord geteilt. [8]"."""

TASK_RULES = """Regeln für "aufgaben" (alles, was jemand erledigen soll oder zugesagt hat):
- "aufgabe": kurz und konkret, mit dem Verb am Ende (z. B. "Drei Angebote für Möbel einholen [3]").
- "verantwortlich": Name der zuständigen Person, wie er im Gespräch fällt (wird jemand mit "Frau X" oder "Herr Y" angesprochen, diesen Namen verwenden). Sagt jemand "mache ich", ist es die sprechende Person. Unklar: "offen".
- "frist": die Zeitangabe GENAU so, wie sie im Gespräch genannt wurde (z. B. "Freitag", "Ende Januar", "15. Januar", "nächste Sitzung"). NICHT selbst in ein Datum umrechnen. Keine Angabe: "offen".
- Nur echte Aufgaben, nichts erfinden.
Regeln für "sensible_daten": Namen von Klienten, Patienten, Bewohnern oder anderen Personen, über die gesprochen wird (NICHT die Teilnehmenden), sowie Adressen, Telefonnummern, Geburtsdaten und Diagnosen, jeweils genau so geschrieben wie im Transkript. Leere Liste, wenn keine."""

AGENDA_RULE = """Tagesordnung: Gliedere "themen" nach der Tagesordnung, Titel jeweils "TOP 1: …", "TOP 2: …". Nicht besprochene TOPs trotzdem aufführen mit dem einzigen Punkt "Nicht besprochen.". Zusätzliche Themen danach unter "Sonstiges"."""

FOLLOWUP_RULE = """Regeln für "erledigte_aufgaben": Kennungen (z. B. "A2 [14]") der offenen Aufgaben aus früheren Besprechungen, von denen im Gespräch gesagt wird, dass sie erledigt sind, jeweils mit der belegenden Zeilennummer. Nur bei klarer Aussage. Leere Liste, wenn keine."""

TEMPLATE_RULES = {
    "DIENSTBESPRECHUNG": """Vorlage Dienstbesprechung: Gliedere "themen" nach Bereichen wie "Informationen", "Organisation und Dienstplan", "Termine", "Sonstiges" (nur Bereiche, die vorkamen; passende eigene Titel erlaubt). Termine immer mit Datum bzw. Zeitangabe nennen.""",
    "FALLBESPRECHUNG": """Vorlage Fallbesprechung: Je besprochenem Fall (Klient, Patient, Bewohner) ein Thema mit dem Titel "Fall: <Name oder Kürzel wie im Gespräch>". Punkte, soweit besprochen, in dieser Reihenfolge und mit diesen Anfängen: "Ausgangslage: …", "Einschätzung: …", "Vereinbarte Maßnahmen: …", "Nächste Überprüfung: …". Fachlich und wertschätzend formulieren, keine Vermutungen.""",
    "UEBERGABE": """Vorlage Übergabe: Je Person (Klient, Patient, Bewohner) ein Thema mit dem Namen als Titel. Punkte, soweit genannt: "Befinden: …", "Besonderheiten: …", "Pflege/Medikation: …", "Für den nächsten Dienst: …". Allgemeine Informationen unter dem Titel "Allgemein". Kurz und eindeutig.""",
    "VEREINSSITZUNG": """Vorlage Vereinssitzung: Formales Sitzungsprotokoll. Erstes Thema "Eröffnung und Formalien" (Begrüßung, Beschlussfähigkeit, Genehmigung der Tagesordnung und des letzten Protokolls), soweit erwähnt. Jede Abstimmung gehört in "beschluesse" im Format "<Beschlusstext> – Abstimmung: X Ja, Y Nein, Z Enthaltungen, angenommen." bzw. "… einstimmig angenommen." oder "… abgelehnt.". Zahlen nur, wenn sie genannt wurden.""",
}

SUMMARY_PROMPT = """%s

Erstelle aus dem folgenden Material ein professionelles Ergebnisprotokoll als JSON:
- "zusammenfassung": 3 bis 5 Sätze, worum es ging und was herauskam (ohne Quellenangabe).
- "themen": die besprochenen Themen in zeitlicher Reihenfolge. Je Thema ein kurzer "titel" und 2 bis 6 "punkte" mit Ergebnissen, Argumenten und Zahlen (vollständige, knappe Sätze).
- "beschluesse": Entscheidungen, die die Runde getroffen hat (z. B. "Ab März wird ..."). Reine Aufgabenverteilungen gehören NICHT hierher, sondern zu "aufgaben". Leere Liste, wenn keine.
- "offene_punkte": ungeklärte Fragen und vertagte Themen. Leere Liste, wenn keine.
- "aufgaben", "erledigte_aufgaben", "sensible_daten": siehe Regeln.

%s

%s"""

TASKS_PROMPT = """%s

Extrahiere alle Aufgaben, die in diesem Gespräch vereinbart, zugesagt oder jemandem zugewiesen wurden, als JSON. Keine Aufgaben: "aufgaben": []

%s

Transkript:
%s"""

JSON_STRING = (
    'string ::= "\\"" ( [^"\\\\\\x7F\\x00-\\x1F] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F]{4}) )* "\\""\n'
    'ws ::= [ \\t\\n]{0,20}\n'
    'strlist ::= "[" ws ( string ws ("," ws string ws)* )? "]"\n'
    'tasks ::= "[" ws ( task ws ("," ws task ws)* )? "]"\n'
    'task ::= "{" ws "\\"aufgabe\\":" ws string ws "," ws "\\"verantwortlich\\":" ws string ws "," ws "\\"frist\\":" ws string ws "}"\n'
    'tail ::= "," ws "\\"erledigte_aufgaben\\":" ws strlist ws "," ws "\\"sensible_daten\\":" ws strlist ws\n'
)

SUMMARY_GRAMMAR = (
    'root ::= "{" ws "\\"zusammenfassung\\":" ws string ws "," ws "\\"themen\\":" ws themen ws "," ws "\\"beschluesse\\":" ws strlist ws "," ws "\\"offene_punkte\\":" ws strlist ws "," ws "\\"aufgaben\\":" ws tasks ws tail "}"\n'
    'themen ::= "[" ws ( thema ws ("," ws thema ws)* )? "]"\n'
    'thema ::= "{" ws "\\"titel\\":" ws string ws "," ws "\\"punkte\\":" ws strlist ws "}"'
) + "\n" + JSON_STRING

TASKS_GRAMMAR = 'root ::= "{" ws "\\"aufgaben\\":" ws tasks ws tail "}"' + "\n" + JSON_STRING


def extra_rules(has_agenda: bool, has_open_tasks: bool, template: str = "ALLGEMEIN") -> str:
    out = TASK_RULES
    rule = TEMPLATE_RULES.get(template)
    if rule:
        out += "\n" + rule
    if has_agenda:
        out += "\n" + AGENDA_RULE
    out += "\n" + (FOLLOWUP_RULE if has_open_tasks else '"erledigte_aufgaben": immer leere Liste.')
    out += "\n" + CITE_RULE
    return out


class Cancelled(LlmError):
    def __init__(self):
        super().__init__("Abgebrochen")


class ProtocolGenerator:
    def __init__(self, model: TextModel):
        self.model = model

    def generate(self, title: str, date_millis: int, segments: list[Segment],
                 speakers: list[Speaker], want_summary: bool, agenda: list[str] | None = None,
                 open_tasks: list[OpenTask] | None = None, template: str = "ALLGEMEIN",
                 vocabulary: list[str] | None = None,
                 progress: Callable[[float, str], bool] = lambda f, t: True) -> Result:
        agenda = agenda or []
        open_tasks = open_tasks or []
        vocabulary = vocabulary or []
        names = {s.id: s.name for s in speakers}

        def name_of(i: int) -> str:
            return names.get(i, f"Person {i + 1}")

        lines = [f"[{i}] {name_of(s.speaker_id)}: {s.text}" for i, s in enumerate(segments)]
        participants = list(dict.fromkeys(name_of(s.speaker_id) for s in segments))
        dt = datetime.fromtimestamp(date_millis / 1000)
        date_text = f"{_WEEKDAY_LONG[dt.weekday()]}, {dt:%d.%m.%Y}"
        tpl = template_of(template)
        header = f"Besprechung: {title}\nDatum: {date_text}\nTeilnehmende: {', '.join(participants)}"
        if tpl.name != "ALLGEMEIN":
            header += f"\nArt: {tpl.label}"
        if vocabulary:
            header += "\nRichtige Schreibweise von Namen und Fachbegriffen: " + ", ".join(vocabulary[:60])
        if agenda:
            header += "\nTagesordnung:\n" + "".join(f"TOP {i + 1}: {t}\n" for i, t in enumerate(agenda))
        if open_tasks:
            header += "\nOffene Aufgaben aus früheren Besprechungen:\n" + \
                "".join(f"{t.key}: {t.text} ({t.owner})\n" for t in open_tasks)
        header = header.rstrip()

        # Platz für Systemprompt, Anweisungen und Antwort freihalten
        # Antwortlängen passend zum Kontext (bei kleinem Kontext sonst "Text zu lang")
        ctx = self.model.context_size
        summary_max = min(2600, ctx // 3)
        notes_max = min(1000, ctx // 5)
        tasks_max = min(1400, ctx // 4)
        reserve = summary_max + 900 + self.model.count_tokens(header)
        budget = max(1500, self.model.context_size - reserve)
        chunks = self.chunk(lines, budget)

        steps = (len(chunks) if len(chunks) > 1 and want_summary else 0) + \
            (1 if want_summary else len(chunks))
        state = {"step": 0}

        def prog(text: str, inner: float = 0.0) -> bool:
            return progress(min(1.0, max(0.0, (state["step"] + inner) / steps)), text)

        def inner(label: str):
            return lambda f: prog(label, f)

        extras = extra_rules(bool(agenda), bool(open_tasks), tpl.name)
        raws: list[str] = []
        if want_summary:
            if len(chunks) == 1:
                material = "Transkript:\n" + chunks[0]
            else:
                notes = []
                for i, c in enumerate(chunks):
                    label = f"Fasse Abschnitt {i + 1} von {len(chunks)} zusammen …"
                    if not prog(label):
                        raise Cancelled()
                    n = self.model.complete(SYSTEM, NOTES_PROMPT % (header, i + 1, len(chunks), c),
                                            notes_max, 0.2, None, inner(label))
                    state["step"] += 1
                    notes.append(f"Notizen zu Teil {i + 1}:\n{n}")
                material = "\n\n".join(notes)
            label = "Schreibe Protokoll und Aufgaben …"
            if not prog(label):
                raise Cancelled()
            # Notizen vieler Abschnitte können zusammen zu lang werden: dann gleichmäßig kürzen
            fitted = material
            for _ in range(12):
                tokens = self.model.count_tokens(SYSTEM) + \
                    self.model.count_tokens(SUMMARY_PROMPT % (header, extras, fitted))
                if tokens + summary_max + 64 <= ctx:
                    break
                fitted = shorten_evenly(fitted, 0.8)
            raws.append(self.model.complete(SYSTEM, SUMMARY_PROMPT % (header, extras, fitted),
                                            summary_max, 0.2, SUMMARY_GRAMMAR, inner(label)))
            state["step"] += 1
        else:
            for i, c in enumerate(chunks):
                label = (f"Ermittle Aufgaben ({i + 1}/{len(chunks)}) …" if len(chunks) > 1
                         else "Ermittle Aufgaben …")
                if not prog(label):
                    raise Cancelled()
                raws.append(self.model.complete(SYSTEM, TASKS_PROMPT % (header, extras, c),
                                                tasks_max, 0.0, TASKS_GRAMMAR, inner(label)))
                state["step"] += 1

        def safe_tasks(r: str) -> list[TaskItem]:
            try:
                return parse_tasks(r, len(segments))
            except Exception:
                return []

        if want_summary and not any(safe_tasks(r) for r in raws):
            for c in chunks:
                if not progress(0.97, "Prüfe Aufgaben …"):
                    raise Cancelled()
                raws.append(self.model.complete(SYSTEM, TASKS_PROMPT % (header, extras, c),
                                                tasks_max, 0.0, TASKS_GRAMMAR, None))

        meeting_date = dt.date()
        n = len(segments)
        summary = Summary()
        if want_summary:
            try:
                summary = parse_summary(raws[0], n)
            except Exception:
                summary = Summary()
        tasks = []
        for r in raws:
            for t in safe_tasks(r):
                t.due = dates.resolve(t.due, meeting_date)
                if t.ref < 0:
                    t.ref = locate_ref(t.text, segments)
                t.owner = resolve_owner(t.owner, t.ref, segments, name_of, t.text)
                tasks.append(t)
        done_keys: dict[str, int] = {}
        for r in raws:
            try:
                for k, ref in parse_completed(r, n):
                    done_keys.setdefault(k, ref)
            except Exception:
                pass
        completed = [t for t in open_tasks if t.key in done_keys]
        final_tasks = dedupe(tasks)
        sens = [s for r in raws for s in parse_sensitive(r, participants)]
        if tpl.name in ("FALLBESPRECHUNG", "UEBERGABE"):
            sens += addressed_clients(segments, final_tasks)
        progress(1.0, "Fertig")
        return Result(summary, final_tasks, completed,
                      {t.task_id: done_keys.get(t.key, -1) for t in completed},
                      _distinct_ci(sens))

    def chunk(self, lines: list[str], budget: int) -> list[str]:
        everything = "\n".join(lines)
        if self.model.count_tokens(everything) <= budget:
            return [everything]
        chunks, cur, cur_tokens = [], [], 0
        for line in lines:
            t = self.model.count_tokens(line) + 1
            if cur_tokens + t > budget and cur:
                chunks.append("\n".join(cur).strip())
                cur, cur_tokens = [], 0
            cur.append(line)
            cur_tokens += t
        if cur:
            chunks.append("\n".join(cur).strip())
        return chunks


def _distinct_ci(items: list[str]) -> list[str]:
    seen, out = set(), []
    for s in items:
        if s.lower() not in seen:
            seen.add(s.lower())
            out.append(s)
    return out


# ------------------------------------------------------------------ Nachbearbeitung

ADDRESS = re.compile(r"\b(Frau|Herr|Herrn)\s+(?:Dr\.\s+)?([A-ZÄÖÜ](?:[^\W\d_]|-){1,30})")
GENERIC = re.compile(r"^(Person \d+|Sprecher(in)? \d+|offen|unklar|unbekannt|keine angabe|niemand|alle|team|ich|sie|du|wir|er|ihr|-|n/?a|)$",
                     re.IGNORECASE)


def addressed_clients(segments: list[Segment], tasks: list[TaskItem]) -> list[str]:
    staff = set()
    for t in tasks:
        staff.update(m.group(2).lower() for m in ADDRESS.finditer(t.owner))
        staff.update(w.lower() for w in t.owner.split(" "))
    found = [m.group(2) for s in segments for m in ADDRESS.finditer(s.text)]
    return _distinct_ci([f for f in found if f.lower() not in staff])


def resolve_owner(owner: str, ref: int, segments: list[Segment], name_of, task_text: str = "") -> str:
    if not GENERIC.match(owner.strip()):
        return owner
    m = ADDRESS.search(task_text)
    if m:
        return ("Herr" if m.group(1) == "Herrn" else m.group(1)) + " " + m.group(2)
    if not (0 <= ref < len(segments)):
        return owner
    for i in (ref, ref - 1):
        if not (0 <= i < len(segments)):
            continue
        seg = segments[i]
        m = ADDRESS.search(seg.text)
        if not m:
            continue
        name = ("Herr" if m.group(1) == "Herrn" else m.group(1)) + " " + m.group(2)
        if name_of(seg.speaker_id).lower() == name.lower():
            continue
        return name
    return owner


_WORD = re.compile(r"[^\W_]{4,}")
_STOP = {"bitte", "sich", "sind", "wird", "werden", "noch", "dann", "auch", "eine", "einen", "einer",
         "dass", "diese", "dieser", "nicht", "habe", "haben", "kann", "können", "sollen"}


def _words(t: str) -> set[str]:
    return {w[:6] for w in _WORD.findall(t.lower()) if w[:6] not in _STOP}


def locate_ref(task_text: str, segments: list[Segment]) -> int:
    w = _words(task_text)
    if len(w) < 2:
        return -1
    best, best_score = -1, 1
    for i, s in enumerate(segments):
        score = len(_words(s.text) & w)
        if score > best_score:
            best, best_score = i, score
    return best


_REF = re.compile(r"\s*\[(\d{1,5})(?:\s*[,;–-]\s*\d{1,5})*]")


def split_ref(raw: str, segment_count: int) -> tuple[str, int]:
    ref = -1
    for m in _REF.finditer(raw):
        idx = int(m.group(1))
        if ref < 0 and 0 <= idx < segment_count:
            ref = idx
    text = re.sub(r"\s+([.,;:!?])", r"\1", _REF.sub("", raw)).strip()
    return text, ref


def shorten_evenly(text: str, factor: float) -> str:
    """Kürzt jeden Absatz (durch Leerzeile getrennt) auf factor seiner Länge, an Zeilengrenzen."""
    out = []
    for part in text.split("\n\n"):
        keep = int(len(part) * factor)
        if len(part) <= keep:
            out.append(part)
            continue
        t = part[:keep]
        nl = t.rfind("\n")
        out.append(t[:nl] if nl > keep // 2 else t)
    return "\n\n".join(out)


def repair_json(raw: str) -> str | None:
    stack: list[str] = []
    in_string = escape = False
    cut, cut_stack = -1, []
    for i, c in enumerate(raw):
        if in_string:
            if escape:
                escape = False
            elif c == "\\":
                escape = True
            elif c == '"':
                in_string = False
            continue
        if c == '"':
            in_string = True
        elif c in "{[":
            stack.append(c)
        elif c in "}]":
            if not stack:
                break
            stack.pop()
            if not stack:
                return raw[:i + 1]
            cut, cut_stack = i, list(stack)
        elif c == ",":
            if stack:
                cut, cut_stack = i - 1, list(stack)
    if cut < 0:
        return None
    s = raw[:cut + 1].rstrip().rstrip(",")
    for o in reversed(cut_stack):
        s += "}" if o == "{" else "]"
    return s


def _json_object(raw: str) -> dict:
    s, e = raw.find("{"), raw.rfind("}")
    if s >= 0 and e > s:
        try:
            v = json.loads(raw[s:e + 1])
            if isinstance(v, dict):
                return v
        except json.JSONDecodeError:
            pass
    if s >= 0:
        r = repair_json(raw[s:])
        if r:
            try:
                v = json.loads(r)
                if isinstance(v, dict):
                    return v
            except json.JSONDecodeError:
                pass
    raise LlmError("Ungültige Antwort des Sprachmodells")


def _strings(v) -> list[str]:
    if not isinstance(v, list):
        return []
    return [str(x).strip() for x in v if isinstance(x, (str, int, float)) and str(x).strip()]


def parse_summary(raw: str, segment_count: int = 10 ** 9) -> Summary:
    o = _json_object(raw)
    decisions = [split_ref(x, segment_count) for x in _strings(o.get("beschluesse"))]
    open_ = [split_ref(x, segment_count) for x in _strings(o.get("offene_punkte"))]
    topics = []
    for t in o.get("themen") or []:
        if not isinstance(t, dict):
            continue
        pts = [split_ref(p, segment_count) for p in _strings(t.get("punkte"))]
        title = split_ref(str(t.get("titel") or ""), 0)[0]
        if title:
            topics.append(Topic(title, [p[0] for p in pts], [p[1] for p in pts]))
    return Summary(
        overview=split_ref(str(o.get("zusammenfassung") or ""), 0)[0],
        topics=topics,
        decisions=[d[0] for d in decisions], open_points=[d[0] for d in open_],
        decision_refs=[d[1] for d in decisions], open_point_refs=[d[1] for d in open_],
    )


def _norm_field(v: str) -> str:
    s = split_ref(v, 0)[0] or "offen"
    if s.lower() in ("unbekannt", "n/a", "keine") or s == "-":
        return "offen"
    return s


def parse_tasks(raw: str, segment_count: int = 10 ** 9) -> list[TaskItem]:
    arr = _json_object(raw).get("aufgaben")
    if not isinstance(arr, list):
        return []
    out = []
    for t in arr:
        if not isinstance(t, dict):
            continue
        text, ref = split_ref(str(t.get("aufgabe") or ""), segment_count)
        if not text:
            continue
        out.append(TaskItem(text, _norm_field(str(t.get("verantwortlich") or "")),
                            _norm_field(str(t.get("frist") or "")), ref=ref))
    return out


def parse_completed(raw: str, segment_count: int = 10 ** 9) -> list[tuple[str, int]]:
    try:
        items = _strings(_json_object(raw).get("erledigte_aufgaben"))
    except LlmError:
        return []
    out = []
    for s in items:
        m = re.search(r"\bA\d{1,3}\b", s)
        if m:
            out.append((m.group(0), split_ref(s.replace(m.group(0), ""), segment_count)[1]))
    return out


def parse_sensitive(raw: str, participants: list[str] | None = None) -> list[str]:
    people = {p.lower() for p in participants or []}
    try:
        items = _strings(_json_object(raw).get("sensible_daten"))
    except LlmError:
        return []
    out = []
    for s in items:
        v = split_ref(s, 0)[0].strip().strip('"„“')
        if len(v) >= 3 and v.lower() not in people and not v.lower().startswith("person "):
            out.append(v)
    return out


def dedupe(tasks: list[TaskItem]) -> list[TaskItem]:
    def key(t: TaskItem) -> set[str]:
        return {w for w in re.sub(r"[^a-zäöüß0-9 ]", "", t.text.lower()).split(" ") if len(w) > 3}

    out: list[TaskItem] = []
    for t in tasks:
        k = key(t)
        dup = -1
        for i, o in enumerate(out):
            ok = key(o)
            if len(k & ok) / max(1, len(k | ok)) >= 0.6:
                dup = i
                break
        if dup < 0:
            out.append(t)
        elif out[dup].owner == "offen" and t.owner != "offen":
            out[dup] = t
    return out
