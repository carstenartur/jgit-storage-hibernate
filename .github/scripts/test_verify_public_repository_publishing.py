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
        # Construct the optional trailing dispatcher independently in the focused cases.
        release = self.root / ".github/workflows/release.yml"
        release.write_text(release.read_text().split("\n  central-upload:\n", 1)[0] + "\n")
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

    def add_central_job(self, needs="finalize"):
        workflow = self.root / ".github/workflows/release.yml"
        workflow.write_text(workflow.read_text() + f'''
  central-upload:
    needs: {needs}
    runs-on: ubuntu-latest
    steps:
      - env:
          GH_TOKEN: ${{{{ github.token }}}}
        run: gh workflow run maven-central.yml --ref main -f release_version=0.12.1 -f mode=upload
''')

    def test_allows_independent_central_job_after_finalize(self):
        self.add_central_job()
        result = self.run_policy()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_central_job_must_depend_on_finalize(self):
        self.add_central_job(needs="prepare")
        self.assert_rejected("central-upload must depend on finalize")

    def test_central_secret_in_existing_release_job_is_rejected(self):
        self.add_central_job()
        path = self.root / ".github/workflows/release.yml"
        for job in ("prepare", "finalize"):
            with self.subTest(job=job):
                original = path.read_text()
                path.write_text(original.replace(f"  {job}:\n", f"  {job}:\n    env:\n      MAVEN_GPG_KEY: forbidden\n", 1))
                self.assert_rejected("release workflow retains MAVEN_GPG")
                path.write_text(original)

    def test_central_dispatcher_cannot_access_production_signing_secrets(self):
        self.add_central_job()
        path = self.root / ".github/workflows/release.yml"
        path.write_text(path.read_text().replace("  central-upload:\n", "  central-upload:\n    env:\n      MAVEN_GPG_KEY: forbidden\n"))
        self.assert_rejected("release workflow retains MAVEN_GPG")

    def test_central_dispatch_cannot_move_into_existing_release_job(self):
        path = self.root / ".github/workflows/release.yml"
        original = path.read_text()
        for job in ("prepare", "finalize"):
            with self.subTest(job=job):
                path.write_text(original.replace(f"  {job}:\n", f"  {job}:\n    env:\n      CHILD: maven-central.yml\n", 1))
                self.assert_rejected("Central reference must remain inside central-upload")

    def test_central_exception_does_not_cover_other_or_global_workflow_content(self):
        self.add_central_job()
        path = self.root / ".github/workflows/release.yml"
        original = path.read_text()
        for text in (
            "env:\n  CENTRAL_USERNAME: forbidden\n" + original,
            original + "\n  unrelated:\n    env:\n      CENTRAL_USERNAME: forbidden\n",
        ):
            path.write_text(text)
            self.assert_rejected("release workflow retains CENTRAL_USERNAME")

    def test_central_exception_does_not_allow_secrets_in_release_script(self):
        self.add_central_job()
        path = self.root / ".github/scripts/release.sh"
        path.write_text(path.read_text() + "\nCENTRAL_USERNAME=forbidden\n")
        self.assert_rejected("release script retains CENTRAL_USERNAME")


if __name__ == "__main__":
    unittest.main()
