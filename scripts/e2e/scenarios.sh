#!/usr/bin/env bash
# Szenario-Lauf: Testbesprechungen nacheinander importieren, verarbeiten und prüfen.
# Aufruf: scenarios.sh <modellpaket> <szenario> [<szenario> ...]
source scripts/e2e/common.sh
SET=$1; shift
install_app; push_models
adb logcat -c
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
for name in "$@"; do
  meta="$SC/$name.json"
  tpl=$(python3 -c "import json;print(json.load(open('$meta'))['template'])")
  agenda=$(python3 -c "import json;print('|'.join(json.load(open('$meta')).get('agenda',[])))")
  info "Szenario $name (Vorlage $tpl, Modelle $SET)"
  r=$(import_and_wait "$SC/$name.wav" "$SET" "$tpl" "$agenda" 2400)
  case "$r" in
    "") fail "[$name] Import hat keine Besprechung angelegt"; continue;;
    CRASH:*) fail "[$name] App abgestürzt während der Verarbeitung"; crash_report
             adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; continue;;
  esac
  id=${r%%:*}; secs=${r##*:}
  meeting_json "$id" > "$OUT/$name.meeting.json"
  python3 scripts/e2e/check.py "$OUT/$name.meeting.json" "$meta" "$secs" | tee -a "$RESULTS"
  shot "$name"
done
crash_report
summary
