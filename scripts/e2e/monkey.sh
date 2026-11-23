#!/usr/bin/env bash
# Zufalls-Klicktest über alle Bildschirme (ohne Modelle, mit vorbereiteten Besprechungen).
source scripts/e2e/common.sh
install_app
# Vorbereitete Besprechungen (fertig verarbeitet) einspielen
python3 scripts/e2e/make_fixtures.py "$OUT/fixtures"
for d in "$OUT"/fixtures/*/; do
  id=$(basename "$d"); adb shell "run-as $PKG mkdir -p files/meetings/$id"
  push_app "$d/meeting.json" "files/meetings/$id/meeting.json"
  [ -f "$d/audio.wav" ] && push_app "$d/audio.wav" "files/meetings/$id/audio.wav"
done
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><boolean name=\\\"onboardingDone\\\" value=\\\"true\\\" /></map>\" > shared_prefs/settings.xml'"
adb logcat -c
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 6; shot monkey-start
total=0
for seed in 11 22 33 44 55; do
  adb shell monkey -p $PKG -s $seed --throttle 120 --pct-syskeys 0 --pct-appswitch 5 --pct-anyevent 0 \
     --ignore-security-exceptions -v 1500 > "$OUT/monkey-$seed.txt" 2>&1
  if grep -qE "CRASH|ANR in|// CRASH" "$OUT/monkey-$seed.txt"; then
    fail "Zufalls-Klicktest Seed $seed: Absturz/ANR"; grep -A12 -E "CRASH|ANR in" "$OUT/monkey-$seed.txt" | head -30
  else pass "Zufalls-Klicktest Seed $seed: 1500 Aktionen ohne Absturz"; fi
  total=$((total+1500))
  adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 3
done
info "Zufalls-Aktionen gesamt: $total"
crash_report
summary
