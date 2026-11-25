"""Bewertet Host-Läufe (PKRESULT-Zeilen aus scripts/quality/eval) gegen den Goldstandard (gold.py).
Aufruf: python3 score.py <ergebnis.jsonl|job-log> [...] [--details]
Ausgabe: Trefferquoten in Prozent je Stufe, Variante (ohne/mit Teilnehmerliste) und Gruppe (normal/hart)."""
import json, os, re, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gold import GOLD  # noqa: E402
from holdout import HOLDOUT  # noqa: E402
import export_scenarios  # noqa: E402,F401  (nur für gleiche Pfade)

NUMWORDS = {"1": ["1", "eine", "einer", "eins"], "2": ["2", "zwei"], "3": ["3", "drei"], "4": ["4", "vier"],
            "5": ["5", "fünf"], "6": ["6", "sechs"], "7": ["7", "sieben"]}
METRICS = [("tasks", "Aufgaben gefunden"), ("precision", "Aufgaben ohne Fehlgriffe"), ("owner", "Verantwortliche"),
           ("due", "Fristen"), ("decisions", "Beschlüsse inkl. Abstimmung"), ("names", "Namen richtig geschrieben"),
           ("sensitive", "Sensible Daten erkannt"), ("sens_fp", "Sensibel-Meldungen ohne Mitarbeitende"),
           ("completed", "Erledigt-Abgleich"), ("structure", "Gliederung (TOP/Fall)")]


def gold_for(name):
    if name in GOLD:
        return GOLD[name]
    if name in HOLDOUT:
        return HOLDOUT[name]["gold"]
    if name.startswith("asr_"):
        return GOLD[name[4:]]
    if name == "lang":
        from export_scenarios import LONG_PARTS
        g = dict(tasks=[], decisions=[], names=set(), sensitive=[])
        for p in dict.fromkeys(LONG_PARTS):
            gp = GOLD[p]
            g["tasks"] += gp.get("tasks", [])
            g["decisions"] += [{"match": d["match"]} for d in gp.get("decisions", [])]
            g["names"] |= set(gp.get("names", []))
            g["sensitive"] += gp.get("sensitive", [])
        g["names"] = sorted(g["names"])
        # Wer in einem Teil zum Team gehört (z. B. Becker), ist im Gesamttext nicht eindeutig Klient: nicht werten
        staff = {n for p in LONG_PARTS for n in GOLD[p].get("not_sensitive", [])}
        g["sensitive"] = [alts for alts in g["sensitive"] if not any(a in staff for a in alts)]
        return g
    return {}


def matches(text, alts):
    t = text.lower()
    return any(all(w in t for w in alt) for alt in alts)


def owner_ok(owner, gold_owner, voices):
    o = owner.lower()
    for g in gold_owner:
        if g.startswith("@"):
            if g[1:] in voices and o.strip() == f"person {voices.index(g[1:]) + 1}":
                return True
        elif g in o:
            return True
    return False


def score(r):
    g = gold_for(r["scenario"])
    voices = r.get("voices", [])
    s = defaultdict(lambda: [0, 0])  # metric -> [treffer, gesamt]
    notes = []
    if r.get("error"):
        notes.append("FEHLER " + r["error"])
    tasks = r.get("tasks", [])
    used = set()
    for gt in g.get("tasks", []):
        cands = [i for i, t in enumerate(tasks) if matches(t["text"], gt["match"])]
        if gt.get("optional"):
            used |= set(cands)
            continue
        s["tasks"][1] += 1
        if not cands:
            notes.append(f"fehlt: {gt['match'][0]}")
            if gt.get("owner"):
                s["owner"][1] += 1
            if gt.get("due"):
                s["due"][1] += 1
            continue
        s["tasks"][0] += 1
        used |= set(cands)
        best = next((i for i in cands if gt.get("owner") and owner_ok(tasks[i]["owner"], gt["owner"], voices)), cands[0])
        t = tasks[best]
        if gt.get("owner"):
            ok = owner_ok(t["owner"], gt["owner"], voices)
            s["owner"][0] += ok; s["owner"][1] += 1
            if not ok:
                notes.append(f"Verantwortlich falsch: {t['text']!r} -> {t['owner']!r} (soll {gt['owner']})")
        if gt.get("due"):
            ok = any(d in t["due"].lower() for d in gt["due"])
            s["due"][0] += ok; s["due"][1] += 1
            if not ok:
                notes.append(f"Frist falsch: {t['text']!r} -> {t['due']!r} (soll {gt['due'][0]})")
    for i, t in enumerate(tasks):
        s["precision"][1] += 1
        if i in used:
            s["precision"][0] += 1
        else:
            notes.append(f"zusätzliche Aufgabe: {t['text']!r} ({t['owner']})")
    decs = r.get("decisions", [])
    for gd in g.get("decisions", []):
        s["decisions"][1] += 1
        hit = [d for d in decs if matches(d, gd["match"])]
        ok = bool(hit)
        if ok and gd.get("vote"):
            res, nums = gd["vote"]
            other = "abgelehnt" if res == "angenommen" else "angenommen"
            ok = any(res in d.lower() and other not in d.lower() and
                     all(any(re.search(r"\b" + w + r"\b", d.lower()) for w in NUMWORDS[n]) for n in nums) for d in hit)
        s["decisions"][0] += ok
        if not ok:
            notes.append(f"Beschluss fehlt/unvollständig: {gd['match'][0]} {gd.get('vote', '')} in {decs}")
    text = " ".join([r.get("overview", "")] + [t["title"] + " " + " ".join(t["points"]) for t in r.get("topics", [])] +
                    decs + r.get("open_points", []) + [t["text"] + " " + t["owner"] for t in tasks])
    for n in g.get("names", []):
        s["names"][1] += 1
        ok = n in text
        s["names"][0] += ok
        if not ok:
            notes.append(f"Name fehlt/falsch: {n}")
    sens = " ".join(r.get("sensitive", [])).lower()
    for alts in g.get("sensitive", []):
        s["sensitive"][1] += 1
        ok = any(a in sens for a in alts)
        s["sensitive"][0] += ok
        if not ok:
            notes.append(f"sensibel nicht erkannt: {alts[0]}")
    for x in r.get("sensitive", []):
        s["sens_fp"][1] += 1
        bad = any(ns in x.lower() for ns in g.get("not_sensitive", []))
        s["sens_fp"][0] += not bad
        if bad:
            notes.append(f"Mitarbeitende als sensibel: {x}")
    comp = " ".join(r.get("completed", [])).lower()
    for c in g.get("completed", []):
        s["completed"][1] += 1
        ok = c in comp
        s["completed"][0] += ok
        if not ok:
            notes.append(f"erledigt nicht erkannt: {c}")
    for c in g.get("not_completed", []):
        if c in comp:
            s["completed"][1] += 1
            notes.append(f"fälschlich erledigt: {c}")
    titles = [t["title"] for t in r.get("topics", [])]
    for st in g.get("structure", []):
        s["structure"][1] += 1
        ok = any(t.startswith("TOP") for t in titles) if st == "agenda" else any(t.startswith(st) for t in titles)
        s["structure"][0] += ok
        if not ok:
            notes.append(f"Gliederung {st} fehlt: {titles}")
    return s, notes


def load(paths):
    rows = []
    for p in paths:
        for line in open(p, encoding="utf-8", errors="replace"):
            i = line.find("PKRESULT ")
            j = line.find("{") if i < 0 else line.find("{", i)
            if (i >= 0 or line.startswith("{")) and j >= 0:
                try:
                    rows.append(json.loads(line[j:]))
                except json.JSONDecodeError:
                    pass
    # spätere Zeilen ersetzen frühere (gleiche Stufe/Variante/Szenario)
    return list({(r["stage"], r["variant"], r["scenario"]): r for r in rows}.values())


def pct(a, b):
    return "–" if b == 0 else f"{100 * a / b:.0f} %"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    details = "--details" in sys.argv
    rows = load(args)
    agg = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    per = []
    for r in rows:
        s, notes = score(r)
        key = (r["stage"], r["variant"], r["group"])
        for m, (a, b) in s.items():
            agg[key][m][0] += a; agg[key][m][1] += b
        agg[key]["_ms"][0] += r.get("ms", 0); agg[key]["_ms"][1] += 1
        tot = sum(a for a, _ in s.values()), sum(b for _, b in s.values())
        per.append((r["stage"], r["variant"], r["scenario"], tot, r.get("ms", 0), r.get("llm_calls", 0), notes))
    stages = sorted({k[0] for k in agg}, key=lambda x: ["kompakt", "standard", "premium", "ultra"].index(x.split("-")[0]) if x.split("-")[0] in ["kompakt", "standard", "premium", "ultra"] else 9)
    for v in sorted({k[1] for k in agg}, reverse=True):
        for grp in ["normal", "hart", "neu"]:
            keys = [(st, v, grp) for st in stages if (st, v, grp) in agg]
            if not keys:
                continue
            print(f"## Trefferquoten · {grp} · Teilnehmerliste {v}\n")
            print("| Kennzahl | " + " | ".join(k[0] for k in keys) + " |")
            print("|---|" + "---|" * len(keys))
            for m, label in METRICS:
                print(f"| {label} | " + " | ".join(f"{pct(*agg[k][m])} ({agg[k][m][0]}/{agg[k][m][1]})" for k in keys) + " |")
            print("| **Gesamt** | " + " | ".join(
                pct(sum(x[0] for mm, x in agg[k].items() if mm != "_ms"), sum(x[1] for mm, x in agg[k].items() if mm != "_ms"))
                for k in keys) + " |")
            print("| Ø Sekunden je Protokoll | " + " | ".join(f"{agg[k]['_ms'][0] / max(1, agg[k]['_ms'][1]) / 1000:.0f}" for k in keys) + " |\n")
    print("\n## Je Szenario\n")
    print("| Stufe | Variante | Szenario | Punkte | s | LLM-Runden |")
    print("|---|---|---|---|---|---|")
    for st, v, sc, (a, b), ms, calls, notes in sorted(per):
        print(f"| {st} | {v} | {sc} | {a}/{b} | {ms / 1000:.0f} | {calls} |")
    if details:
        print("\n## Abweichungen\n")
        for st, v, sc, _, _, _, notes in sorted(per):
            for n in notes:
                print(f"- {st} · {v} · {sc}: {n}")


if __name__ == "__main__":
    main()
