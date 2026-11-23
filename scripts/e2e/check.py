"""Prüft ein Verarbeitungsergebnis (meeting.json) gegen die Erwartungen eines Szenarios.
Aufruf: python check.py <meeting.json> <szenario.json> <sekunden> [<alle_meetings_dir>]
Gibt PASS/FAIL-Zeilen aus."""
import json, re, sys

m = json.load(open(sys.argv[1])); sc = json.load(open(sys.argv[2])); secs = sys.argv[3]
exp = sc["expect"]; name = sc["scenario"]
out = []
def ok(c, msg): out.append(("PASS" if c else "FAIL") + f"  [{name}] {msg}")

if exp.get("error"):
    ok(m["status"] == "ERROR" and bool(m.get("error")), f"sauberer Fehler statt Absturz: {m.get('error')!r}")
else:
    ok(m["status"] == "DONE" and not m.get("error"), f"Status DONE in {secs} s (Fehler: {m.get('error')})")
    segs = m.get("segments", []); n = len(segs)
    spk = {s["s"] for s in segs}
    lo, hi = exp["speakers"]
    ok(lo <= len(spk) <= hi, f"Sprecher erkannt: {len(spk)} (erwartet {lo}–{hi})")
    text = " ".join(s["t"] for s in segs).lower()
    for kw in exp.get("keywords", []):
        stem = kw.lower()[: max(4, len(kw) - 2)]
        ok(stem in text, f"Transkript enthält „{kw}“")
    starts = [s["a"] for s in segs]
    ok(starts == sorted(starts), "Segmente zeitlich geordnet")
    dup = sum(1 for a, b in zip(segs, segs[1:]) if a["t"].strip() and a["t"].strip() == b["t"].strip())
    ok(dup == 0, f"keine doppelten Folgesegmente ({dup})")
    tasks = m.get("tasks", [])
    ok(len(tasks) >= exp.get("min_tasks", 0), f"Aufgaben: {len(tasks)} (mind. {exp.get('min_tasks', 0)})")
    owners = " ".join(t.get("owner", "") for t in tasks).lower()
    for o in exp.get("task_owners", []):
        ok(o.lower()[:5] in owners, f"Aufgabe für {o} zugeordnet")
    refs_ok = all(-1 <= t.get("ref", -1) < n for t in tasks)
    sm = m.get("summary", {})
    for tp in sm.get("topics", []):
        refs_ok &= all(-1 <= r < n for r in tp.get("refs", []))
    refs_ok &= all(-1 <= r < n for r in sm.get("decisionRefs", []) + sm.get("openPointRefs", []))
    ok(refs_ok, "alle Quellenangaben zeigen auf gültige Stellen")
    with_ref = sum(1 for t in tasks if t.get("ref", -1) >= 0) + sum(1 for tp in sm.get("topics", []) for r in tp.get("refs", []) if r >= 0)
    out.append(f"INFO  [{name}] Quellen gesetzt: {with_ref}")
    if sc["template"] != "WORT":
        ok(bool(sm.get("overview", "").strip()), "Zusammenfassung vorhanden")
        ok(len(sm.get("topics", [])) >= 1, f"Themen: {len(sm.get('topics', []))}")
    dec = " ".join(sm.get("decisions", [])).lower()
    if exp.get("decisions_any"):
        ok(any(d.lower() in dec for d in exp["decisions_any"]), f"Beschluss erkannt ({exp['decisions_any']})")
    if exp.get("vote_format"):
        ok(bool(re.search(r"angenommen|abgelehnt", dec)), "Abstimmungsergebnis im Beschluss")
    if exp.get("agenda_topics"):
        ok(any(t["title"].startswith("TOP") for t in sm.get("topics", [])), "Themen nach Tagesordnung (TOP …)")
    if exp.get("topic_prefix"):
        ok(any(t["title"].startswith(exp["topic_prefix"]) for t in sm.get("topics", [])), f"Vorlagen-Gliederung „{exp['topic_prefix']}: …“")
    if exp.get("sensitive_any"):
        sens = " ".join(m.get("sensitiveTerms", [])).lower()
        ok(any(s.lower() in sens for s in exp["sensitive_any"]), f"sensible Daten erkannt: {m.get('sensitiveTerms')}")
    if exp.get("completed_tasks_min"):
        done = [f for f in m.get("followUps", []) if f.get("done")]
        ok(len(done) >= exp["completed_tasks_min"], f"erledigte Aufgaben aus Vorsitzung erkannt: {len(done)}")
    ok(m.get("template") == sc["template"], f"Vorlage gespeichert: {m.get('template')}")
    if sc.get("agenda"):
        ok(m.get("agenda") == sc["agenda"], "Tagesordnung gespeichert")
print("\n".join(out))
