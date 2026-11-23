#!/usr/bin/env bash
# Gemeinsame Hilfsfunktionen für die Emulator-Tests.
set -uo pipefail
PKG=de.protokollki
OUT=${OUT:-e2e-out}
M=${MODELS:-e2e-models}
SC=scripts/e2e/scenarios/out
mkdir -p "$OUT"
RESULTS="$OUT/results.txt"; : > "$RESULTS"
pass() { echo "PASS  $*" | tee -a "$RESULTS"; }
fail() { echo "FAIL  $*" | tee -a "$RESULTS"; }
info() { echo "INFO  $*" | tee -a "$RESULTS"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }

push_app() { # $1 lokale Datei, $2 Ziel relativ zum App-Ordner
  adb push "$1" /data/local/tmp/pk.tmp > /dev/null
  adb shell "cat /data/local/tmp/pk.tmp | run-as $PKG sh -c 'cat > $2'"
  adb shell rm /data/local/tmp/pk.tmp
}
app_alive() { [ -n "$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')" ]; }

install_app() {
  adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null && pass "App installiert"
  adb shell pm grant $PKG android.permission.RECORD_AUDIO
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb shell "run-as $PKG mkdir -p files/models files/in"
}
push_models() {
  for f in "$M"/*; do push_app "$f" "files/models/$(basename "$f")"; done
  info "Modelle aufgespielt: $(ls "$M" | tr '\n' ' ')"
}
newest_meeting() { adb shell "run-as $PKG ls -t files/meetings" 2>/dev/null | tr -d '\r' | head -1; }
meeting_json() { adb shell "run-as $PKG cat files/meetings/$1/meeting.json" 2>/dev/null; }
status_of() { meeting_json "$1" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('status',''))" 2>/dev/null; }

# Datei importieren (wie "Teilen" aus einer anderen App) und Ergebnis abwarten.
# $1 Datei, $2 Modellpaket, $3 Vorlage, $4 Tagesordnung (mit | getrennt), $5 Timeout in s
import_and_wait() {
  local file=$1 set=$2 tpl=${3:-ALLGEMEIN} agenda=${4:-} timeout=${5:-1500}
  # Fachbegriffe aus der Szenario-Beschreibung neben der Datei (wie in den Einstellungen gepflegt)
  local vocab=""; [ -f "${file%.*}.json" ] && vocab=$(python3 -c "import json,sys;print('|'.join(json.load(open(sys.argv[1])).get('vocabulary',[])))" "${file%.*}.json")
  local name; name=$(basename "$file")
  push_app "$file" "files/in/$name"
  local before; before=$(newest_meeting)
  adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT \
    --es path "/data/data/$PKG/files/in/$name" --es model_set "$set" --es template "$tpl" \
    ${agenda:+--es agenda "'$agenda'"} ${vocab:+--es vocabulary "'$vocab'"} > /dev/null
  local id="" t0=$SECONDS dead=0 st=""
  for _ in $(seq 1 30); do sleep 2; id=$(newest_meeting); [ -n "$id" ] && [ "$id" != "$before" ] && break; done
  if [ -z "$id" ] || [ "$id" = "$before" ]; then echo ""; return 1; fi
  while [ $((SECONDS - t0)) -lt "$timeout" ]; do
    st=$(status_of "$id")
    [ "$st" = "DONE" ] || [ "$st" = "ERROR" ] && break
    if app_alive; then dead=0; else dead=$((dead+1)); [ $dead -ge 3 ] && { echo "CRASH:$id"; return 2; }; fi
    sleep 8
  done
  echo "$id:$((SECONDS - t0))"
}

crash_report() {
  adb logcat -d > "$OUT/logcat.txt"
  local n; n=$(grep -cE "FATAL EXCEPTION|JNI DETECTED|Fatal signal" "$OUT/logcat.txt")
  if [ "$n" = "0" ]; then pass "Keine Abstürze im Systemprotokoll"; else
    fail "Abstürze im Systemprotokoll: $n"
    grep -E "JNI DETECTED|Abort message|FATAL EXCEPTION|Caused by|Fatal signal|at de\.protokollki|at com\.k2fsa" "$OUT/logcat.txt" | awk '!seen[$0]++' | head -40
  fi
}
summary() {
  echo "================ ERGEBNIS ================"
  cat "$RESULTS"
  local f; f=$(grep -c '^FAIL' "$RESULTS")
  echo "Bestanden: $(grep -c '^PASS' "$RESULTS")  Fehlgeschlagen: $f"
  [ "$f" = "0" ]
}
