"""Create public Android update metadata from the actual, signed APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

REPOSITORY = "fabriciomatosqa/iptv-player"
PACKAGE = "br.com.fabriciomatos.iptvplayer"
# Keep updates compatible with every APK distributed since the persistent v2 key.
SIGNER = "be81a234029fb32898f82a0b7904bb21a0de84d1ff76b066197c55f908284a97"


def metadata(badging, signature, apk, notes):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    if not match or not sdk or match[1] != PACKAGE or not re.fullmatch(r"\d+\.\d+\.\d+", match[3]):
        raise ValueError("Unexpected Android package or version")
    if [value.lower() for value in certificates] != [SIGNER]:
        raise ValueError("Signing certificate changed; do not distribute an incompatible update")
    return {
        "schemaVersion": 1, "versionCode": int(match[2]), "versionName": match[3], "minSdk": int(sdk[1]),
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/android-v{match[3]}/aurora-iptv.apk",
        "size": len(apk), "sha256": hashlib.sha256(apk).hexdigest(), "notes": notes.strip(),
    }


def main():
    apk_path, output = Path(sys.argv[1]), Path(sys.argv[2])
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    build_tools = sdk / "build-tools" / "35.0.0"
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk_path)], text=True)
    signature = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk_path)], text=True)
    notes = (Path(__file__).parent / "notes.md").read_text(encoding="utf-8")
    info = metadata(badging, signature, apk_path.read_bytes(), notes)
    output.mkdir(parents=True, exist_ok=True)
    (output / "aurora-update.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "aurora-iptv.apk.sha256").write_text(info["sha256"] + "  aurora-iptv.apk\n", encoding="utf-8")
    print(f"Verified Android {info['versionName']} ({info['versionCode']}), {info['size']} bytes; signing identity preserved")


if __name__ == "__main__":
    main()
