#!/usr/bin/env bash
set -euo pipefail
trap 'adb pull /sdcard/Download/aurora-ui app/build/aurora-ui || true' EXIT
python3 test/generate-track-fixture.py
python3 test/generate-update-fixture.py
gradle --no-daemon connectedDebugAndroidTest
python3 test/verify-force-stop.py
