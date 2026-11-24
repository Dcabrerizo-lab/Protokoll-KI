#!/usr/bin/env python3
"""Wählt echte Handys aus dem Firebase-Test-Lab-Katalog.

Eingabe: JSON von `gcloud firebase test android models list --format=json`, Auswahl
(standard | s23 | alle). Ausgabe: JSON-Liste für die GitHub-Matrix:
[{"name": "Galaxy S23+", "model": "dm2q", "version": "34", "set": "premium"}, ...]

Jeder Eintrag ist ein Gerätetest. Im kostenlosen Spark-Tarif sind 5 pro Tag frei, daher
nimmt "standard" nur 3 Handys (2 bleiben für Wiederholungen übrig).
"""
import json
import re
import sys

catalog = json.load(open(sys.argv[1]))
choice = (sys.argv[2] if len(sys.argv) > 2 else "standard").strip().lower()

phys = [m for m in catalog if m.get("form") == "PHYSICAL" and m.get("supportedVersionIds")
        and not any(str(t).startswith("deprecated") for t in m.get("tags", []))]
by_id = {m["id"]: m for m in phys}

# Gruppen in Wunschreihenfolge: (Anzeige, Modell-IDs, Namensmuster als Ersatz, Modellpaket)
GROUPS = {
    "s23":      ("Samsung Galaxy S23-Familie", ["dm2q", "dm1q", "dm3q"], r"Galaxy S23", "premium"),
    "s23b":     ("Weiteres Galaxy S23",        ["dm1q", "dm3q", "dm2q"], r"Galaxy S23", "premium"),
    "pixel":    ("Google Pixel 8/9",           ["shiba", "husky", "akita", "tokay", "caiman", "komodo"], r"Pixel [89]", "premium"),
    "mittel":   ("Samsung Galaxy A (Mittelklasse)", ["a54x", "a55x", "a35x", "a53x", "a34x", "a15", "a25x"], r"Galaxy A[1-5]\d", "recommended"),
    "s24":      ("Samsung Galaxy S24/S25",     ["e1q", "e2q", "e3q", "e2s", "pa1q", "pa2q", "pa3q"], r"Galaxy S2[45]", "premium"),
}
PLANS = {
    "s23": ["s23"],
    "standard": ["s23", "pixel", "mittel"],
    "alle": ["s23", "s23b", "pixel", "mittel", "s24"],
}
plan = PLANS.get(choice, PLANS["standard"])

def newest(m):
    return max(m["supportedVersionIds"], key=lambda v: int(re.sub(r"\D", "", v) or 0))

used, out = set(), []
for g in plan:
    label, ids, pattern, model_set = GROUPS[g]
    cands = [by_id[i] for i in ids if i in by_id]
    cands += sorted((m for m in phys if re.search(pattern, m.get("name", ""))), key=lambda m: m["id"])
    pick = next((m for m in cands if m["id"] not in used), None)
    if not pick:
        print(f"Kein Gerät für „{label}“ im Katalog gefunden", file=sys.stderr)
        continue
    used.add(pick["id"])
    out.append({"name": f'{pick.get("brand", "")} {pick.get("name", pick["id"])}'.strip(),
                "model": pick["id"], "version": newest(pick), "set": model_set})

for d in out:
    print(f'Gewählt: {d["name"]} ({d["model"]}, Android-API {d["version"]}, Paket {d["set"]})', file=sys.stderr)
print(json.dumps(out))
