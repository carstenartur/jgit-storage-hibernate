#!/usr/bin/env python3
"""Regression tests for repository-aging policy evidence conversion."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("convert-jmh-repository-aging.py")
SPEC = importlib.util.spec_from_file_location("convert_jmh_repository_aging", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class RepositoryAgingConverterTest(unittest.TestCase):

    def test_break_even_and_no_maintenance_recommendations_are_derived(self) -> None:
        results = []
        for operation, baseline, compact, optimized in (
            ("reopenAndLookupOldest", 15.0, 5.0, 6.0),
            ("lookupOldestObject", 1.0, 1.2, 1.1),
        ):
            results.extend(
                [
                    self.result(operation, "none", baseline, 0, 0),
                    self.result(operation, "compact-only", compact, 70, 8),
                    self.result(operation, "read-optimized", optimized, 100, 8),
                ]
            )
        report = CONVERTER.convert(results)
        candidate = report["recommendations"][0]
        self.assertEqual("candidate", candidate["status"])
        self.assertEqual("compact-only", candidate["maintenanceMode"])
        self.assertEqual("reopenAndLookupOldest", candidate["operation"])
        self.assertEqual(7, candidate["breakEvenReads"])
        self.assertEqual("unsupported-by-selected-jgit-dfs", report["midx"]["decision"])

    def test_missing_baseline_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Missing no-maintenance baseline"):
            CONVERTER.convert(
                [self.result("lookupOldestObject", "compact-only", 1.0, 10, 2)]
            )

    def test_missing_maintenance_mode_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Missing 'read-optimized' result"):
            CONVERTER.convert(
                [
                    self.result("lookupOldestObject", "none", 1.0, 0, 0),
                    self.result("lookupOldestObject", "compact-only", 0.8, 10, 2),
                ]
            )

    @staticmethod
    def result(
        operation: str,
        mode: str,
        score: float,
        maintenance_ms: int,
        pack_reduction: int,
    ) -> dict:
        secondary = {
            "activePacks": 10 if mode == "none" else 2,
            "packPayloadBytes": 90000,
            "packIndexBytes": 8000,
            "smallPacks": 10 if mode == "none" else 2,
            "smallPackRatioBasisPoints": 10000,
            "storedExtensionBytes": 100000,
            "unreachableLogicalBytes": 8250,
            "maintenanceElapsedMillis": maintenance_ms,
            "maintenanceStoredByteDelta": -8000,
            "maintenancePackReduction": pack_reduction,
            "jgitDfsMidxExtensionAvailable": 0,
        }
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "RepositoryAgingBenchmark."
                + operation
            ),
            "mode": "sample",
            "jdkVersion": "21",
            "params": {
                "backend": "postgresql",
                "pushes": "10",
                "cacheState": "cold",
                "maintenanceMode": mode,
            },
            "primaryMetric": {
                "score": score,
                "scoreError": 0.1,
                "scoreUnit": "ms/op",
            },
            "secondaryMetrics": {
                f"AgingCounters.{key}": {"score": value, "scoreUnit": "#"}
                for key, value in secondary.items()
            },
        }


if __name__ == "__main__":
    unittest.main()
