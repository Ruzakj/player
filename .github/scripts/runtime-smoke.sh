#!/usr/bin/env bash
set -euo pipefail

adb install -r apk/app-release.apk
adb logcat -c

adb shell am force-stop com.ric.player
adb shell am start -W -n com.ric.player/.PlayerActivity
sleep 2
test -n "$(adb shell pidof com.ric.player)"

# Open online media. Controls are made visible by playNewMedia() and auto-hide after ~3.2s.
adb shell am start -W -n com.ric.player/.PlayerActivity -a android.intent.action.VIEW -d "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
sleep 1
test -n "$(adb shell pidof com.ric.player)"

# Pixel 6 CI profile is 1080x2400 @ 420dpi. PiP is the right-most button in the
# 54dp top bar. Tap it directly before auto-hide; avoid UIAutomator dump latency.
adb shell input tap 970 100
sleep 3
test -n "$(adb shell pidof com.ric.player)"

adb shell dumpsys activity activities > activity.txt
if ! grep -Eiq 'PictureInPictureMode=true|picture.?in.?picture.*true|mIsInPictureInPictureMode=true' activity.txt; then
  echo "PiP state was not reported by ActivityManager"
  grep -i -C 10 'com.ric.player' activity.txt | tail -n 240 || true
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
