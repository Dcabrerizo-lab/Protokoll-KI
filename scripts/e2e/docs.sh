#!/usr/bin/env bash
# Import alter Protokolle (PDF, Word, Text) inkl. Verarbeitung durch das Sprachmodell.
source scripts/e2e/common.sh
SET=$1
python3 scripts/e2e/make_docs.py "$OUT/docs"
install_app; push_models
adb logcat -c
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
for f in protokoll.pdf protokoll.docx protokoll.txt; do
  r=$(import_and_wait "$OUT/docs/$f" "$SET" ALLGEMEIN "" 1500)
  case "$r" in
    "") fail "[$f] Import hat kein Protokoll angelegt";;
    CRASH:*) fail "[$f] App abgestürzt"; adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6;;
    *) id=${r%%:*}; meeting_json "$id" > "$OUT/$f.meeting.json"
       python3 - "$OUT/$f.meeting.json" "$f" "${r##*:}" <<'PY' | tee -a "$RESULTS"
import json,sys
m=json.load(open(sys.argv[1])); f=sys.argv[2]
def ok(c,msg): print(("PASS" if c else "FAIL")+f"  [{f}] {msg}")
ok(m["status"]=="DONE", f"verarbeitet in {sys.argv[3]} s (Fehler: {m.get('error')})")
ok(m.get("importedFrom")==f, f"als Import markiert: {m.get('importedFrom')}")
ok("2025" in str(__import__('datetime').datetime.fromtimestamp(m["createdAt"]/1000).year), "Sitzungsdatum aus dem Dokument übernommen")
text=" ".join(s["t"] for s in m["segments"])
ok("Möbel" in text and "Tour Nord" in text, "Text inkl. Umlauten vollständig gelesen")
ok(len(m["segments"])>=5, f"Absätze: {len(m['segments'])}")
ok(len(m.get("tasks",[]))>=2, f"Aufgaben übernommen: {[t['text'] for t in m.get('tasks',[])]}")
ok(bool(m["summary"].get("overview")), "Zusammenfassung erstellt")
PY
       shot "doc-$f";;
  esac
done
crash_report
summary
