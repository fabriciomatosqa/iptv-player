"""Verify cached UI after Android actually kills the target process."""
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "br.com.fabriciomatos.iptvplayer"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True, timeout=30).stdout


def phase(name):
    output = adb("shell", "am", "instrument", "-w", "-e", "class",
                 PACKAGE + ".ForceStopFixtureTest#" + name, "-e", "forceStopPhase", name, RUNNER)
    print(output)
    assert "OK (1 test)" in output, output


adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
adb("install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
phase("seed")
adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
adb("shell", "settings", "put", "system", "user_rotation", "0")
for attempt in range(2):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    found = False
    texts = set()
    for _ in range(12):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/aurora-cache.xml")
            document = ET.fromstring(adb("shell", "cat", "/sdcard/aurora-cache.xml"))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(0.5)
            continue
        texts = {node.get("text") for node in document.iter("node")}
        if "Lista persistente" in texts and "3 conteúdos disponíveis" in texts:
            found = True
            break
        time.sleep(0.5)
    adb("shell", "screencap", "-p", "/sdcard/Download/aurora-ui/force-stop-" + str(attempt) + ".png")
    assert found, "Saved catalog was missing after force-stop: " + str(texts)
adb("shell", "am", "force-stop", PACKAGE)
phase("verify")
print("PASS: source, channels, favorites and daily ledger survived two real force-stops.")
