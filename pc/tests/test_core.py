"""Tests der portierten Logik (gleiche Fälle wie die Kotlin-Tests der App) und des Austauschformats."""
import json
import zipfile
from datetime import date

import numpy as np

from pkpc import audio, dates, export, generator as g, package, vocabulary as v
from pkpc.model import Meeting, Segment, Speaker, Summary, TaskItem, Topic, meeting_from_json, meeting_to_json

MONDAY = date(2026, 11, 23)


def test_dates_like_app():
    p = lambda s: dates.parse(s, MONDAY)  # noqa: E731
    assert p("bis Freitag") == date(2026, 11, 27)
    assert p("nächste Woche Mittwoch") == date(2026, 12, 2)
    assert p("Ende Januar") == date(2027, 1, 31)
    assert p("15. Januar") == date(2027, 1, 15)
    assert p("04.12.") == date(2026, 12, 4)
    assert p("in zwei Wochen") == date(2026, 12, 7)
    assert p("morgen") == date(2026, 11, 24)
    assert p("Ende der Woche") == date(2026, 11, 27)
    assert dates.resolve("nächste Sitzung", MONDAY) == "nächste Sitzung"
    assert dates.resolve("Freitag", MONDAY) == "Fr., 27.11.2026"


def test_vocabulary_like_app():
    fix = lambda t, *terms: v.correct(t, list(terms))  # noqa: E731
    assert v.cologne_phonetic("Müller-Lüdenscheidt") == "65752682"
    assert v.cologne_phonetic("Meier") == v.cologne_phonetic("Mayer")
    assert fix("Frau Schmidt übernimmt das.", "Schmitt") == "Frau Schmitt übernimmt das."
    assert fix("Herr Maier ruft an.", "Meyer") == "Herr Meyer ruft an."
    assert fix("Wir sprechen mit Frau Kowaltschik.", "Kowalczyk") == "Wir sprechen mit Frau Kowalczyk."
    assert fix("Der Antrag nach sgb xi läuft.", "SGB XI") == "Der Antrag nach SGB XI läuft."
    assert fix("Die Pflegekase zahlt.", "Pflegekasse") == "Die Pflegekasse zahlt."
    assert fix("Schmidts Antrag, Schmidt!", "Schmitt") == "Schmitts Antrag, Schmitt!"
    assert fix("Herr Becker kommt.", "Schneider") == "Herr Becker kommt."
    assert fix("Das ist gut.", "Gut") == "Das ist gut."
    assert v.levenshtein("schmidt", "schmitt") == 1


def test_parsing_and_repair():
    raw = '{"zusammenfassung":"Kurz.","themen":[{"titel":"Umzug","punkte":["Vertrag unterschrieben. [1]"]}],' \
          '"beschluesse":["Ab März geteilt. [3]"],"offene_punkte":[],"aufgaben":[{"aufgabe":"Angebote einholen [2]",' \
          '"verantwortlich":"Frau Becker","frist":"Freitag"}],"erledigte_aufgaben":["A1 [2]"],"sensible_daten":["Herr Krüger"]}'
    s = g.parse_summary(raw, 5)
    assert s.topics[0].points == ["Vertrag unterschrieben."] and s.topics[0].refs == [1]
    assert s.decision_refs == [3]
    t = g.parse_tasks(raw, 5)
    assert t[0].text == "Angebote einholen" and t[0].ref == 2 and t[0].owner == "Frau Becker"
    assert g.parse_completed(raw, 5) == [("A1", 2)]
    assert g.parse_sensitive(raw, ["Frau Becker"]) == ["Herr Krüger"]
    cut = raw[:raw.index('"beschluesse"') + 30]       # abgeschnittene Antwort
    assert g.parse_summary(cut, 5).topics[0].title == "Umzug"


class FakeModel:
    context_size = 4096

    def __init__(self):
        self.calls = []

    def count_tokens(self, text):
        return len(text) // 4 + 1

    def complete(self, system, user, max_tokens, temperature, grammar, progress):
        self.calls.append(user)
        if progress:
            progress(0.5)
        if grammar == g.SUMMARY_GRAMMAR:
            return '{"zusammenfassung":"Z.","themen":[],"beschluesse":[],"offene_punkte":[],"aufgaben":[],' \
                   '"erledigte_aufgaben":[],"sensible_daten":[]}'
        if grammar == g.TASKS_GRAMMAR:
            return '{"aufgaben":[{"aufgabe":"Telefonanlage klären","verantwortlich":"Person 1","frist":"Ende Januar"}],' \
                   '"erledigte_aufgaben":[],"sensible_daten":[]}'
        return "- Notiz [0]"


def test_generator_map_reduce_and_followup_tasks():
    segs = [Segment(i % 2, i * 5.0, i * 5.0 + 4, ("Herr Wagner, bitte die Telefonanlage klären. " if i == 3 else "") + "Text " * 120)
            for i in range(60)]
    m = FakeModel()
    res = g.ProtocolGenerator(m).generate("Test", 1795392000000, segs, [Speaker(0, "Anna"), Speaker(1, "Ben")], True,
                                          template="DIENSTBESPRECHUNG")
    assert any("Teil 1 von" in c for c in m.calls), "lange Besprechung wird abschnittsweise verdichtet"
    assert res.tasks and res.tasks[0].due.endswith(".2027")        # Frist umgerechnet
    assert res.tasks[0].owner == "Herr Wagner"                    # Anrede statt "Person 1"


def test_meeting_json_roundtrip():
    m = Meeting(id="x1", title="Ä", created_at=1, segments=[Segment(0, 0.0, 1.5, "Hallo")],
                speakers=[Speaker(0, "Anna")], summary=Summary("ü", [Topic("T", ["p"], [0])]),
                tasks=[TaskItem("a", id="t1")], template="UEBERGABE")
    assert meeting_to_json(meeting_from_json(json.loads(json.dumps(meeting_to_json(m))))) == meeting_to_json(m)


def test_package_roundtrip(tmp_path):
    wav = tmp_path / "a.wav"
    audio.write_wav(wav, np.zeros(16000, dtype=np.float32))
    m = Meeting(id="m1", title="Besprechung", created_at=1, audio_file=str(wav))
    f = package.write(tmp_path / "x.protokoll", package.Package(m, vocabulary=["SGB XI"]), wav)
    assert package.is_package(f) and package.meeting_id(f) == "m1"
    with zipfile.ZipFile(f) as z:
        assert set(z.namelist()) == {"protokoll.json", "audio.wav"}
    back = package.read(f, tmp_path / "out" / "audio.wav")
    assert back.meeting.title == "Besprechung" and back.vocabulary == ["SGB XI"]
    assert len(audio.read_mono16k(back.meeting.audio_file)) == 16000


def test_exports(tmp_path):
    m = Meeting(id="m1", title="Dienstbesprechung Dezember", created_at=1795392000000, duration_sec=1800,
                segments=[Segment(0, 0, 3, "Hallo"), Segment(1, 3, 6, "Guten Tag")],
                speakers=[Speaker(0, "Anna"), Speaker(1, "Ben")],
                summary=Summary("Es ging um den Dienstplan.", [Topic("Dienstplan", ["Zwei Nachtdienste fehlen."], [0])],
                                ["Frau B. übernimmt."], ["Urlaub offen"]),
                tasks=[TaskItem("Dienstplan anpassen", "Anna", "Fr., 27.11.2026", done=True)],
                template="VEREINSSITZUNG", chair="Anna", minute_taker="Ben")
    files = export.export_all(m, tmp_path)
    assert files["docx"].stat().st_size > 5000 and files["pdf"].stat().st_size > 5000
    md = files["md"].read_text("utf-8")
    assert "Sitzungsprotokoll" in md and "Sitzungsleitung" in md and "Dienstplan anpassen" in md
