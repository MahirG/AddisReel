#!/usr/bin/env bash
set -euo pipefail

mkdir -p visual-qa
APP=et.addisreel.app/.MainActivity
APK=app/build/outputs/apk/debug/app-debug.apk
test -s "$APK"
adb install -r "$APK"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

size=$(adb shell wm size | tr -d '\r' | tail -n1 | grep -oE '[0-9]+x[0-9]+')
width=${size%x*}
height=${size#*x}
tap_tab() {
  local tab="$1" x y
  case "$tab" in
    watch) x=$((width / 8));;
    home) x=$((3 * width / 8));;
    library) x=$((5 * width / 8));;
    premium) x=$((7 * width / 8));;
    *) echo "Invalid tab" >&2; exit 2;;
  esac
  y=$((height - 210))
  adb shell input tap "$x" "$y"
  sleep 2
}

capture_group() {
  local theme="$1" font="$2"
  adb shell cmd uimode night "$theme" >/dev/null
  adb shell settings put system font_scale "$font"
  adb shell am force-stop et.addisreel.app || true
  adb shell am start -W -n "$APP"
  sleep 6
  local group="${theme}-font${font}"
  for tab in home watch library premium; do
    tap_tab "$tab"
    adb exec-out screencap -p > "visual-qa/${group}-${tab}.png"
    test -s "visual-qa/${group}-${tab}.png"
    adb shell uiautomator dump /sdcard/addisreel-window.xml >/dev/null 2>&1 || true
    adb pull /sdcard/addisreel-window.xml "visual-qa/${group}-${tab}.xml" >/dev/null 2>&1 || true
  done
}
trap 'adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true; adb shell cmd uimode night no >/dev/null 2>&1 || true' EXIT

capture_group yes 1.0
capture_group no 1.0
capture_group yes 1.5
capture_group no 1.5
python3 - <<'PY'
from pathlib import Path
shots = list(Path("visual-qa").glob("*.png"))
assert len(shots) == 16, f"Expected 16 screenshots, got {len(shots)}"
assert all(p.stat().st_size > 1024 for p in shots), "Tiny/invalid screenshot found"
print(f"Captured {len(shots)} screenshots; visual review is still required")
PY
