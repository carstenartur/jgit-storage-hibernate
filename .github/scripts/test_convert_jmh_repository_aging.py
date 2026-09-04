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

        comparison = next(
            entry
            for entry in report["comparison"]
            if "reopenAndLookupOldest" not in entry["name"]
            and "Reopen and oldest-object lookup" in entry["name"]
            and entry["name"].endswith("compact-only")
        )
        self.assertIn("p50/p95/p99:", comparison["extra"])

    def test_event_counters_use_raw_iteration_values_instead_of_sum(self) -> None:
        results = [
            self.result("reopenAndLookupOldest", "none", 15.0, 0, 0),
            self.result("reopenAndLookupOldest", "compact-only", 5.0, 70, 8),
            self.result("reopenAndLookupOldest", "read-optimized", 6.0, 100, 8),
        ]
        for result in results:
            self.as_three_iteration_events(result)

        report = CONVERTER.convert(results)
        compact = next(
            row
            for row in report["policyEvidence"]
            if row["maintenanceMode"] == "compact-only"
        )

        self.assertEqual(2, compact["activePacks"])
        self.assertEqual(100000, compact["storedExtensionBytes"])
        self.assertEqual(70.0, compact["maintenanceElapsedMillis"])
        self.assertEqual(8, compact["maintenancePackReduction"])
        self.assertEqual(7, compact["breakEvenReads"])
        self.assertEqual(7, report["recommendations"][0]["breakEvenReads"])

    def test_secondary_p50_is_used_when_raw_data_is_not_retained(self) -> None:
        result = self.result("reopenAndLookupOldest", "compact-only", 5.0, 70, 8)
        metric = result["secondaryMetrics"]["AgingCounters.maintenanceElapsedMillis"]
        metric["score"] = 210
        metric["scorePercentiles"] = {"50.0": 70}

        self.assertEqual(
            70.0,
            CONVERTER._metric_score(result, "maintenanceElapsedMillis"),
        )

    def test_non_finite_raw_counter_is_rejected(self) -> None:
        result = self.result("reopenAndLookupOldest", "compact-only", 5.0, 70, 8)
        metric = result["secondaryMetrics"]["AgingCounters.maintenanceElapsedMillis"]
        metric["rawData"] = [[70.0, float("nan")]]

        with self.assertRaisesRegex(ValueError, "not finite"):
            CONVERTER._metric_score(result, "maintenanceElapsedMillis")

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

    def test_missing_percentile_is_rejected(self) -> None:
        result = self.result("lookupOldestObject", "none", 1.0, 0, 0)
        result["primaryMetric"]["scorePercentiles"].pop("95.0")
        with self.assertRaisesRegex(ValueError, "missing the p95.0 JMH percentile"):
            CONVERTER.convert([result])

    @staticmethod
    def as_three_iteration_events(result: dict) -> None:
        for metric in result["secondaryMetrics"].values():
            value = metric["score"]
            metric["score"] = value * 3
            metric["scorePercentiles"] = {"50.0": value}
            metric["rawData"] = [[value, value, value]]

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
                "scorePercentiles": {
                    "50.0": score,
                    "95.0": score * 1.1,
                    "99.0": score * 1.2,
                },
            },
            "secondaryMetrics": {
                f"AgingCounters.{key}": {"score": value, "scoreUnit": "#"}
                for key, value in secondary.items()
            },
        }


if __name__ == "__main__":
    unittest.main()
