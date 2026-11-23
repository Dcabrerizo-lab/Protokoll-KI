#!/usr/bin/env bash
# Randfälle: Stille, extrem kurz, App-Absturz/Neustart mitten in der Verarbeitung, erneute Verarbeitung.
source scripts/e2e/common.sh
SET=$1
install_app; push_models
adb logcat -c
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6
for name in stille kurz; do
  r=$(import_and_wait "$SC/$name.wav" "$SET" ALLGEMEIN "" 900)
  case "$r" in
    "") # zu kurze Dateien dürfen schon beim Import mit Meldung abgelehnt werden
        if app_alive; then pass "[$name] Import sauber abgelehnt, App läuft"; else fail "[$name] App nach Import beendet"; fi;;
    CRASH:*) fail "[$name] App abgestürzt";;
    *) id=${r%%:*}; meeting_json "$id" > "$OUT/$name.meeting.json"
       python3 scripts/e2e/check.py "$OUT/$name.meeting.json" "$SC/$name.json" "${r##*:}" | tee -a "$RESULTS";;
  esac
done

# Prozess mitten in der Transkription beenden (wie Absturz oder Systemabschuss)
push_app "$SC/drei_personen.wav" files/in/kill.wav
adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT --es path /data/data/$PKG/files/in/kill.wav --es model_set "$SET" > /dev/null
sleep 25
id=$(newest_meeting); st=$(status_of "$id")
info "Status vor dem Abschuss: $st"
adb shell am kill $PKG; adb shell am force-stop $PKG; sleep 3
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 8
if app_alive; then pass "App startet nach Abschuss wieder"; else fail "App startet nach Abschuss nicht"; fi
# Der Zustand wird beim Laden repariert (nicht dauerhaft "in Arbeit")
adb shell am force-stop $PKG; sleep 2; adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 8
shot kill-neustart
st2=$(status_of "$id")
info "Status auf Platte nach Neustart: $st2 (wird beim Laden auf RECORDED gesetzt, falls hängend)"
# Erneut verarbeiten über einen neuen Import derselben Datei muss funktionieren
r=$(import_and_wait "$SC/drei_personen.wav" "$SET" ALLGEMEIN "" 2400)
case "$r" in
  ""|CRASH:*) fail "Verarbeitung nach Neustart fehlgeschlagen ($r)";;
  *) id=${r%%:*}; meeting_json "$id" > "$OUT/nach-neustart.meeting.json"
     python3 scripts/e2e/check.py "$OUT/nach-neustart.meeting.json" "$SC/drei_personen.json" "${r##*:}" | tee -a "$RESULTS";;
esac

# Drehen des Bildschirms während der Verarbeitung
push_app "$SC/zwei_personen.wav" files/in/rot.wav
adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT --es path /data/data/$PKG/files/in/rot.wav --es model_set "$SET" > /dev/null
for i in 1 2 3 4 5 6; do adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation $((i % 2)); sleep 4; done
adb shell settings put system user_rotation 0
if app_alive; then pass "Drehen während der Verarbeitung ohne Absturz"; else fail "Absturz beim Drehen"; fi
crash_report
summary
