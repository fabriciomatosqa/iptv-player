"""Publish complete stable Android Releases. Published assets are never replaced."""
import json
import os
from pathlib import Path
import subprocess
import tempfile


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def main():
    directory = Path("dist")
    manifest = json.loads((directory / "aurora-update.json").read_text(encoding="utf-8"))
    repository = os.environ["GITHUB_REPOSITORY"]
    tag = "android-v" + manifest["versionName"]
    result = subprocess.run(["gh", "release", "view", tag, "--repo", repository, "--json", "isDraft"], text=True, capture_output=True)
    if result.returncode == 0 and not json.loads(result.stdout)["isDraft"]:
        with tempfile.TemporaryDirectory() as folder:
            gh("release", "download", tag, "--repo", repository, "--pattern", "aurora-update.json", "--dir", folder)
            previous = json.loads((Path(folder) / "aurora-update.json").read_text(encoding="utf-8"))
        if previous["versionCode"] != manifest["versionCode"] or previous["sha256"] != manifest["sha256"]:
            raise RuntimeError("This version was already published. Increment versionCode and versionName for a new APK.")
        print("This exact Android version is already published.")
        return
    if result.returncode != 0:
        gh("release", "create", tag, "--repo", repository, "--target", os.environ["GITHUB_SHA"],
           "--title", f"AURORA IPTV {manifest['versionName']} (Android)", "--notes-file", "release/notes.md", "--draft")
    gh("release", "upload", tag, "--repo", repository, "--clobber", *[str(directory / name) for name in
       ("aurora-iptv.apk", "aurora-update.json", "aurora-iptv.apk.sha256")])
    # A draft is hidden from clients until every asset has finished uploading.
    gh("release", "edit", tag, "--repo", repository, "--draft=false", "--latest")
    print(f"Published https://github.com/{repository}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
