#!/usr/bin/env bash
set -euo pipefail

mkdir -p visual-qa
APP=et.addisreel.app/.MainActivity
BEFORE_APK=before/app/build/outputs/apk/debug/app-debug.apk
AFTER_APK=app/build/outputs/apk/debug/app-debug.apk
test -s "$BEFORE_APK" && test -s "$AFTER_APK"

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
oldsize=$(adb shell wm size | tr -d '\r' | tail -1)
olddensity=$(adb shell wm density | tr -d '\r' | tail -1)
trap 'adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true; adb shell cmd uimode night no >/dev/null 2>&1 || true; adb shell wm size reset >/dev/null 2>&1 || true; adb shell wm density reset >/dev/null 2>&1 || true' EXIT

tap_tab() {
  local tab="$1" x y size width height dpi offset
  size=$(adb shell wm size | tr -d '\r' | tail -n1 | grep -oE '[0-9]+x[0-9]+')
  width=$(printf '%s' "$size" | cut -d x -f 1)
  height=$(printf '%s' "$size" | cut -d x -f 2)
  dpi=$(adb shell wm density | tr -d '\r' | tail -n1 | grep -oE '[0-9]+' | tail -n1)
  offset=$((70 * dpi / 160))
  case "$tab" in
    watch) x=$((width / 8));;
    home) x=$((3 * width / 8));;
    library) x=$((5 * width / 8));;
    premium) x=$((7 * width / 8));;
    *) echo "Invalid tab: $tab" >&2; exit 2;;
  esac
  y=$((height - offset))
  adb shell input tap "$x" "$y"
  sleep 2
}

capture_group() {
  local stage="$1" theme="$2" font="$3" device="$4" group tab
  adb shell cmd uimode night "$theme" >/dev/null
  adb shell settings put system font_scale "$font"
  adb shell am force-stop et.addisreel.app || true
  adb shell am start -W -n "$APP"
  sleep 5
  group="$stage-$device-$theme-font$font"
  for tab in home watch library premium; do
    tap_tab "$tab"
    adb exec-out screencap -p > "visual-qa/$group-$tab.png"
    test -s "visual-qa/$group-$tab.png"
    adb shell uiautomator dump /sdcard/addisreel-ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/addisreel-ui.xml "visual-qa/$group-$tab.xml" >/dev/null 2>&1 || true
  done
}

capture_stage() {
  local stage="$1" apk="$2"
  adb uninstall et.addisreel.app >/dev/null 2>&1 || true
  adb install "$apk"
  adb shell pm clear et.addisreel.app >/dev/null
  capture_group "$stage" yes 1.0 normal
  capture_group "$stage" no 1.0 normal
  capture_group "$stage" yes 1.5 normal
  capture_group "$stage" no 1.5 normal
}

capture_stage baseline "$BEFORE_APK"
capture_stage candidate "$AFTER_APK"
# Candidate stress-case screenshots, with real 320dp display width.
adb shell wm size 320x720 >/dev/null
adb shell wm density 160 >/dev/null
capture_group candidate yes 2.0 compact
capture_group candidate no 2.0 compact
python3 - <<'PY'
from pathlib import Path
from hashlib import sha256
import json,struct
shots=sorted(Path('visual-qa').glob('*.png'))
assert len(shots)==40, f'Expected 40 actual images, got {len(shots)}'
dimensions={}
for item in shots:
    data=item.read_bytes()
    assert len(data)>1024 and data.startswith(b'\x89PNG\r\n\x1a\n'), f'Bad screenshot {item}'
    w,h=struct.unpack('>II',data[16:24])
    assert w>100 and h>100, f'Bad capture geometry {item}: {w}x{h}'
    dimensions[item.name]=[w,h]
for stage in ('baseline','candidate'):
    for mode in ('yes','no'):
        files=[x for x in shots if x.name.startswith(f'{stage}-normal-{mode}-font1.0')]
        assert len(files)==4
        hashes={sha256(x.read_bytes()).hexdigest() for x in files}
        assert len(hashes)>=3, f'{stage} {mode}: tab taps did not show distinct screens'
Path('visual-qa/capture-manifest.json').write_text(json.dumps({
    'count':len(shots),'dimensions':dimensions,
    'note':'Automated artifact integrity check, NOT a manual visual/accessibility approval.'
},indent=2))
print('Captured 40 before/after and compact-font screenshots; manual inspection remains required.')
PY
