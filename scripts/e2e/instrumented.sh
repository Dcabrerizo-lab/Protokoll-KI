#!/usr/bin/env bash
# Gerätetests (app/src/androidTest) im Emulator ausführen.
# Aufruf: instrumented.sh fast    -> Plattform- und Oberflächentests, ohne KI-Modelle
#         instrumented.sh models  -> Verarbeitung mit echten Modellen (Kompakt)
source scripts/e2e/common.sh
MODE=${1:-fast}
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ ! -f "$TEST_APK" ]; then fail "Gerätetest-APK fehlt (Bau fehlgeschlagen?)"; summary; exit 1; fi
install_app
adb install -r "$TEST_APK" > /dev/null && pass "Gerätetest-APK installiert"
if [ "$MODE" = models ]; then
  push_models
  ARGS="-e annotation de.protokollki.testutil.RequiresModels -e requireModels true"; CLASSES=de.protokollki.ModelPipelineTest; LIMIT=5400
else
  ARGS="-e notAnnotation de.protokollki.testutil.RequiresModels"; CLASSES=de.protokollki.PlatformTest,de.protokollki.UiFlowTest; LIMIT=2400
fi
adb shell svc power stayon true; adb shell wm dismiss-keyguard 2>/dev/null || true
adb logcat -c
info "Starte Gerätetests: $CLASSES"
timeout "$LIMIT" adb shell am instrument -w -r $ARGS -e class "$CLASSES" \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrument.txt"
# Einzelergebnisse aus dem Rohprotokoll lesen
python3 - "$OUT/instrument.txt" >> "$RESULTS" <<'PY'
import re, sys
cur, test, failed = {}, None, set()
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.rstrip("\r\n")
    m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
    if m: cur[m.group(1)] = m.group(2)
    m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
    if m:
        code = int(m.group(1)); name = f"{cur.get('class','').split('.')[-1]}.{cur.get('test','')}"
        if code == 0: print(f"PASS  {name}")
        elif code in (-1, -2): print(f"FAIL  {name}")
        elif code == -3: print(f"INFO  übersprungen: {name}")
PY
if grep -q "^OK (" "$OUT/instrument.txt"; then pass "Gerätetests: $(grep '^OK (' "$OUT/instrument.txt")"
else fail "Gerätetests nicht vollständig grün: $(grep -E 'Tests run|FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrument.txt" | head -3 | tr '\n' ' ')"; fi
crash_report
summary
