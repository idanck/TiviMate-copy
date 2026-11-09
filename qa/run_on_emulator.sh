#!/usr/bin/env bash
# Entry point the emulator job runs once the device has booted: starts the mock provider,
# runs every scenario against the installed debug APK, and never fails the step itself, so the
# evidence is always uploaded — the verdicts live in report.json, not in the exit code.
#
# Usage: qa/run_on_emulator.sh <tv|phone> <apk> <out-dir> <media-dir>
set -uo pipefail
form="$1"; apk="$2"; out="$3"; media="$4"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out"

python3 "$here/mock_provider/server.py" "$media" "$out/mock" 8080 > "$out/mock_server.log" 2>&1 &
server=$!
for _ in $(seq 1 20); do curl -fs http://127.0.0.1:8080/health >/dev/null && break; sleep 0.5; done

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
# The emulator sees the host as 10.0.2.2; prove the route before blaming the app for it.
adb shell "ping -c1 -W3 10.0.2.2 >/dev/null 2>&1 && echo host-reachable || echo host-ping-blocked" | tee "$out/route.txt"

# ADR-060: the bridge, built beside the app by the same job and so signed with the same debug key.
bridge="$(ls "$(dirname "$apk")"/bridge/*.apk 2>/dev/null | head -1)"
timeout 55m python3 "$here/driver/shidur_qa.py" --form "$form" --apk "$apk" --out "$out" \
  --mock http://10.0.2.2:8080 ${bridge:+--bridge-apk "$bridge"} 2>&1 | tee "$out/driver.log"
echo "driver exit: ${PIPESTATUS[0]}" >> "$out/driver.log"

kill "$server" 2>/dev/null || true
exit 0
