#!/usr/bin/env bash
# Ende-zu-Ende-Test im Emulator: Modelle (Kompakt) aufspielen, Testbesprechung importieren,
# Verarbeitung abwarten, Screenshots und Ergebnis sichern.
set -uo pipefail
PKG=de.protokollki
OUT=e2e-out
M=e2e-models
mkdir -p "$OUT"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "Screenshot $1"; }
push_app() { # $1 lokale Datei, $2 Ziel relativ zum App-Ordner
  adb push "$1" /data/local/tmp/pk.tmp > /dev/null
  adb shell "cat /data/local/tmp/pk.tmp | run-as $PKG sh -c 'cat > $2'"
  adb shell rm /data/local/tmp/pk.tmp
}
tap_text() { # tippt auf das erste Element mit diesem Text
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  b=$(python3 - "$1" <<'PY'
import re,sys
x=open('e2e-out/ui.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    if f'text="{sys.argv[1]}"' in n or f'content-desc="{sys.argv[1]}"' in n:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups())
        print((a+c)//2,(b+d)//2); break
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; sleep 3; echo "Tippe '$1' ($b)"; else echo "Nicht gefunden: $1"; fi
}

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell "run-as $PKG mkdir -p files/models"

# Erster Start: Einrichtungsbildschirm
adb shell am start -n $PKG/.MainActivity; sleep 8; shot 01-einrichtung
adb shell am force-stop $PKG

for f in "$M"/*; do push_app "$f" "files/models/$(basename "$f")"; echo "Modell: $(basename "$f")"; done
push_app scripts/e2e/meeting.wav files/test.wav

adb logcat -c
adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT \
  --es path /data/data/$PKG/files/test.wav --es model_set kompakt
sleep 10; shot 02-verarbeitung-start

status=""
for i in $(seq 1 150); do
  json=$(adb shell "run-as $PKG sh -c 'cat files/meetings/*/meeting.json'" 2>/dev/null)
  status=$(echo "$json" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('status',''))" 2>/dev/null)
  echo "[$i] Status: $status"
  if [ "$i" = "6" ]; then shot 03-verarbeitung-laeuft; fi
  if [ "$status" = "DONE" ] || [ "$status" = "ERROR" ]; then break; fi
  sleep 10
done
echo "$json" > "$OUT/meeting.json"
sleep 3; shot 04-ergebnis
tap_text "Transkript"; shot 05-transkript
tap_text "Aufgaben"; shot 06-aufgaben
adb shell input keyevent KEYCODE_BACK; sleep 3; shot 07-start
tap_text "Einstellungen"; shot 08-einstellungen
adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL|AndroidRuntime|ProtokollKI" "$OUT/logcat.txt" | tail -50
python3 -c "
import json; m=json.load(open('$OUT/meeting.json'))
print('STATUS', m['status'], m.get('error'))
print('SPRECHER', [s['name'] for s in m['speakers']])
for s in m['segments']: print(s['s'], s['t'])
print(json.dumps(m['summary'], ensure_ascii=False, indent=1))
for t in m['tasks']: print('AUFGABE', t)
" || true
[ "$status" = "DONE" ]
