#!/usr/bin/env python3
"""Regression tests for Hibernate Search JMH chart conversion."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
SCRIPT = SCRIPT_DIR / "convert-jmh-search-performance.py"
SPEC = importlib.util.spec_from_file_location("convert_jmh_search_performance", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class SearchPerformanceConverterTest(unittest.TestCase):

    def test_emits_profile_timing_footprint_and_quality_series(self) -> None:
        converted = CONVERTER.convert(self.profile_results("content-v1", 20.0, 50.0))

        self.assertEqual(12, len(converted))
        by_name = {entry["name"]: entry for entry in converted}
        self.assertIn(
            "Hibernate Search full-text query — content-v1 / Entity hydration", by_name
        )
        self.assertIn(
            "Hibernate Search full-text query — content-v1 / Lucene projection", by_name
        )
        self.assertIn(
            "Hibernate Search content query — content-v1 / Lucene projection", by_name
        )
        self.assertEqual(
            "bytes",
            by_name["Hibernate Search index footprint — content-v1 / Lucene"]["unit"],
        )
        self.assertEqual(
            123456.0,
            by_name["Hibernate Search index footprint — content-v1 / Lucene"]["value"],
        )
        self.assertEqual(
            "segments",
            by_name["Hibernate Search segment count — content-v1 / Lucene"]["unit"],
        )
        self.assertEqual(
            3.0,
            by_name["Hibernate Search segment count — content-v1 / Lucene"]["value"],
        )
        self.assertEqual(
            0.0,
            by_name["Hibernate Search content quality — content-v1 / miss rate"]["value"],
        )
        self.assertEqual(
            0.0,
            by_name["Hibernate Search path quality — content-v1 / miss rate"]["value"],
        )
        timing = [entry for entry in converted if entry["unit"] == "ms/op"]
        self.assertEqual(7, len(timing))
        self.assertTrue(all("Profile: content-v1" in entry["extra"] for entry in timing))

    def test_uses_aux_counter_raw_data_instead_of_events_sum(self) -> None:
        converted = CONVERTER.convert(self.profile_results("content-v1", 10.0, 25.0))
        by_name = {entry["name"]: entry for entry in converted}

        # The synthetic JMH score is the three-iteration EVENTS sum, but conversion must use
        # the per-measurement rawData mean. Otherwise 10+10+10 would be incorrectly treated as
        # 30 hits and clamped to the expected 20, hiding a 50% miss rate.
        self.assertEqual(
            50.0,
            by_name["Hibernate Search content quality — content-v1 / miss rate"]["value"],
        )
        self.assertEqual(
            50.0,
            by_name["Hibernate Search path quality — content-v1 / miss rate"]["value"],
        )
        self.assertEqual(
            123456.0,
            by_name["Hibernate Search index footprint — content-v1 / Lucene"]["value"],
        )
        self.assertEqual(
            654321.0,
            by_name[
                "Hibernate Search SQL projection footprint — content-v1 / PostgreSQL"
            ]["value"],
        )

    def test_keeps_profiles_distinct_and_exposes_intentional_recall_tradeoffs(self) -> None:
        results = self.profile_results("content-v1", 20.0, 50.0)
        results += self.profile_results("metadata-v1", 0.0, 0.0)
        converted = CONVERTER.convert(results)

        self.assertEqual(24, len(converted))
        by_name = {entry["name"]: entry for entry in converted}
        self.assertEqual(
            100.0,
            by_name["Hibernate Search content quality — metadata-v1 / miss rate"]["value"],
        )
        self.assertEqual(
            100.0,
            by_name["Hibernate Search path quality — metadata-v1 / miss rate"]["value"],
        )
        self.assertIn(
            "Hibernate Search indexing — metadata-v1 / Batched incremental indexing",
            by_name,
        )
        self.assertIn(
            "Hibernate Search indexing — content-v1 / Batched incremental indexing",
            by_name,
        )

    def test_missing_series_for_one_profile_is_rejected(self) -> None:
        results = self.profile_results("content-v1", 20.0, 50.0)
        results = [
            result
            for result in results
            if not result["benchmark"].endswith("projectionRebuild")
        ]
        with self.assertRaisesRegex(ValueError, "Missing Search benchmark series"):
            CONVERTER.convert(results)

    def test_missing_footprint_counter_is_rejected(self) -> None:
        results = self.profile_results("content-v1", 20.0, 50.0)
        source = next(
            result
            for result in results
            if result["benchmark"].endswith("fullTextSummaryHits")
        )
        del source["secondaryMetrics"]["luceneBytes"]
        with self.assertRaisesRegex(ValueError, "luceneBytes"):
            CONVERTER.convert(results)

    def test_missing_aux_counter_raw_data_is_rejected(self) -> None:
        results = self.profile_results("content-v1", 20.0, 50.0)
        source = next(
            result
            for result in results
            if result["benchmark"].endswith("fullTextSummaryHits")
        )
        del source["secondaryMetrics"]["luceneBytes"]["rawData"]
        with self.assertRaisesRegex(ValueError, "rawData"):
            CONVERTER.convert(results)

    def profile_results(
        self, profile: str, content_hits: float, path_hits: float
    ) -> list[dict]:
        results: list[dict] = []
        for score, method in enumerate(CONVERTER.SERIES, start=1):
            result_count = 10.0
            if method == "contentOnlySummaryHits":
                result_count = content_hits
            elif method == "pathTermsLucene":
                result_count = path_hits
            results.append(self.result(method, float(score), profile, result_count))
        return results

    @staticmethod
    def event_metric(per_invocation: float, spread: float = 0.0) -> dict:
        samples = [per_invocation - spread, per_invocation, per_invocation + spread]
        return {
            # JMH AuxCounters(Type.EVENTS) aggregates these three measurement values in score.
            "score": sum(samples),
            "scoreError": float("nan"),
            "scoreUnit": "#/op",
            "rawData": [samples],
        }

    @classmethod
    def result(cls, method: str, score: float, profile: str, result_count: float) -> dict:
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "HibernateSearchPerformanceBenchmark."
                + method
            ),
            "mode": "ss",
            "forks": 1,
            "jdkVersion": "21",
            "params": {
                "commitCount": "100",
                "queryLimit": "50",
                "indexProfile": profile,
            },
            "primaryMetric": {
                "score": score,
                "scoreError": 0.25,
                "scoreUnit": "ms/op",
            },
            "secondaryMetrics": {
                "resultCount": cls.event_metric(result_count),
                "luceneBytes": cls.event_metric(123456.0, 100.0),
                "sqlProjectionBytes": cls.event_metric(654321.0),
                "segmentCount": cls.event_metric(3.0),
            },
        }


if __name__ == "__main__":
    unittest.main()
