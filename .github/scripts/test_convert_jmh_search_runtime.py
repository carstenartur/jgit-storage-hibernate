#!/usr/bin/env python3
"""Regression tests for Hibernate Search runtime tuning chart conversion."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
SCRIPT = SCRIPT_DIR / "convert-jmh-search-runtime.py"
SPEC = importlib.util.spec_from_file_location("convert_jmh_search_runtime", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class SearchRuntimeConverterTest(unittest.TestCase):

    def test_emits_timing_visibility_query_and_database_series(self) -> None:
        converted = CONVERTER.convert(self.scenario_results("sync-write-sync-r500"))
        by_name = {entry["name"]: entry for entry in converted}

        self.assertEqual(21, len(converted))
        self.assertEqual(
            12.0,
            by_name[
                "Hibernate Search runtime burst ready p50 — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            18.0,
            by_name[
                "Hibernate Search runtime burst ready p95 — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            18.0,
            by_name[
                "Hibernate Search runtime burst ready p99 — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            6.0,
            by_name[
                "Hibernate Search runtime burst visibility observation wait — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            11.0,
            by_name[
                "Hibernate Search runtime burst visibility polls — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            2.0,
            by_name["Hibernate Search concurrent query p95 — sync-write-sync-r500"][
                "value"
            ],
        )
        self.assertEqual(
            5.0,
            by_name[
                "Hibernate Search runtime burst transactions — sync-write-sync-r500"
            ]["value"],
        )
        self.assertEqual(
            "ms/op",
            by_name[
                "Hibernate Search runtime burst visibility observation wait — sync-write-sync-r500"
            ]["unit"],
        )
        self.assertEqual(
            "ms/op",
            by_name["Hibernate Search concurrent query p95 — sync-write-sync-r500"][
                "unit"
            ],
        )
        self.assertEqual(
            "count/op",
            by_name[
                "Hibernate Search runtime burst visibility polls — sync-write-sync-r500"
            ]["unit"],
        )
        self.assertEqual(
            "count/op",
            by_name[
                "Hibernate Search runtime burst transactions — sync-write-sync-r500"
            ]["unit"],
        )
        self.assertIn(
            "sync=write-sync; refresh=500ms",
            by_name[
                "Hibernate Search runtime burst ready p50 — sync-write-sync-r500"
            ]["extra"],
        )
        self.assertIn(
            "lower is better",
            by_name[
                "Hibernate Search runtime burst ready p50 — sync-write-sync-r500"
            ]["extra"],
        )

    def test_keeps_runtime_scenarios_in_same_stable_operations(self) -> None:
        results = self.scenario_results("reference")
        results += self.scenario_results("batch-250")
        converted = CONVERTER.convert(results)
        names = {entry["name"] for entry in converted}

        self.assertIn("Hibernate Search runtime burst submission p50 — reference", names)
        self.assertIn("Hibernate Search runtime burst submission p50 — batch-250", names)
        self.assertIn("Hibernate Search concurrent query p99 — reference", names)
        self.assertIn("Hibernate Search concurrent query p99 — batch-250", names)
        self.assertIn("Hibernate Search runtime burst visibility polls — reference", names)

    def test_missing_operation_is_rejected(self) -> None:
        results = [
            result
            for result in self.scenario_results("reference")
            if not result["benchmark"].endswith("projectionRebuildReady")
        ]
        with self.assertRaisesRegex(ValueError, "Missing Search runtime series"):
            CONVERTER.convert(results)

    def test_missing_aux_counter_raw_data_is_rejected(self) -> None:
        results = self.scenario_results("reference")
        ready = next(
            result
            for result in results
            if result["benchmark"].endswith("incrementalBurstReady")
        )
        del ready["secondaryMetrics"]["visibilityPolls"]["rawData"]
        with self.assertRaisesRegex(ValueError, "rawData"):
            CONVERTER.convert(results)

    def test_unknown_scenario_is_rejected(self) -> None:
        results = self.scenario_results("reference")
        for result in results:
            result["params"]["runtimeScenario"] = "mystery"
        with self.assertRaisesRegex(ValueError, "Unknown runtime scenario"):
            CONVERTER.convert(results)

    @classmethod
    def scenario_results(cls, scenario: str) -> list[dict]:
        return [
            cls.result("incrementalBurstSubmission", scenario, [4.0, 6.0]),
            cls.result("incrementalBurstReady", scenario, [12.0, 18.0]),
            cls.result("steadyQueriesDuringBurst", scenario, [20.0, 24.0]),
            cls.result("projectionRebuildReady", scenario, [30.0, 36.0]),
        ]

    @classmethod
    def result(cls, method: str, scenario: str, primary_samples: list[float]) -> dict:
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "HibernateSearchRuntimeTuningBenchmark."
                + method
            ),
            "mode": "ss",
            "forks": 1,
            "jdkVersion": "21",
            "params": {
                "commitCount": "100",
                "burstCount": "50",
                "runtimeScenario": scenario,
            },
            "primaryMetric": {
                "score": sum(primary_samples) / len(primary_samples),
                "scoreError": 1.0,
                "scoreUnit": "ms/op",
                "rawData": [primary_samples],
            },
            "secondaryMetrics": {
                "visibilityWaitMicros": cls.counter([5_000.0, 7_000.0]),
                "visibilityPolls": cls.counter([10.0, 12.0]),
                "preparedStatements": cls.counter([10.0, 12.0]),
                "transactions": cls.counter([5.0, 5.0]),
                "queryP50Micros": cls.counter([1_000.0, 1_200.0]),
                "queryP95Micros": cls.counter([2_000.0, 2_000.0]),
                "queryP99Micros": cls.counter([3_000.0, 3_400.0]),
            },
        }

    @staticmethod
    def counter(samples: list[float]) -> dict:
        return {
            "score": sum(samples),
            "scoreError": float("nan"),
            "scoreUnit": "#/op",
            "rawData": [samples],
        }


if __name__ == "__main__":
    unittest.main()
