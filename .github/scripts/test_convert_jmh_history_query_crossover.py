#!/usr/bin/env python3
"""Regression tests for history-query crossover JMH conversion."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
SCRIPT = SCRIPT_DIR / "convert-jmh-history-query-crossover.py"
SPEC = importlib.util.spec_from_file_location("convert_jmh_history_query_crossover", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class HistoryQueryCrossoverConverterTest(unittest.TestCase):

    def test_emits_all_engines_build_cost_and_break_even(self) -> None:
        converted = CONVERTER.convert(self.complete_results())
        self.assertEqual(16, len(converted))
        by_name = {entry["name"]: entry for entry in converted}

        build = by_name[
            "Git history indexed projection build — 1,000 commits / content-v1"
        ]
        self.assertEqual("ms/op", build["unit"])
        self.assertEqual(100.0, build["value"])

        indexed = by_name[
            "Git history path + changed-content query — 1,000 commits / "
            "Hibernate Search path + content projection"
        ]
        self.assertEqual(2.0, indexed["value"])
        self.assertIn("Speedup vs FileRepository/JGit: 25.00x", indexed["extra"])
        self.assertIn(
            "Break-even vs FileRepository/JGit including one projection build: 2.08 queries",
            indexed["extra"],
        )
        self.assertIn("Commits visited on demand: 0", indexed["extra"])

        filesystem = by_name[
            "Git history path + changed-content query — 1,000 commits / "
            "FileRepository / JGit on demand"
        ]
        self.assertIn("Commits visited on demand: 900", filesystem["extra"])
        self.assertIn("Changed blobs read: 4", filesystem["extra"])

    def test_accepts_bounded_large_query_subset(self) -> None:
        results = [self.build_result()]
        for query_kind in ("path-content", "compound"):
            results.extend(
                [
                    self.query_result(query_kind, "filesystem-jgit", 50.0, 900.0, 4.0),
                    self.query_result(query_kind, "hibernate-jgit", 60.0, 900.0, 4.0),
                    self.query_result(query_kind, "indexed-projection", 2.0, 0.0, 0.0),
                ]
            )
        converted = CONVERTER.convert(results)
        self.assertEqual(7, len(converted))
        names = {entry["name"] for entry in converted}
        self.assertTrue(any("path + changed-content" in name for name in names))
        self.assertTrue(any("compound audit" in name for name in names))
        self.assertFalse(any("author + time" in name for name in names))

    def test_reports_no_break_even_when_projection_query_is_slower(self) -> None:
        results = self.complete_results()
        for result in results:
            if (
                result["benchmark"].endswith("query")
                and result["params"].get("queryKind") == "author-time"
                and result["params"].get("engine") == "indexed-projection"
            ):
                result["primaryMetric"]["score"] = 80.0
        converted = CONVERTER.convert(results)
        indexed = next(
            entry
            for entry in converted
            if entry["name"].startswith("Git history author + time query")
            and "PostgreSQL compact projection" in entry["name"]
        )
        self.assertIn("not reached", indexed["extra"])

    def test_missing_engine_is_rejected(self) -> None:
        results = self.complete_results()
        results = [
            result
            for result in results
            if not (
                result["benchmark"].endswith("query")
                and result["params"].get("queryKind") == "compound"
                and result["params"].get("engine") == "hibernate-jgit"
            )
        ]
        with self.assertRaisesRegex(ValueError, "Missing engines"):
            CONVERTER.convert(results)

    def test_missing_projection_build_is_rejected(self) -> None:
        results = [
            result
            for result in self.complete_results()
            if not result["benchmark"].endswith("projectionBuild")
        ]
        with self.assertRaisesRegex(ValueError, "build and query"):
            CONVERTER.convert(results)

    def complete_results(self) -> list[dict]:
        results = [self.build_result()]
        for query_kind in CONVERTER.QUERY_TITLES:
            results.extend(
                [
                    self.query_result(query_kind, "filesystem-jgit", 50.0, 900.0, 4.0),
                    self.query_result(query_kind, "hibernate-jgit", 60.0, 900.0, 4.0),
                    self.query_result(query_kind, "indexed-projection", 2.0, 0.0, 0.0),
                ]
            )
        return results

    @staticmethod
    def event_metric(value: float) -> dict:
        return {
            "score": value * 3,
            "scoreError": float("nan"),
            "scoreUnit": "#/op",
            "rawData": [[value, value, value]],
        }

    @classmethod
    def counters(
        cls, results: float, commits: float, blobs: float, prepared: float = 0.0
    ) -> dict:
        return {
            "resultCount": cls.event_metric(results),
            "commitsVisited": cls.event_metric(commits),
            "treeInspections": cls.event_metric(commits / 2.0),
            "blobsRead": cls.event_metric(blobs),
            "blobBytes": cls.event_metric(blobs * 512.0),
            "queryExecutions": cls.event_metric(0.0),
            "preparedStatements": cls.event_metric(prepared),
            "transactions": cls.event_metric(1.0 if prepared else 0.0),
        }

    @classmethod
    def query_result(
        cls, query_kind: str, engine: str, score: float, commits: float, blobs: float
    ) -> dict:
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "HistoryQueryCrossoverBenchmark.query"
            ),
            "mode": "ss",
            "forks": 1,
            "params": {
                "commitCount": "1000",
                "queryLimit": "500",
                "engine": engine,
                "queryKind": query_kind,
            },
            "primaryMetric": {
                "score": score,
                "scoreError": 0.5,
                "scoreUnit": "ms/op",
            },
            "secondaryMetrics": cls.counters(
                3.0, commits, blobs, 2.0 if engine != "filesystem-jgit" else 0.0
            ),
        }

    @classmethod
    def build_result(cls) -> dict:
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "HistoryQueryCrossoverBenchmark.projectionBuild"
            ),
            "mode": "ss",
            "forks": 1,
            "params": {"commitCount": "1000"},
            "primaryMetric": {
                "score": 100.0,
                "scoreError": 2.0,
                "scoreUnit": "ms/op",
            },
            "secondaryMetrics": cls.counters(1000.0, 0.0, 0.0, 20.0),
        }


if __name__ == "__main__":
    unittest.main()
