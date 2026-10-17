import hashlib
import unittest
from prepare_android import metadata, SIGNER


class MetadataTest(unittest.TestCase):
    def test_metadata_comes_from_actual_apk_and_preserves_identity(self):
        actual = metadata("package: name='br.com.fabriciomatos.iptvplayer' versionCode='20' versionName='1.11.0'\nsdkVersion:'24'",
                          f"Signer #1 certificate SHA-256 digest: {SIGNER}", b"apk fixture", "\nNotes\n")
        self.assertEqual(20, actual["versionCode"])
        self.assertEqual(24, actual["minSdk"])
        self.assertEqual(hashlib.sha256(b"apk fixture").hexdigest(), actual["sha256"])
        self.assertTrue(actual["apkUrl"].endswith("/android-v1.11.0/aurora-iptv.apk"))

    def test_different_key_or_package_is_never_published(self):
        for package, signature in [("other.app", SIGNER), ("br.com.fabriciomatos.iptvplayer", "0" * 64)]:
            with self.assertRaises(ValueError):
                metadata(f"package: name='{package}' versionCode='20' versionName='1.11.0'\nsdkVersion:'24'",
                         f"Signer #1 certificate SHA-256 digest: {signature}", b"apk", "notes")


if __name__ == "__main__":
    unittest.main()
