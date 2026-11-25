#!/usr/bin/env bash
# Lange Aufnahme: eine im Test erzeugte 2-h-Datei (230 MB WAV, 16 kHz mono) importieren und verarbeiten.
# Geprüft wird bis zum Zeitlimit: kein OutOfMemory, kein Absturz, Fortschritt steigt; Java-Heap und
# App-Speicher werden mitgeschrieben. Wird die Verarbeitung im Zeitlimit fertig, wird auch das Ergebnis geprüft.
# Aufruf: long.sh <modellpaket> [<stunden>=2] [<zeitlimit in min>=75]
source scripts/e2e/common.sh
SET=${1:-kompakt}; HOURS=${2:-2}; LIMIT_MIN=${3:-75}
install_app; push_models
adb shell svc power stayon true
WAV="$OUT/lang_${HOURS}h.wav"
python3 - "$SC/lang.wav" "$WAV" "$HOURS" <<'PY'
import sys, wave
src, dst, hours = sys.argv[1], sys.argv[2], float(sys.argv[3])
with wave.open(src, "rb") as r:
    assert r.getframerate() == 16000 and r.getnchannels() == 1 and r.getsampwidth() == 2
    frames = r.readframes(r.getnframes())
target = int(hours * 3600 * 16000)
with wave.open(dst, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
    written = 0
    while written < target:
        n = min(len(frames) // 2, target - written)
        w.writeframes(frames[: n * 2]); written += n
print(f"{dst}: {written / 16000 / 3600:.2f} h")
PY
info "Testdatei: $(ls -lh "$WAV" | awk '{print $5}') ($HOURS h aus lang.wav wiederholt)"
push_app "$WAV" files/in/lang_lang.wav && rm -f "$WAV"
adb logcat -c
start_app; sleep 5
before=$(newest_meeting); t0=$SECONDS
adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT \
  --es path "/data/data/$PKG/files/in/lang_lang.wav" --es model_set "$SET" > /dev/null
# Der Import (Dekodieren über MediaCodec) legt die Besprechung erst am Ende an
id=""
while [ $((SECONDS - t0)) -lt 1200 ]; do
  sleep 5; id=$(newest_meeting); [ -n "$id" ] && [ "$id" != "$before" ] && break
  app_alive || { sleep 5; app_alive || break; }
done
if [ -z "$id" ] || [ "$id" = "$before" ]; then
  fail "Import der ${HOURS}-h-Datei hat keine Besprechung angelegt (App läuft: $(app_alive && echo ja || echo nein))"
  crash_report; summary; exit 1
fi
pass "Import der ${HOURS}-h-Datei in $((SECONDS - t0)) s, Dauer laut App $(field_of "$id" durationSec) s"
adb shell run-as $PKG rm -f files/in/lang_lang.wav

# Verarbeitung beobachten
t1=$SECONDS; dead=0; maxheap=0; maxpss=0; rises=0; last="-1"; first=""; st=""; trace="$OUT/fortschritt.txt"; : > "$trace"
while [ $((SECONDS - t1)) -lt $((LIMIT_MIN * 60)) ]; do
  sleep 30
  st=$(status_of "$id")
  if app_alive; then dead=0; else dead=$((dead+1)); [ $dead -ge 3 ] && break; fi
  p=$(last_progress "$id"); f=${p##* }
  mem=$(adb shell dumpsys meminfo $PKG 2>/dev/null | tr -d '\r')
  heap=$(echo "$mem" | awk '/Java Heap:/{print $3; exit}'); pss=$(echo "$mem" | awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL /{print $2; exit}')
  [ -n "$heap" ] && [ "$heap" -gt "$maxheap" ] && maxheap=$heap
  [ -n "$pss" ] && [ "$pss" -gt "$maxpss" ] && maxpss=$pss
  echo "$((SECONDS - t1)) s  $st  $p  heap=${heap:-?}KB pss=${pss:-?}KB" | tee -a "$trace"
  if [ -n "$f" ] && python3 -c "import sys; sys.exit(0 if float('$f') > float('$last') else 1)" 2>/dev/null; then
    rises=$((rises+1)); last=$f; [ -z "$first" ] && first=$f
  fi
  case "$st" in DONE|ERROR) break;; esac
done
info "Beobachtet $(( (SECONDS - t1) / 60 )) min: Status $st, Fortschritt ${first:-?} → ${last}, $rises Anstiege, Java-Heap max $((maxheap / 1024)) MB, App-Speicher (PSS) max $((maxpss / 1024)) MB"
adb logcat -d > "$OUT/logcat.txt"
if grep -q "OutOfMemoryError" "$OUT/logcat.txt"; then fail "OutOfMemoryError im Systemprotokoll"; grep -m5 -B2 -A8 "OutOfMemoryError" "$OUT/logcat.txt"
else pass "Kein OutOfMemoryError"; fi
if [ $dead -ge 3 ]; then fail "App während der Verarbeitung beendet (Absturz oder vom System abgeschossen)"
else pass "App lief die ganze Zeit"; fi
case "$st" in
  ERROR) fail "Verarbeitung fehlgeschlagen: $(error_of "$id")";;
  DONE) n=$(field_of "$id" segments); spk=$(field_of "$id" speakers)
        if [ "${n:-0}" -gt 50 ]; then pass "${HOURS}-h-Besprechung fertig verarbeitet: $n Beiträge, $spk Sprecher"
        else fail "${HOURS}-h-Besprechung fertig, aber nur ${n:-0} Beiträge"; fi;;
  *) if [ "$rises" -ge 3 ]; then pass "Fortschritt steigt ($rises Anstiege bis $last) – Zeitlimit $LIMIT_MIN min erreicht"
     else fail "Fortschritt steigt nicht (nur $rises Anstiege, zuletzt $last)"; fi;;
esac
# Java-Heap darf nicht die ganze Aufnahme halten (2 h als Float = 460 MB)
if [ "$maxheap" -gt 0 ] && [ "$maxheap" -lt $((400 * 1024)) ]; then pass "Java-Heap max. $((maxheap / 1024)) MB (< 400 MB)"
elif [ "$maxheap" -gt 0 ]; then fail "Java-Heap max. $((maxheap / 1024)) MB – lange Aufnahmen liegen im Speicher"; fi
crash_report
summary
