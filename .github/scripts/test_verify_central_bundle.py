#!/usr/bin/env python3
"""Exercise Central bundle validation against hand-specified reactor artifacts."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).with_name("verify-central-bundle.py")
BASE = "org/example/"
PRIMARY = (
    BASE + "parent/1.2.3/parent-1.2.3.pom",
    BASE + "bom/1.2.3/bom-1.2.3.pom",
    BASE + "core/1.2.3/core-1.2.3.pom",
    BASE + "core/1.2.3/core-1.2.3.jar",
    BASE + "core/1.2.3/core-1.2.3-sources.jar",
    BASE + "core/1.2.3/core-1.2.3-javadoc.jar",
)


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pom = self.root / "pom.xml"
        self.pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<groupId>org.example</groupId><artifactId>parent</artifactId><version>1.2.3</version>
<packaging>pom</packaging><modules><module>core</module><module>bom</module></modules>
</project>''')
        for name, packaging in (("core", "jar"), ("bom", "pom")):
            (self.root / name).mkdir()
            (self.root / name / "pom.xml").write_text(f'''<project>
<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.2.3</version></parent>
<artifactId>{name}</artifactId><packaging>{packaging}</packaging></project>''')
        self.entries = {}
        for name in PRIMARY:
            self.add_artifact(name, b"fixture artifact " + name.encode())

    def add_artifact(self, name, data):
        self.entries[name] = data
        self.entries[name + ".asc"] = b"signature placeholder"
        for algorithm in ("md5", "sha1"):
            self.entries[name + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()

    def gate(self, *args, env=None, extra=()):
        bundle = self.root / "central-bundle.zip"
        with zipfile.ZipFile(bundle, "w") as archive:
            for name, data in list(self.entries.items()) + list(extra):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(name, data)
        return subprocess.run([sys.executable, str(SCRIPT), str(bundle), "--project-root", str(self.root), *args],
                              capture_output=True, text=True, env=env)

    def assert_rejected(self, result, detail):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(detail, result.stderr)

    def test_complete_release_accepts_extra_classifier_without_nested_sources(self):
        self.add_artifact(BASE + "core/1.2.3/core-1.2.3-all.jar", b"attached")
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_each_primary_parent_bom_and_module_artifact_is_rejected(self):
        for name in PRIMARY:
            with self.subTest(name=name):
                data = self.entries.pop(name)
                self.assert_rejected(self.gate(), name)
                self.entries[name] = data

    def test_snapshot_project_is_rejected(self):
        self.pom.write_text(self.pom.read_text().replace("1.2.3", "1.2.3-SNAPSHOT"))
        self.assert_rejected(self.gate(), "SNAPSHOT")

    def test_missing_signature_and_required_checksums_are_rejected(self):
        for suffix in (".asc", ".md5", ".sha1"):
            name = PRIMARY[0] + suffix
            data = self.entries.pop(name)
            self.assert_rejected(self.gate(), name)
            self.entries[name] = data

    def test_corrupt_required_checksum_is_rejected(self):
        for algorithm in ("md5", "sha1"):
            name = PRIMARY[3] + "." + algorithm
            old = self.entries[name]
            self.entries[name] = b"0" * len(old)
            self.assert_rejected(self.gate(), name)
            self.entries[name] = old

    def test_optional_strong_checksums_are_validated(self):
        for algorithm in ("sha256", "sha512"):
            name = PRIMARY[3] + "." + algorithm
            self.entries[name] = hashlib.new(algorithm, self.entries[PRIMARY[3]]).hexdigest().encode() + b"\n"
            result = self.gate()
            self.assertEqual(0, result.returncode, result.stderr)
            self.entries[name] = b"invalid"
            self.assert_rejected(self.gate(), name)
            del self.entries[name]

    def test_signature_checksums_use_the_signature_payload(self):
        for artifact in (PRIMARY[0], PRIMARY[3]):
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                with self.subTest(artifact=artifact, algorithm=algorithm):
                    name = artifact + ".asc." + algorithm
                    try:
                        self.entries[name] = hashlib.new(
                            algorithm, self.entries[artifact + ".asc"]).hexdigest().encode() + b"\n"
                        result = self.gate()
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.entries[name] = hashlib.new(
                            algorithm, self.entries[artifact]).hexdigest().encode()
                        self.assert_rejected(self.gate(), "Invalid checksum: " + name)
                    finally:
                        self.entries.pop(name, None)

    def test_orphan_sidecars_are_rejected(self):
        artifact = BASE + "core/1.2.3/core-1.2.3-extra.jar"
        for suffix in (".asc", ".sha1", ".asc.sha1"):
            with self.subTest(suffix=suffix):
                name = artifact + suffix
                self.assert_rejected(self.gate(extra=((name, b"orphan"),)), "Orphan sidecar: " + name)

    def test_duplicate_unsafe_and_unexpected_zip_paths_are_rejected(self):
        for name in (PRIMARY[0], "../outside.pom", "/outside.pom", "org\\example\\bad.pom",
                     BASE + "core/1.2.3/./core-1.2.3.pom", BASE + "other/1.2.3/other-1.2.3.pom"):
            with self.subTest(name=name):
                self.assert_rejected(self.gate(extra=((name, b"bad"),)), name)
        self.assertFalse((self.root.parent / "outside.pom").exists())

    def test_recursive_modules_and_inherited_coordinates_are_required(self):
        self.pom.write_text(self.pom.read_text().replace("<module>core</module>", ""))
        bom = self.root / "bom/pom.xml"
        bom.write_text(bom.read_text().replace("</project>", "<modules><module>../core</module></modules></project>"))
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.entries.pop(PRIMARY[3])
        self.assert_rejected(self.gate(), PRIMARY[3])

    @unittest.skipUnless(shutil.which("gpg"), "GnuPG is not installed")
    def test_real_signatures_pass_and_invalid_signature_fails(self):
        home = self.root / "gnupg"
        home.mkdir(mode=0o700)
        env = dict(os.environ, GNUPGHOME=str(home))
        self.addCleanup(subprocess.run, ["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"],
                        capture_output=True)
        generated = subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
                                   "--quick-generate-key", "Bundle fixture <bundle@example.invalid>", "ed25519", "sign", "0"],
                                  env=env, capture_output=True, timeout=30)
        if generated.returncode and b"No agent running" in generated.stderr:
            diagnostic = subprocess.run(["gpg-agent", "--homedir", str(home), "--daemon", "/bin/true"],
                                        capture_output=True, timeout=10)
            if b"can't create socket: Operation not permitted" in diagnostic.stderr:
                self.skipTest("GnuPG agent cannot create socket: Operation not permitted")
        self.assertEqual(0, generated.returncode, generated.stderr.decode())
        for name in PRIMARY:
            signed = subprocess.run(["gpg", "--batch", "--armor", "--detach-sign"], input=self.entries[name],
                                    env=env, capture_output=True, check=True, timeout=30)
            self.entries[name + ".asc"] = signed.stdout
        result = self.gate("--verify-signatures", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.entries[PRIMARY[3] + ".asc"] = self.entries[PRIMARY[0] + ".asc"]
        self.assert_rejected(self.gate("--verify-signatures", env=env), PRIMARY[3])


if __name__ == "__main__":
    unittest.main()
