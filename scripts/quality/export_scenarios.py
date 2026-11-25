"""Exportiert die Szenarien (scripts/e2e/scenarios/scenarios.py) samt Goldstandard als JSON für den
Host-Bewertungslauf (scripts/quality/eval). Aufruf: python3 export_scenarios.py <ausgabe.json>

Zusätzlich zu den Basisszenarien entstehen harte Varianten:
  lang            Basisszenarien hintereinander (wie im Emulator-Test)
  asr_<name>      Transkript wie aus der Spracherkennung: Namen phonetisch falsch geschrieben, Kommas fehlen,
                  kurze Zusagen ("Ja, mache ich") dem vorherigen Sprecher zugeordnet (typischer Diarisierungsfehler).
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "e2e", "scenarios"))
sys.path.insert(0, HERE)
from scenarios import LONG_PARTS, SCENARIOS, STRESS, VOCABULARY  # noqa: E402
from gold import GOLD, HARD, NORMAL, OPEN_TASKS  # noqa: E402
from holdout import HOLDOUT  # noqa: E402

# Teilnehmerliste, wie sie Nutzer vorab eingeben würden (nur Namen, die im Gespräch als Teilnehmende vorkommen)
PARTICIPANTS = {
    "zwei_personen": ["Frau Kaiser"], "drei_personen": ["Frau Becker", "Herr Wagner"],
    "folgesitzung": ["Frau Becker", "Herr Wagner"], "vereinssitzung": ["Herr Schröder"],
    "fallbesprechung": ["Frau Schmidt", "Herr Yilmaz"], "uebergabe": [],
    "stress_durcheinander": ["Frau Becker", "Herr Wagner", "Frau Kaiser"],
    "stress_grossteam": ["Frau Lange", "Herr Brandt"], "stress_fachvokabular": ["Frau Wolf", "Herr Neumann"],
    "stress_akzente": ["Herr Wagner"],
}

MISHEAR = {"Krüger": "Krieger", "Yilmaz": "Yilmas", "Schröder": "Schröter", "Becker": "Bäcker",
           "Wagner": "Wagener", "Schmidt": "Schmitt", "Kaiser": "Keiser", "Lindenstraße": "Linden Straße",
           "Holzmann": "Holzmannn", "Ordnungsamt": "Ordnungs Amt", "Pflegekasse": "Pflege Kasse"}
CONFIRM = re.compile(r"^(Ja|Mache ich|In Ordnung|Verstanden)\b")


def build(name, lines):
    letters = []
    for ln in lines:
        if ln[0] not in letters:
            letters.append(ln[0])
    return [{"speaker": letters.index(ln[0]), "voice": ln[0], "text": ln[1]} for ln in lines], letters


def asr(segs):
    out = []
    for i, s in enumerate(segs):
        t = s["text"].replace(",", "")
        for a, b in MISHEAR.items():
            t = t.replace(a, b)
        sp = s["speaker"]
        if i > 0 and len(s["text"]) < 70 and CONFIRM.match(s["text"]):
            sp = out[-1]["speaker"]
        out.append({**s, "speaker": sp, "text": t})
    return out


def entry(name, sc, segs, letters, gold, group, vocab, participants):
    return {
        "name": name, "group": group, "template": sc["template"], "agenda": sc.get("agenda", []),
        "segments": segs, "voices": letters, "vocabulary": vocab, "participants": participants,
        "openTasks": [{"text": t, "owner": o} for t, o in OPEN_TASKS] if sc.get("after") else [],
        "gold": gold,
    }


def main(path):
    out = []
    allsc = {**SCENARIOS, **STRESS}
    for name in NORMAL + [h for h in HARD if h.startswith("stress_")]:
        sc = allsc[name]
        segs, letters = build(name, sc["lines"])
        out.append(entry(name, sc, segs, letters, GOLD[name], "normal" if name in NORMAL else "hart",
                         VOCABULARY.get(name, []), PARTICIPANTS.get(name, [])))
    # lang: Teile hintereinander, Gold = Vereinigung (gleiche Aufgaben aus Wiederholungen zählen einmal)
    lines, gold, seen = [], dict(tasks=[], decisions=[], names=[], sensitive=[], not_sensitive=[]), set()
    for p in LONG_PARTS:
        lines += SCENARIOS[p]["lines"]
        if p in seen:
            continue
        seen.add(p)
        g = GOLD[p]
        gold["tasks"] += g.get("tasks", [])
        gold["decisions"] += [{"match": d["match"]} for d in g.get("decisions", [])]
        gold["names"] += g.get("names", [])
        gold["sensitive"] += g.get("sensitive", [])
    gold["names"] = sorted(set(gold["names"]))
    segs, letters = build("lang", lines)
    out.append(entry("lang", {"template": "ALLGEMEIN"}, segs, letters, gold, "hart", VOCABULARY["lang"],
                     sorted({n for p in LONG_PARTS for n in PARTICIPANTS[p]})))
    for name in [h for h in HARD if h.startswith("asr_")]:
        base = name[4:]
        sc = SCENARIOS[base]
        segs, letters = build(base, sc["lines"])
        out.append(entry(name, sc, asr(segs), letters, GOLD[base], "hart", VOCABULARY.get(base, []),
                         PARTICIPANTS.get(base, [])))
    for name, sc in HOLDOUT.items():
        segs, letters = build(name, sc["lines"])
        out.append(entry(name, sc, segs, letters, sc["gold"], "neu", sc["vocabulary"], sc["participants"]))
    json.dump(out, open(path, "w"), ensure_ascii=False, indent=1)
    print(f"{len(out)} Szenarien -> {path}")


if __name__ == "__main__":
    main(sys.argv[1])
