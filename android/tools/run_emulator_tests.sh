#!/usr/bin/env bash
# Must collect while the emulator is still running, even after a test failure.
set -euo pipefail
collect() {
  mkdir -p android/build/runtime
  adb pull /sdcard/Android/data/com.ylports.deltaandroid/files/screenshots android/build/runtime/ || true
  adb logcat -d > android/build/runtime/logcat.txt || true
  adb shell wm size > android/build/runtime/display.txt || true
  adb shell wm density >> android/build/runtime/display.txt || true
}
trap collect EXIT
gradle --project-dir android -PdeltaAbi=x86_64 :app:connectedDebugAndroidTest --no-daemon --max-workers=2
