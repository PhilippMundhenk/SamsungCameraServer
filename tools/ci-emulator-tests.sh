#!/usr/bin/env bash
# Runs the Espresso suite + coverage gate on the CI emulator (reactivecircus/android-emulator-runner
# executes its `script:` one line at a time, so the logic lives here). Collects diagnostics for the
# android-test-and-coverage-reports artifact: screenshots before/after, the focused window, logcat.
set -uo pipefail
DIAG=app/build/emulator-diagnostics
mkdir -p "$DIAG"

# The runner reports "booted" a little before adb is really usable ("adb: device offline"):
# wait for a healthy device ourselves before touching it.
adb kill-server || true; adb start-server
adb wait-for-device
for i in $(seq 1 90); do
  state=$(adb get-state 2>/dev/null || true)
  booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  [ "$state" = "device" ] && [ "$booted" = "1" ] && break
  sleep 2
done
echo "adb state: $(adb get-state 2>/dev/null) boot_completed: $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"

settle() {
  # No keyevent 82 (MENU): on the launcher it opens the "Wallpaper & style" popup, which then
  # holds window focus and fails every Espresso test. BACK closes popups, HOME shows the launcher.
  adb shell wm dismiss-keyguard || true
  adb shell input keyevent 4 || true
  adb shell input keyevent 3 || true
}
settle
# The software-rendered launcher ANRs under load and its "Application Not Responding" dialog
# takes window focus away from the app under test; hide crash/ANR dialogs system-wide (what
# the CTS runners do) and give the launcher a moment to finish its first draw.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure show_first_crash_dialog 0 || true
adb shell settings put global hidden_api_policy 1 || true
sleep 15
adb logcat -c || true
adb exec-out screencap -p > "$DIAG/before.png" || true

# A suite that fails with RootViewWithoutFocusException is an environment failure, not a test
# failure: retry once after re-settling the device.
rc=1
for attempt in 1 2; do
  ./gradlew :app:connectedDebugAndroidTest :app:jacocoAndroidTestReport :app:jacocoAndroidTestCoverageVerification --no-daemon
  rc=$?
  [ $rc -eq 0 ] && break
  if [ $attempt -eq 1 ] && grep -rq "RootViewWithoutFocusException" app/build/outputs/androidTest-results 2>/dev/null; then
    echo "::warning::window focus lost on the emulator, retrying the suite once"
    adb exec-out screencap -p > "$DIAG/after-attempt1.png" || true
    adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > "$DIAG/focus-attempt1.txt" || true
    settle; sleep 10
  else
    break
  fi
done

# what was on screen and in focus when the run ended, plus the full logcat
adb exec-out screencap -p > "$DIAG/after.png" || true
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" > "$DIAG/focus.txt" || true
adb logcat -d > "$DIAG/logcat.txt" || true
exit $rc
