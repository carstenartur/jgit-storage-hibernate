#!/usr/bin/env python3
"""Regression tests for consumer contract POM preparation."""

from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("prepare-consumer-contract.py")
SPEC = importlib.util.spec_from_file_location("prepare_consumer_contract", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class ConsumerContractPreparationTest(unittest.TestCase):

    def test_literal_and_property_versions_change_without_reformatting_poms(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent = root / "pom.xml"
            child = root / "module" / "pom.xml"
            child.parent.mkdir()
            parent.write_text(
                """<project>
  <properties>
    <jgit.storage.version>0.9.0</jgit.storage.version>
    <unrelated.version>0.9.0</unrelated.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.carstenartur</groupId>
        <artifactId>jgit-storage-hibernate-search</artifactId>
        <version>${jgit.storage.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
""",
                encoding="utf-8",
            )
            child.write_text(
                """<project>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>0.9.0</version>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>
    </dependency>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>unrelated</artifactId>
      <version>0.9.0</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )

            poms = PREPARE.pom_files(root)
            uses = [use for pom in poms for use in PREPARE.dependency_uses(pom, root)]
            literal_count = sum(
                PREPARE.replace_literal_versions(pom, "0.9.1-SNAPSHOT") for pom in poms
            )
            property_changes = PREPARE.replace_property(
                poms, root, "jgit.storage.version", "0.9.1-SNAPSHOT"
            )

            self.assertEqual(1, literal_count)
            self.assertEqual(1, len(property_changes))
            self.assertEqual(
                {"jgit-storage-hibernate-core", "jgit-storage-hibernate-search"},
                {use.artifact for use in uses},
            )
            self.assertIn(
                "<jgit.storage.version>0.9.1-SNAPSHOT</jgit.storage.version>",
                parent.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "<unrelated.version>0.9.0</unrelated.version>",
                parent.read_text(encoding="utf-8"),
            )
            child_text = child.read_text(encoding="utf-8")
            self.assertIn("<version>0.9.1-SNAPSHOT</version>", child_text)
            self.assertIn("<groupId>org.example</groupId>", child_text)
            self.assertIn("<version>0.9.0</version>", child_text)

    def test_main_writes_a_machine_readable_contract_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "target" / "consumer-contract.json"
            (root / "pom.xml").write_text(
                """<project>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
      <version>0.9.0</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )
            previous = os.sys.argv
            os.sys.argv = [
                str(SCRIPT),
                "--consumer",
                "sandbox",
                "--root",
                str(root),
                "--version",
                "0.9.1-SNAPSHOT",
                "--report",
                str(report),
            ]
            try:
                PREPARE.main()
            finally:
                os.sys.argv = previous

            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("sandbox", data["consumer"])
            self.assertEqual("0.9.1-SNAPSHOT", data["targetVersion"])
            self.assertEqual(
                ["jgit-storage-hibernate-java-analysis"], data["modules"]
            )
            self.assertEqual(1, data["literalReplacements"])

    def test_non_consumer_fails_instead_of_silently_passing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(
                """<project>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>unrelated</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )
            uses = [
                use
                for pom in PREPARE.pom_files(root)
                for use in PREPARE.dependency_uses(pom, root)
            ]
            self.assertEqual([], uses)


if __name__ == "__main__":
    unittest.main()
