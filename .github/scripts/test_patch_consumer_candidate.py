#!/usr/bin/env python3
"""Regression tests for patch-consumer-candidate.py."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("patch-consumer-candidate.py")
SPEC = importlib.util.spec_from_file_location("patch_consumer_candidate", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ConsumerCandidatePatcherTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, relative: str, content: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_patches_property_backed_versions_without_touching_other_properties(self) -> None:
        pom = self.write(
            "pom.xml",
            """<project>
  <properties>
    <jgit-storage-hibernate.version>0.1.18</jgit-storage-hibernate.version>
    <other.version>9</other.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>${jgit-storage-hibernate.version}</version>
    </dependency>
    <dependency>
      <groupId>example</groupId><artifactId>other</artifactId><version>${other.version}</version>
    </dependency>
  </dependencies>
</project>
""",
        )

        report = MODULE.patch(self.root, "0.9.1-SNAPSHOT")
        text = pom.read_text(encoding="utf-8")
        self.assertIn(
            "<jgit-storage-hibernate.version>0.9.1-SNAPSHOT</jgit-storage-hibernate.version>",
            text,
        )
        self.assertIn("<other.version>9</other.version>", text)
        self.assertEqual(["jgit-storage-hibernate-core"], report["modules"])
        self.assertEqual(1, len(report["propertyChanges"]))

    def test_patches_literal_dependency_and_dependency_management_versions(self) -> None:
        pom = self.write(
            "module/pom.xml",
            """<project>
  <dependencyManagement><dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-search</artifactId>
      <version>0.1.18</version>
    </dependency>
  </dependencies></dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
      <version>0.1.18</version>
    </dependency>
  </dependencies>
</project>""",
        )

        report = MODULE.patch(self.root, "0.9.1-SNAPSHOT")
        text = pom.read_text(encoding="utf-8")
        self.assertEqual(2, text.count("<version>0.9.1-SNAPSHOT</version>"))
        self.assertEqual(
            ["jgit-storage-hibernate-core", "jgit-storage-hibernate-search"],
            report["modules"],
        )

    def test_fails_when_consumer_does_not_use_library(self) -> None:
        self.write(
            "pom.xml",
            "<project><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version></dependency></dependencies></project>",
        )
        with self.assertRaisesRegex(MODULE.PatchError, "declares no"):
            MODULE.patch(self.root, "0.9.1-SNAPSHOT")

    def test_fails_for_unresolved_version_property(self) -> None:
        self.write(
            "pom.xml",
            """<project><dependencies><dependency>
<groupId>io.github.carstenartur</groupId>
<artifactId>jgit-storage-hibernate-core</artifactId>
<version>${missing.version}</version>
</dependency></dependencies></project>""",
        )
        with self.assertRaisesRegex(MODULE.PatchError, "Could not resolve"):
            MODULE.patch(self.root, "0.9.1-SNAPSHOT")


if __name__ == "__main__":
    unittest.main()
