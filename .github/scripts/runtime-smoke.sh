#!/usr/bin/env bash
set -euo pipefail

adb install -r apk/app-release.apk
adb logcat -c

adb shell am force-stop com.ric.player
adb shell am start -W -n com.ric.player/.PlayerActivity
sleep 2
test -n "$(adb shell pidof com.ric.player)"

# Open online media, then trigger PiP before the custom controls auto-hide (3.2s).
adb shell am start -W -n com.ric.player/.PlayerActivity -a android.intent.action.VIEW -d "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
sleep 1
test -n "$(adb shell pidof com.ric.player)"

adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml window.xml >/dev/null
python3 - <<'PY'
import re, subprocess, sys, xml.etree.ElementTree as ET
root = ET.parse('window.xml').getroot()
for node in root.iter('node'):
    rid = node.attrib.get('resource-id', '')
    if rid == 'com.ric.player:id/pip' or rid.endswith(':id/pip'):
        nums = list(map(int, re.findall(r'\d+', node.attrib.get('bounds', ''))))
        if len(nums) == 4:
            x = (nums[0] + nums[2]) // 2
            y = (nums[1] + nums[3]) // 2
            subprocess.check_call(['adb','shell','input','tap',str(x),str(y)])
            sys.exit(0)
print(open('window.xml', encoding='utf-8').read())
raise SystemExit('PiP control resource-id not found before auto-hide')
PY

sleep 3
test -n "$(adb shell pidof com.ric.player)"

adb shell dumpsys activity activities > activity.txt
if ! grep -Eiq 'PictureInPictureMode=true|picture.?in.?picture.*true|mIsInPictureInPictureMode=true|mPictureInPictureParams' activity.txt; then
  echo "PiP state was not reported by ActivityManager"
  grep -i -C 8 'com.ric.player' activity.txt | tail -n 220 || true
  exit 1
fi

# Return from PiP to the same player session.
adb shell am start -W -n com.ric.player/.PlayerActivity
sleep 2
test -n "$(adb shell pidof com.ric.player)"

# Rotation smoke after PiP return.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
test -n "$(adb shell pidof com.ric.player)"
adb shell settings put system user_rotation 0
sleep 2
test -n "$(adb shell pidof com.ric.player)"

# Background/foreground transition.
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -W -n com.ric.player/.PlayerActivity
sleep 2
test -n "$(adb shell pidof com.ric.player)"

adb logcat -d > logcat.txt
if grep -E 'FATAL EXCEPTION|ANR in com\.ric\.player|Process: com\.ric\.player.*has died' logcat.txt; then
  echo "Runtime crash/ANR detected"
  grep -E -C 20 'FATAL EXCEPTION|ANR in com\.ric\.player|Process: com\.ric\.player.*has died' logcat.txt
  exit 1
fi

echo "Runtime smoke PASS: install, launch, online media, PiP entry/return, rotation, background/foreground, no fatal crash/ANR"
