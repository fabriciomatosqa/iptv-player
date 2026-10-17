"""Resign the built app using a disposable key to test rejection of a substituted APK."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
destination = root / "app/src/androidTest/assets/wrong-signer.apk"
with tempfile.TemporaryDirectory() as temporary:
    key = Path(temporary) / "fixture.keystore"
    subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", "fixture123", "-keypass", "fixture123",
                    "-alias", "fixture", "-keyalg", "RSA", "-validity", "2", "-dname", "CN=Disposable test fixture"], check=True, capture_output=True)
    subprocess.run([str(sdk / "build-tools/35.0.0/apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:fixture123",
                    "--key-pass", "pass:fixture123", "--out", str(destination), str(apk)], check=True)
