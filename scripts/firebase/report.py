#!/usr/bin/env python3
"""Baut aus den Firebase-Ergebnissen (logcat + test_result_*.xml) einen kurzen deutschen Bericht.

Aufruf: report.py <ergebnisordner> <gerätename> <konsolen-link> <gcloud-exitcode>
Schreibt Markdown nach stdout. Exit 0 = alles bestanden.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

root, device, link, code = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4] or 1)

def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""

logcat = "\n".join(read(p) for p in glob.glob(os.path.join(root, "**", "logcat"), recursive=True))
facts = {}
for m in re.finditer(r"PKREPORT ([\w.]+)=(.*)", logcat):
    facts[m.group(1)] = m.group(2).strip()

tests = []
for p in glob.glob(os.path.join(root, "**", "test_result_*.xml"), recursive=True):
    try:
        for tc in ET.parse(p).getroot().iter("testcase"):
            f = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
            msg = (f.get("message") or f.text or "").strip().splitlines()[0][:300] if f is not None else ""
            skipped = tc.find("skipped") is not None
            tests.append((tc.get("name"), "übersprungen" if skipped else ("FEHLER" if f is not None else "OK"), msg))
    except ET.ParseError:
        pass

crash_lines = [l for l in logcat.splitlines()
               if re.search(r"FATAL EXCEPTION|Fatal signal|Abort message|lowmemorykiller.*protokollki|"
                            r"Process de\.protokollki .* has died|OutOfMemoryError|JNI DETECTED", l)]

NAMES = {
    "step1_downloadModels": "1. Modelle laden",
    "step2_meetingWav_toFinishedProtocol_andExport": "2. Besprechung 69 s → fertiges Protokoll + PDF/Word",
    "step3_longMeeting_toFinishedProtocol": "3. Lange Besprechung 4,7 min → fertiges Protokoll",
}
ok = code == 0 and tests and all(s != "FEHLER" for _, s, _ in tests) and not crash_lines

lines = [f"### {'✅' if ok else '❌'} {device}", ""]
g = facts.get
if g("geraet"):
    lines.append(f"- Gerät: {g('geraet')}, Android {g('android')}, {g('ram_gesamt_gb')} GB RAM")
    lines.append(f"- Modellpaket: **{g('paket')}** (App-Empfehlung für dieses Handy: {g('empfohlen')})")
if g("download.sekunden"):
    lines.append(f"- Download: {g('download')} in {int(g('download.sekunden')) // 60} min")
def power(key):
    """Akku vorher→nachher, Akkutemperatur max., Thermal-Status max. (falls gemessen)."""
    parts = []
    a, b = g(key + ".akku_vorher"), g(key + ".akku_nachher")
    if a not in (None, "", "?") and b not in (None, "", "?"):
        try:
            diff = int(a) - int(b)
            parts.append(f"Akku {a} % → {b} % ({'-' if diff > 0 else '±' if diff == 0 else '+'}{abs(diff)} %)")
        except ValueError:
            parts.append(f"Akku {a} % → {b} %")
    if g(key + ".laedt") == "true":
        parts.append("am Ladegerät")
    t = g(key + ".akku_temp_max")
    if t not in (None, "", "?"):
        warm = ""
        try:
            warm = " 🔥" if float(t) >= 42 else ""
        except ValueError:
            pass
        parts.append(f"Akku max. {t} °C{warm}")
    th = g(key + ".thermal_max")
    if th not in (None, "", "?"):
        parts.append(f"Wärmestufe max. {th}" + (" ⚠️" if th[:1].isdigit() and int(th[:1]) >= 3 else ""))
    return (" · " + ", ".join(parts)) if parts else ""

for key, label in (("kurz", "Besprechung 69 s"), ("lang", "Lange Besprechung 4,7 min")):
    if g(f"{key}.sekunden"):
        lines.append(
            f"- {label}: {g(key + '.status')} nach {g(key + '.sekunden')} s · "
            f"{g(key + '.sprecher')} Sprecher, {g(key + '.aufgaben')} Aufgaben · "
            f"App-Speicher max. {g(key + '.max_app_speicher_mb')} MB, "
            f"freier RAM min. {g(key + '.min_frei_ram_mb')} MB"
            + (" · ⚠️ Speicher knapp" if g(key + ".speicher_knapp") == "true" else "")
            + power(key)
            + (f" · Fehlermeldung: {g(key + '.fehler')}" if g(key + ".fehler") not in (None, "", "null") else ""))
# Zeitmessung je Schritt (App) und je Sprachmodell-Aufruf (nativ), in Reihenfolge des Protokolls
timing = [m.group(1) + ": " + m.group(2).strip()
          for m in re.finditer(r"PKREPORT ((?:schritt|llm)\.\d+|kerne|cpu)=(.*)", logcat)]
if timing:
    lines += ["", "<details><summary>Zeiten je Schritt</summary>", ""] + [f"- {t}" for t in timing[:80]] + ["", "</details>"]
lines.append("")
if tests:
    lines += ["| Schritt | Ergebnis | Hinweis |", "|---|---|---|"]
    for name, status, msg in tests:
        lines.append(f"| {NAMES.get(name, name)} | {status} | {msg.replace('|', '/')} |")
elif "storage.objects.create" in read("gcloud.txt") or "PERMISSION_DENIED" in read("gcloud.txt"):
    lines.append("Kein Test gestartet: dem Firebase-Schlüssel fehlt die Rolle „Bearbeiter“ (Editor) im Projekt. "
                 "Es wurde kein Tagestest verbraucht.")
else:
    lines.append(f"Keine Testergebnisse erhalten (gcloud-Code {code}). Häufige Ursache: Tageslimit "
                 "von 5 Gerätetests erreicht oder Gerät gerade belegt.")
if crash_lines:
    lines += ["", "**Absturz im Systemprotokoll:**", "```"] + crash_lines[:15] + ["```"]
if link:
    lines += ["", f"[Video und Details in Firebase]({link})"]
print("\n".join(lines) + "\n")
sys.exit(0 if ok else 1)
