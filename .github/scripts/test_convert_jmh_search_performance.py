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

    def test_groups_alternative_query_implementations_into_stable_operations(self) -> None:
        results = [self.result(method, score) for method, score in enumerate(
            CONVERTER.SERIES, start=1
        )]
        converted = CONVERTER.convert(results)

        self.assertEqual(6, len(converted))
        by_name = {entry["name"]: entry for entry in converted}
        self.assertIn(
            "Hibernate Search full-text query — Entity hydration", by_name
        )
        self.assertIn(
            "Hibernate Search full-text query — Lucene projection", by_name
        )
        self.assertIn(
            "Hibernate Search path query — SQL literal fragment", by_name
        )
        self.assertIn(
            "Hibernate Search path query — Lucene analyzed terms", by_name
        )
        self.assertTrue(all(entry["unit"] == "ms/op" for entry in converted))
        self.assertTrue(all("Commits: 100" in entry["extra"] for entry in converted))

    def test_missing_series_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Missing Search benchmark series"):
            CONVERTER.convert(
                [self.result("fullTextSummaryHits", 1.0)]
            )

    @staticmethod
    def result(method: str, score: float) -> dict:
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "HibernateSearchPerformanceBenchmark."
                + method
            ),
            "mode": "ss",
            "forks": 1,
            "jdkVersion": "21",
            "params": {"commitCount": "100", "queryLimit": "50"},
            "primaryMetric": {
                "score": float(score),
                "scoreError": 0.25,
                "scoreUnit": "ms/op",
            },
        }


if __name__ == "__main__":
    unittest.main()
