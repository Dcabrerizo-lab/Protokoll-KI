#!/usr/bin/env bash
# Härtetest: Unterbrechungen und knappe Ressourcen.
#  A  Aufnahme: Hintergrund, Bildschirm aus, Anruf, Speicherdruck – es darf nichts verloren gehen;
#     Abschuss (kill -9) während der Aufnahme – das Aufgenommene bleibt erhalten.
#  B  Verarbeitung: Hintergrund, Bildschirm aus, Speicherdruck, am kill – läuft weiter bis zum Protokoll;
#     kill -9 in Transkription und Protokoll-Schritt – beim Neustart verständlich "fehlgeschlagen",
#     keine Endlosschleife, "Erneut versuchen" führt zum fertigen Protokoll.
#  C  Akku 5 % ohne Ladegerät + Energiesparmodus: Warnung sichtbar, Verarbeitung ohne Absturz.
#  D  Speicher fast voll: Aufnahme startet nicht bzw. endet sauber mit Meldung, Import mit Meldung.
# Aufruf: stress.sh <modellpaket>
source scripts/e2e/common.sh
SET=${1:-kompakt}
install_app; push_models
# Bildschirm bleibt an, außer wenn der Test ihn gezielt ausschaltet (KEYCODE_SLEEP)
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb logcat -c
start_app; sleep 6

# Wartet, bis die Besprechung $1 fertig/fehlgeschlagen ist (max. $2 s). Ausgabe: Status
wait_final() {
  local id=$1 limit=$2 t0=$SECONDS st="" dead=0
  while [ $((SECONDS - t0)) -lt "$limit" ]; do
    st=$(status_of "$id")
    case "$st" in DONE|ERROR) echo "$st"; return 0;; esac
    if app_alive; then dead=0; else dead=$((dead+1)); [ $dead -ge 4 ] && { echo "CRASH"; return 1; }; fi
    sleep 8
  done
  echo "TIMEOUT:$st"
}
# Wartet, bis die Verarbeitung von $1 die Phase $2 (TRANSCRIBING/SUMMARIZING) erreicht (max. $3 s)
wait_phase() {
  local id=$1 phase=$2 limit=$3 t0=$SECONDS
  while [ $((SECONDS - t0)) -lt "$limit" ]; do
    case "$(last_progress "$id")" in "$phase "*) return 0;; esac
    case "$(status_of "$id")" in DONE|ERROR) return 1;; esac
    sleep 3
  done
  return 1
}
import_file() { # $1 Szenario-WAV, $2 Name im App-Ordner, $3 auto_process (true/false); Ausgabe: Besprechungs-ID
  local before id; push_app "$1" "files/in/$2"; before=$(newest_meeting)
  adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT \
    --es path "/data/data/$PKG/files/in/$2" --es model_set "$SET" --ez auto_process "${3:-true}" > /dev/null
  for _ in $(seq 1 60); do sleep 2; id=$(newest_meeting); [ -n "$id" ] && [ "$id" != "$before" ] && { echo "$id"; return 0; }; done
  return 1
}

# Tippt "Erneut versuchen" und prüft, dass die Verarbeitung wirklich wieder anläuft
retry_ui() {
  local id=$1
  for _ in 1 2 3; do
    screen_on; tap_text "Erneut versuchen"; sleep 6
    [ "$(status_of "$id")" != ERROR ] && return 0
    info "Erneut versuchen hat nicht gegriffen, Oberfläche: $(ui_texts)"
  done
  debug_log "Erneut versuchen"
  return 1
}

# Öffnet die Besprechung mit Titel $1 (nach Neustart steht die App evtl. schon darin)
open_meeting() {
  ui_has "Erneut versuchen" && return 0
  tap_text "$1" || { adb shell input keyevent KEYCODE_BACK; sleep 2; tap_text "$1"; }
  sleep 3
}

# ================================================================ A  Aufnahme
info "=== A: Unterbrechungen während der Aufnahme ==="
rec_start() { adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_RECORD --es cmd start > /dev/null; }
rec_stop() { adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_RECORD --es cmd stop > /dev/null; }
before=$(newest_meeting); rec_start; sleep 6
rid=$(newest_meeting); mic=0
if [ -n "$rid" ] && [ "$rid" != "$before" ] && [ "$(status_of "$rid")" = RECORDING ]; then
  s1=$(file_size "files/meetings/$rid/audio.wav"); sleep 3; s2=$(file_size "files/meetings/$rid/audio.wav")
  [ "${s2:-0}" -gt "${s1:-0}" ] && mic=1
fi
if [ $mic = 0 ]; then
  info "Mikrofon im Emulator nicht verfügbar (Status $(status_of "$rid"): $(error_of "$rid")) – Aufnahmeteil übersprungen"
  rec_stop; sleep 3
else
  t0=$((SECONDS - 9))
  adb shell input keyevent KEYCODE_HOME; sleep 8
  adb shell am send-trim-memory $PKG COMPLETE > /dev/null 2>&1
  adb shell input keyevent KEYCODE_SLEEP; sleep 20
  screen_on; sleep 2
  # Anruf während der Aufnahme (Emulator-Telefonie): klingeln, annehmen, auflegen
  adb emu gsm call 5551234 > /dev/null 2>&1; sleep 6
  adb emu gsm accept 5551234 > /dev/null 2>&1; sleep 10
  adb emu gsm cancel 5551234 > /dev/null 2>&1; sleep 4
  start_app; sleep 3
  adb shell am send-trim-memory $PKG RUNNING_CRITICAL > /dev/null 2>&1; sleep 3
  if [ "$(status_of "$rid")" = RECORDING ] && app_alive; then pass "[Aufnahme] läuft nach Hintergrund, Bildschirm aus, Anruf und Speicherdruck weiter"
  else fail "[Aufnahme] nach Unterbrechungen nicht mehr aktiv (Status $(status_of "$rid"))"; fi
  rec_stop; sleep 5
  wall=$((SECONDS - t0 - 5)); dur=$(field_of "$rid" durationSec); st=$(status_of "$rid")
  info "Aufnahme: Wanduhr ~${wall} s, gespeichert ${dur} s, Status $st $(error_of "$rid")"
  if [ "$st" = RECORDED ] && python3 -c "import sys; sys.exit(0 if float('$dur') >= $wall - 6 else 1)"; then
    pass "[Aufnahme] nichts verloren: ${dur} s gespeichert bei ~${wall} s Laufzeit"
  else fail "[Aufnahme] Audio verloren oder Status falsch: ${dur} s von ~${wall} s, Status $st"; fi

  # Abschuss während der Aufnahme
  before=$(newest_meeting); rec_start; sleep 25; kid=$(newest_meeting)
  if kill9_app; then pass "[Aufnahme] Prozess per kill -9 beendet"; else fail "[Aufnahme] kill -9 nicht möglich"; fi
  start_app; sleep 8
  [ "$(status_of "$kid")" = RECORDING ] && { debug_log "nach Abschuss Aufnahme"; rec_stop; sleep 3; }
  st=$(status_of "$kid"); dur=$(field_of "$kid" durationSec)
  if [ "$st" = RECORDED ] && python3 -c "import sys; sys.exit(0 if float('$dur') >= 15 else 1)"; then
    pass "[Aufnahme] nach Abschuss erhalten: ${dur} s, Status $st"
  else fail "[Aufnahme] nach Abschuss: Status $st, ${dur} s ($(error_of "$kid"))"; fi
fi
shot stress-aufnahme

# ================================================================ B  Verarbeitung
info "=== B: Unterbrechungen während der Verarbeitung ==="
bid=$(import_file "$SC/drei_personen.wav" hintergrund.wav true)
if [ -z "$bid" ]; then fail "[Verarbeitung] Import fehlgeschlagen"; else
  wait_phase "$bid" TRANSCRIBING 300 || info "Phase Transkription nicht im Protokoll gesehen"
  adb shell input keyevent KEYCODE_HOME; sleep 5
  adb shell am send-trim-memory $PKG COMPLETE > /dev/null 2>&1
  adb shell am kill $PKG; sleep 3     # sanftes Beenden: darf einem Vordergrunddienst nichts anhaben
  if app_alive; then pass "[Verarbeitung] am kill im Hintergrund: Dienst läuft weiter"; else fail "[Verarbeitung] am kill hat die Verarbeitung beendet"; fi
  adb shell input keyevent KEYCODE_SLEEP; sleep 45
  screen_on; start_app; sleep 3
  adb shell am send-trim-memory $PKG RUNNING_CRITICAL > /dev/null 2>&1
  r=$(wait_final "$bid" 900)
  if [ "$r" = DONE ]; then pass "[Verarbeitung] Hintergrund + Bildschirm aus + Speicherdruck → Protokoll fertig"
  else fail "[Verarbeitung] nach Hintergrund/Bildschirm aus: $r ($(error_of "$bid"))"; fi
fi

kid=$(import_file "$SC/zwei_personen.wav" abschuss.wav true)
if [ -z "$kid" ]; then fail "[Abschuss] Import fehlgeschlagen"; else
  wait_phase "$kid" TRANSCRIBING 300; sleep 8
  info "Abschuss in Phase: $(last_progress "$kid")"
  kill9_app || fail "[Abschuss] kill -9 nicht möglich"
  adb logcat -c
  start_app; sleep 10
  st=$(status_of "$kid"); err=$(error_of "$kid")
  if [ "$st" = ERROR ] && echo "$err" | grep -q "unterbrochen"; then pass "[Abschuss Transkription] beim Neustart verständlich gemeldet: $err"
  else fail "[Abschuss Transkription] Status nach Neustart: $st ($err)"; fi
  sleep 30
  if [ "$(status_of "$kid")" = ERROR ] && [ -z "$(last_progress "$kid")" ] && app_alive; then
    pass "[Abschuss] keine Endlosschleife: bleibt ruhig bei „fehlgeschlagen“"
  else fail "[Abschuss] Verarbeitung startet von selbst neu oder App weg (Status $(status_of "$kid"))"; fi
  shot stress-abschuss-fehler
  # Über die Oberfläche erneut versuchen
  open_meeting "abschuss"
  if ui_has "Erneut versuchen"; then pass "[Abschuss] Fehlerkarte mit „Erneut versuchen“ sichtbar"
  else fail "[Abschuss] „Erneut versuchen“ nicht sichtbar"; fi
  retry_ui "$kid"
  # Zweiter Abschuss im Protokoll-Schritt: Transkript bleibt, erneuter Versuch schreibt nur das Protokoll
  if wait_phase "$kid" SUMMARIZING 600; then
    sleep 5; kill9_app; start_app; sleep 10
    st=$(status_of "$kid"); n=$(field_of "$kid" segments)
    if [ "$st" = ERROR ] && [ "${n:-0}" -gt 0 ]; then pass "[Abschuss Protokoll] gemeldet, Transkript ($n Beiträge) erhalten"
    else fail "[Abschuss Protokoll] Status $st, Beiträge ${n:-0}"; fi
    open_meeting "abschuss"
    retry_ui "$kid"
  else info "Protokoll-Schritt nicht erreicht (Status $(status_of "$kid"))"; fi
  r=$(wait_final "$kid" 900)
  if [ "$r" = DONE ]; then pass "[Abschuss] „Erneut versuchen“ führt zum fertigen Protokoll"
  else fail "[Abschuss] nach erneutem Versuch: $r ($(error_of "$kid"))"; debug_log "nach erneutem Versuch"; fi
fi

# ================================================================ C  Akku + Energiesparmodus
info "=== C: Akku 5 % ohne Ladegerät, Energiesparmodus ==="
adb shell dumpsys battery unplug; adb shell dumpsys battery set level 5
adb shell settings put global low_power 1
sleep 2
screen_on
cid=$(import_file "$SC/folgesitzung.wav" akku.wav false)
sleep 4; shot stress-akku
ui_has "Akku bei 5" || info "Oberfläche: $(ui_texts)"
if ui_has "Akku bei 5"; then pass "[Akku] Warnung vor der Verarbeitung sichtbar (5 %, ohne Ladegerät)"
else fail "[Akku] keine Akku-Warnung bei 5 % ohne Ladegerät"; fi
tap_text "Jetzt verarbeiten" || fail "[Akku] Knopf „Jetzt verarbeiten“ nicht gefunden"
sleep 5
r=$(wait_final "$cid" 900)
if [ "$r" = DONE ] || [ "$r" = ERROR ] && app_alive; then pass "[Akku] Verarbeitung im Energiesparmodus ohne Absturz ($r)"
else fail "[Akku] Verarbeitung im Energiesparmodus: $r"; fi
adb shell settings put global low_power 0; adb shell dumpsys battery reset

# ================================================================ D  Speicher fast voll
info "=== D: Speicher fast voll ==="
python3 - "$OUT/gross.wav" <<'PY'
import sys, wave, math
# 30 min Ton (57 MB) für den Import bei vollem Speicher
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
    sec = b"".join(int(3000 * math.sin(2 * math.pi * 220 * i / 16000)).to_bytes(2, "little", signed=True) for i in range(16000))
    for _ in range(1800): w.writeframes(sec)
PY
push_app "$OUT/gross.wav" files/in/gross.wav; rm -f "$OUT/gross.wav"
fill_disk fill1 30
# Aufnahme starten bei < 50 MB: sofort mit Meldung abgelehnt (unabhängig vom Mikrofon)
before=$(newest_meeting); rec_start; sleep 5; did=$(newest_meeting)
if [ -n "$did" ] && [ "$did" != "$before" ] && [ "$(status_of "$did")" = ERROR ] && error_of "$did" | grep -q "Speicher"; then
  pass "[Speicher voll] Aufnahme startet nicht, Meldung: $(error_of "$did")"
else fail "[Speicher voll] Aufnahme bei vollem Speicher: Status $(status_of "$did") ($(error_of "$did"))"; fi
rec_stop; sleep 2
screen_on
# Import einer 57-MB-Datei bei ~30 MB frei: sauberer Abbruch mit Meldung, App läuft
before=$(newest_meeting)
adb shell am start -n $PKG/.MainActivity -a de.protokollki.DEBUG_IMPORT \
  --es path "/data/data/$PKG/files/in/gross.wav" --es model_set "$SET" --ez auto_process false > /dev/null
sleep 40; shot stress-speicher-import
if app_alive && [ "$(newest_meeting)" = "$before" ] && ui_has "Speicherplatz"; then pass "[Speicher voll] Import abgebrochen mit Meldung „Speicherplatz“"
elif app_alive && [ "$(newest_meeting)" = "$before" ]; then fail "[Speicher voll] Import abgebrochen, aber ohne verständliche Meldung: $(ui_texts)"
else fail "[Speicher voll] Import: App weg oder Besprechung trotzdem angelegt"; fi
ui_has "OK" && tap_text "OK"
free_disk
# Laufende Aufnahme, dann wird der Speicher knapp: Aufnahme endet sauber, Bisheriges bleibt
if [ $mic = 1 ]; then
  fill_disk fill2 80
  before=$(newest_meeting); rec_start; sleep 8; lid=$(newest_meeting)
  fill_disk fill3 12; sleep 20
  st=$(status_of "$lid"); err=$(error_of "$lid"); dur=$(field_of "$lid" durationSec)
  if [ "$st" = RECORDED ] && echo "$err" | grep -q "Speicher"; then pass "[Speicher voll] laufende Aufnahme sauber beendet (${dur} s gespeichert): $err"
  else fail "[Speicher voll] laufende Aufnahme: Status $st, ${dur} s ($err)"; fi
  rec_stop; free_disk
fi
free_disk
if app_alive || { start_app; sleep 5; app_alive; }; then pass "App läuft nach allen Härtetests"; else fail "App startet nach den Härtetests nicht"; fi
crash_report
summary
