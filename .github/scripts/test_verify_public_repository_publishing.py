#!/usr/bin/env python3
"""Exercise publication policy against safe profiles and accidental publication."""

import copy
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/verify-public-repository-publishing.py"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])


class PublicationPolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        paths = [
            Path("pom.xml"),
            Path(".github/workflows/release.yml"),
            Path(".github/workflows/maven.yml"),
            Path(".github/workflows/publish-snapshot.yml"),
            Path(".github/scripts/release.sh"),
            Path(".github/public-repository-consumer/pom.xml"),
        ]
        paths.extend(path.relative_to(ROOT) for path in ROOT.glob("jgit-storage-hibernate-*/pom.xml"))
        for relative in paths:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        self.pom = ET.parse(self.root / "pom.xml")
        profiles = self.pom.find("m:profiles", NS)
        self.central = profiles.find("m:profile[m:id='maven-central']", NS)
        if self.central is None:
            self.central = ET.fromstring('''
              <profile xmlns="http://maven.apache.org/POM/4.0.0">
                <id>maven-central</id>
                <properties><central.skipUpload>true</central.skipUpload></properties>
                <build><plugins>
                  <plugin>
                    <groupId>org.sonatype.central</groupId>
                    <artifactId>central-publishing-maven-plugin</artifactId>
                    <version>0.12.0</version><extensions>true</extensions>
                    <configuration>
                      <publishingServerId>central</publishingServerId>
                      <skipUpload>${central.skipUpload}</skipUpload>
                      <autoPublish>false</autoPublish>
                      <waitUntil>validated</waitUntil>
                      <waitForPublishCompletion>false</waitForPublishCompletion>
                      <ignorePublishedComponents>false</ignorePublishedComponents>
                    </configuration>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-gpg-plugin</artifactId><version>3.2.8</version>
                    <configuration><bestPractices>true</bestPractices><skip>false</skip></configuration>
                    <executions><execution><id>sign-central-artifacts</id>
                      <phase>verify</phase><goals><goal>sign</goal></goals>
                    </execution></executions>
                  </plugin>
                </plugins></build>
              </profile>
            ''')
            profiles.append(self.central)
        self.publisher = self.central.find(
            "m:build/m:plugins/m:plugin[m:artifactId='central-publishing-maven-plugin']", NS
        )

    def run_policy(self):
        self.pom.write(self.root / "pom.xml", encoding="utf-8", xml_declaration=True)
        return subprocess.run(
            [sys.executable, str(SCRIPT)], cwd=self.root,
            text=True, capture_output=True, check=False,
        )

    def assert_rejected(self, message):
        result = self.run_policy()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(message, result.stderr)

    def test_accepts_explicit_local_bundle_profile_without_credentials(self):
        result = self.run_policy()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_static_publication_remains_valid_without_central_profile(self):
        self.pom.find("m:profiles", NS).remove(self.central)
        result = self.run_policy()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_automatically_activated_central_profile(self):
        activation = ET.SubElement(self.central, "{" + NS["m"] + "}activation")
        ET.SubElement(activation, "{" + NS["m"] + "}activeByDefault").text = "true"
        self.assert_rejected("maven-central must be activated explicitly")

    def test_rejects_central_plugin_in_the_normal_build(self):
        self.pom.find("m:build/m:plugins", NS).append(copy.deepcopy(self.publisher))
        self.assert_rejected("must remain inside the maven-central profile")

    def test_rejects_gpg_in_the_static_release_profile(self):
        public = self.pom.find("m:profiles/m:profile[m:id='public-repository-release']", NS)
        build = ET.SubElement(public, "{" + NS["m"] + "}build")
        plugins = ET.SubElement(build, "{" + NS["m"] + "}plugins")
        signer = self.central.find("m:build/m:plugins/m:plugin[m:artifactId='maven-gpg-plugin']", NS)
        plugins.append(copy.deepcopy(signer))
        self.assert_rejected("must remain inside the maven-central profile")

    def test_rejects_upload_enabled_by_default(self):
        self.central.find("m:properties/m:central.skipUpload", NS).text = "false"
        self.assert_rejected("maven-central must skip upload by default")

    def test_rejects_upload_switch_not_connected_to_safe_default(self):
        self.publisher.find("m:configuration/m:skipUpload", NS).text = "false"
        self.assert_rejected("Central skipUpload must use ${central.skipUpload}")

    def test_rejects_automatic_publication_including_legacy_switch(self):
        for field in ("autoPublish", "waitForPublishCompletion"):
            with self.subTest(field=field):
                element = self.publisher.find("m:configuration/m:" + field, NS)
                element.text = "true"
                self.assert_rejected("Central " + field + " must remain false")
                element.text = "false"

    def test_rejects_credential_lookup_for_existing_components_during_preparation(self):
        self.publisher.find("m:configuration/m:ignorePublishedComponents", NS).text = "true"
        self.assert_rejected("Central ignorePublishedComponents must remain false")

    def test_rejects_disabled_signing(self):
        signer = self.central.find("m:build/m:plugins/m:plugin[m:artifactId='maven-gpg-plugin']", NS)
        signer.find("m:configuration/m:skip", NS).text = "true"
        self.assert_rejected("Central signing must not be skipped")


if __name__ == "__main__":
    unittest.main()
