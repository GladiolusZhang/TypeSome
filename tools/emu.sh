#!/bin/bash
# Runs inside the emulator step of the screenshots workflow. Never fails the step; everything goes to screens-log.txt.
exec > screens-log.txt 2>&1
set -x
adb wait-for-device
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 5; done
sleep 10
APK=$(ls apk/*.apk | head -1)
for i in 1 2 3 4 5; do adb install -r -g "$APK" && break; sleep 10; done
adb shell ime enable com.shuyu.voiceime/.ImeService
adb shell ime set com.shuyu.voiceime/.ImeService
adb shell settings put secure show_ime_with_hard_keyboard 1
python3 tools/screens.py shots || echo "screens.py failed"
exit 0
