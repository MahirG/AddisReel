#!/usr/bin/env bash
set -euo pipefail

mkdir -p visual-qa
# Retry transient transport failures without accepting missing captures.
adb_retry() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if timeout 45 adb "$@"; then return 0; fi
    echo "ADB retry $attempt: $*" >&2
    timeout 15 adb reconnect offline >&2 || true
    timeout 60 adb wait-for-device >&2 || true
    sleep 3
  done
  return 1
}
capture_png() {
  local dest="$1" attempt
  for attempt in 1 2 3 4 5; do
    if timeout 30 adb exec-out screencap -p > "$dest.tmp" &&
       python3 -c 'import sys; d=open(sys.argv[1],"rb").read(); assert len(d)>1024 and d.startswith(bytes.fromhex("89504e470d0a1a0a"))' "$dest.tmp"; then
      mv "$dest.tmp" "$dest"
      return 0
    fi
    rm -f "$dest.tmp"
    timeout 15 adb reconnect offline >&2 || true
    timeout 30 adb wait-for-device >&2 || true
    sleep 3
  done
  return 1
}
trap 'echo "QA failed at line $LINENO" >&2; adb devices -l >&2 || true; timeout 20 adb logcat -d -t 500 > visual-qa/failure-logcat.txt 2>&1 || true' ERR
set -E
APP=et.addisreel.app/.MainActivity
BEFORE_APK=before/app/build/outputs/apk/debug/app-debug.apk
AFTER_APK=app/build/outputs/apk/debug/app-debug.apk
case "${QA_STAGE:?}" in baseline) test -s "$BEFORE_APK";; candidate) test -s "$AFTER_APK";; *) exit 2;; esac

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
oldsize=$(adb_retry shell wm size | tr -d '\r' | tail -1)
olddensity=$(adb shell wm density | tr -d '\r' | tail -1)
trap 'adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true; adb shell cmd uimode night no >/dev/null 2>&1 || true; adb shell wm size reset >/dev/null 2>&1 || true; adb shell wm density reset >/dev/null 2>&1 || true' EXIT

tap_tab() {
  local tab="$1" x y size width height dpi offset
  size=$(adb_retry shell wm size | tr -d '\r' | tail -n1 | grep -oE '[0-9]+x[0-9]+')
  width=$(printf '%s' "$size" | cut -d x -f 1)
  height=$(printf '%s' "$size" | cut -d x -f 2)
  dpi=$(adb_retry shell wm density | tr -d '\r' | tail -n1 | grep -oE '[0-9]+' | tail -n1)
  offset=$((70 * dpi / 160))
  case "$tab" in
    watch) x=$((width / 8));;
    home) x=$((3 * width / 8));;
    library) x=$((5 * width / 8));;
    premium) x=$((7 * width / 8));;
    *) echo "Invalid tab: $tab" >&2; exit 2;;
  esac
  y=$((height - offset))
  adb_retry shell input tap "$x" "$y"
  sleep 2
}

capture_group() {
  local stage="$1" theme="$2" font="$3" device="$4" group tab
  adb_retry shell cmd uimode night "$theme" >/dev/null
  adb_retry shell settings put system font_scale "$font"
  adb shell am force-stop et.addisreel.app || true
  adb_retry shell am start -W -n "$APP"
  sleep 5
  adb_retry shell getprop sys.boot_completed
  group="$stage-$device-$theme-font$font"
  for tab in home watch library premium; do
    tap_tab "$tab"
    echo "Capturing $group-$tab"
    capture_png "visual-qa/$group-$tab.png"
    test -s "visual-qa/$group-$tab.png"
    adb_retry shell rm -f /sdcard/addisreel-ui.xml
    adb_retry shell uiautomator dump /sdcard/addisreel-ui.xml >/dev/null
    adb_retry pull /sdcard/addisreel-ui.xml "visual-qa/$group-$tab.xml" >/dev/null
  done
}

# Each matrix job owns a fresh emulator and exactly one configuration.
stage="${QA_STAGE:?}"
theme="${QA_THEME:?}"
font="${QA_FONT:?}"
device="${QA_DEVICE:?}"
case "$stage" in
  baseline) apk="$BEFORE_APK";;
  candidate) apk="$AFTER_APK";;
  *) exit 2;;
esac
adb_retry install "$apk"
if [ "$device" = compact ]; then
  adb_retry shell wm size 320x720
  adb_retry shell wm density 160
fi
capture_group "$stage" "$theme" "$font" "$device"
python3 - <<'PY'
from pathlib import Path
import struct, xml.etree.ElementTree as ET
shots=list(Path('visual-qa').glob('*.png'))
assert len(shots)==4, f'Expected four screenshots, got {len(shots)}'
for p in shots:
    d=p.read_bytes()
    assert len(d)>1024 and d.startswith(bytes.fromhex('89504e470d0a1a0a'))
    w,h=struct.unpack('>II',d[16:24])
    assert w>100 and h>100
    root=ET.parse(p.with_suffix('.xml')).getroot()
    assert any(n.get('package')=='et.addisreel.app' for n in root.iter('node')), p
print('Four captures and accessibility trees validated; manual review remains required.')
PY
