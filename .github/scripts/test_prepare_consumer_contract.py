#!/usr/bin/env python3
"""Regression tests for consumer contract POM preparation."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("prepare-consumer-contract.py")
SPEC = importlib.util.spec_from_file_location("prepare_consumer_contract", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
PREPARE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PREPARE
SPEC.loader.exec_module(PREPARE)


class ConsumerContractPreparationTest(unittest.TestCase):

    def run_main(
        self, root: Path, report: Path, consumer: str = "sandbox"
    ) -> None:
        previous = os.sys.argv
        os.sys.argv = [
            str(SCRIPT),
            "--consumer",
            consumer,
            "--root",
            str(root),
            "--version",
            "0.9.1-consumer-deadbeef-SNAPSHOT",
            "--report",
            str(report),
        ]
        try:
            PREPARE.main()
        finally:
            os.sys.argv = previous

    def test_literal_and_exclusive_property_versions_change_without_reformatting(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "target" / "consumer-contract.json"
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

            self.run_main(root, report, consumer="audio-analyzer")

            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(1, data["literalReplacements"])
            self.assertEqual(1, len(data["propertyChanges"]))
            self.assertEqual(
                {
                    "jgit-storage-hibernate-core",
                    "jgit-storage-hibernate-search",
                },
                set(data["modules"]),
            )
            self.assertEqual(
                [
                    {
                        "property_name": "jgit.storage.version",
                        "target_references": 1,
                        "total_version_references": 1,
                        "definitions": 1,
                    }
                ],
                data["propertySafety"],
            )
            self.assertIn(
                "<jgit.storage.version>0.9.1-consumer-deadbeef-SNAPSHOT"
                "</jgit.storage.version>",
                parent.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "<unrelated.version>0.9.0</unrelated.version>",
                parent.read_text(encoding="utf-8"),
            )
            child_text = child.read_text(encoding="utf-8")
            self.assertIn(
                "<version>0.9.1-consumer-deadbeef-SNAPSHOT</version>",
                child_text,
            )
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

            self.run_main(root, report)

            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("sandbox", data["consumer"])
            self.assertEqual(
                "0.9.1-consumer-deadbeef-SNAPSHOT", data["targetVersion"]
            )
            self.assertEqual(
                ["jgit-storage-hibernate-java-analysis"], data["modules"]
            )
            self.assertEqual(1, data["literalReplacements"])

    def test_reserved_project_identity_property_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "contract.json"
            (root / "pom.xml").write_text(
                """<project>
  <version>${revision}</version>
  <properties><revision>1.0.0-SNAPSHOT</revision></properties>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>${revision}</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                SystemExit, "reserved project identity property 'revision'"
            ):
                self.run_main(root, report)

            self.assertIn(
                "<revision>1.0.0-SNAPSHOT</revision>",
                (root / "pom.xml").read_text(encoding="utf-8"),
            )

    def test_property_shared_with_unrelated_dependency_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "contract.json"
            (root / "pom.xml").write_text(
                """<project>
  <properties><stack.version>1.0.0</stack.version></properties>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>${stack.version}</version>
    </dependency>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>shared-stack-member</artifactId>
      <version>${stack.version}</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                SystemExit, "shared outside io.github.carstenartur"
            ):
                self.run_main(root, report)

            self.assertIn(
                "<stack.version>1.0.0</stack.version>",
                (root / "pom.xml").read_text(encoding="utf-8"),
            )

    def test_duplicate_property_definitions_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "contract.json"
            (root / "pom.xml").write_text(
                """<project>
  <properties><jgit.version>1.0.0</jgit.version></properties>
  <profiles>
    <profile>
      <properties><jgit.version>2.0.0</jgit.version></properties>
    </profile>
  </profiles>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>${jgit.version}</version>
    </dependency>
  </dependencies>
</project>
""",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                SystemExit, "exactly one deterministic definition"
            ):
                self.run_main(root, report)

    def test_non_consumer_has_no_matching_uses(self) -> None:
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
