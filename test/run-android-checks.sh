#!/usr/bin/env bash
set -euo pipefail
trap 'adb pull /sdcard/Download/aurora-ui app/build/aurora-ui || true' EXIT
gradle --no-daemon connectedDebugAndroidTest
python3 test/verify-force-stop.py
