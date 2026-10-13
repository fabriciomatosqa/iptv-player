#!/usr/bin/env bash
set -euo pipefail
trap 'adb pull /sdcard/Download/aurora-ui app/build/aurora-ui || true' EXIT
python3 test/generate-track-fixture.py
# UiModeManager ignores night-mode changes until initial Android setup is complete.
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
gradle --no-daemon connectedDebugAndroidTest
python3 test/verify-force-stop.py
