#!/usr/bin/env python3
"""Regression tests for exact consumer dependency resolution."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-consumer-dependency-tree.py")
SPEC = importlib.util.spec_from_file_location("verify_consumer_dependency_tree", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
VERIFY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFY
SPEC.loader.exec_module(VERIFY)


class ConsumerDependencyTreeVerificationTest(unittest.TestCase):

    def test_extracts_normal_and_classifier_coordinates(self) -> None:
        tree = """
[INFO] +- io.github.carstenartur:jgit-storage-hibernate-core:jar:0.9.1-SNAPSHOT:compile
[INFO] \\- io.github.carstenartur:jgit-storage-hibernate-search:jar:tests:0.9.1-SNAPSHOT:test
"""
        resolved = VERIFY.coordinates(tree)
        self.assertEqual(2, len(resolved))
        self.assertEqual("jgit-storage-hibernate-core", resolved[0]["artifact"])
        self.assertEqual("tests", resolved[1]["classifier"])

    def test_mixed_versions_are_rejected(self) -> None:
        resolved = [
            {
                "artifact": "jgit-storage-hibernate-core",
                "packaging": "jar",
                "classifier": "",
                "version": "0.9.1-SNAPSHOT",
                "scope": "compile",
            },
            {
                "artifact": "jgit-storage-hibernate-search",
                "packaging": "jar",
                "classifier": "",
                "version": "0.9.0",
                "scope": "compile",
            },
        ]
        with self.assertRaisesRegex(ValueError, "Expected every library module"):
            VERIFY.verify(resolved, "0.9.1-SNAPSHOT", set())

    def test_forbidden_benchmark_artifact_is_rejected(self) -> None:
        resolved = [
            {
                "artifact": "jgit-storage-hibernate-benchmarks",
                "packaging": "jar",
                "classifier": "",
                "version": "0.9.1-SNAPSHOT",
                "scope": "compile",
            }
        ]
        with self.assertRaisesRegex(ValueError, "Forbidden consumer dependency"):
            VERIFY.verify(
                resolved,
                "0.9.1-SNAPSHOT",
                {"jgit-storage-hibernate-benchmarks"},
            )

    def test_missing_library_dependency_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "No jgit-storage-hibernate"):
            VERIFY.verify([], "0.9.1-SNAPSHOT", set())


if __name__ == "__main__":
    unittest.main()
