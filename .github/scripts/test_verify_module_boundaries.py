#!/usr/bin/env python3
"""Regression tests for the published module capability graph."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-module-boundaries.py")
SPEC = importlib.util.spec_from_file_location("verify_module_boundaries", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
BOUNDARIES = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BOUNDARIES
SPEC.loader.exec_module(BOUNDARIES)


class ModuleBoundaryVerificationTest(unittest.TestCase):

    def dependency(
        self,
        module: str,
        artifact: str,
        *,
        group: str = BOUNDARIES.GROUP_ID,
        scope: str = "compile",
        optional: bool = False,
    ):
        return BOUNDARIES.Dependency(
            module=module,
            group_id=group,
            artifact_id=artifact,
            scope=scope,
            optional=optional,
            dependency_type="jar",
        )

    def test_expected_downward_capabilities_are_allowed(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-search",
                    "jgit-storage-hibernate-core",
                ),
                self.dependency(
                    "jgit-storage-hibernate-java-analysis",
                    "jgit-storage-hibernate-search",
                ),
                self.dependency(
                    "jgit-storage-hibernate-architecture",
                    "jgit-storage-hibernate-java-analysis",
                ),
                self.dependency(
                    "jgit-storage-hibernate-benchmarks",
                    "jgit-storage-hibernate-architecture",
                ),
            ]
        )
        self.assertEqual([], report["violations"])

    def test_core_to_search_dependency_is_rejected(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-core",
                    "jgit-storage-hibernate-search",
                )
            ]
        )
        self.assertTrue(any("depends upward" in item for item in report["violations"]))

    def test_production_benchmark_dependency_is_rejected(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-search",
                    "jgit-storage-hibernate-benchmarks",
                )
            ]
        )
        self.assertTrue(any("benchmark artifact" in item for item in report["violations"]))

    def test_unavoidable_core_database_driver_is_rejected(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-core",
                    "postgresql",
                    group="org.postgresql",
                )
            ]
        )
        self.assertTrue(any("forces database driver" in item for item in report["violations"]))

    def test_test_scoped_driver_is_allowed(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-core",
                    "postgresql",
                    group="org.postgresql",
                    scope="test",
                )
            ]
        )
        self.assertEqual([], report["violations"])

    def test_cycle_is_rejected(self) -> None:
        report = BOUNDARIES.verify(
            [
                self.dependency(
                    "jgit-storage-hibernate-search",
                    "jgit-storage-hibernate-core",
                ),
                self.dependency(
                    "jgit-storage-hibernate-core",
                    "jgit-storage-hibernate-search",
                ),
            ]
        )
        self.assertTrue(any("cycle" in item.lower() for item in report["violations"]))


if __name__ == "__main__":
    unittest.main()
