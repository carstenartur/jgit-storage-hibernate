#!/usr/bin/env python3
"""Regression tests for the reflog performance chart converter."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
SCRIPT = SCRIPT_DIR / "convert-jmh-reflog-performance.py"
SPEC = importlib.util.spec_from_file_location("convert_jmh_reflog_performance", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class ReflogPerformanceConverterTest(unittest.TestCase):

  def test_complete_matrix_is_grouped_into_two_stable_operations(self) -> None:
    results = []
    score = 1.0
    for method in CONVERTER.OPERATIONS:
      for backend in CONVERTER.BACKENDS:
        for index_mode in CONVERTER.INDEXES:
          results.append(self.result(method, backend, index_mode, score))
          score += 1.0

    converted = CONVERTER.convert(results)
    self.assertEqual(8, len(converted))
    names = {entry["name"] for entry in converted}
    self.assertIn(
        "Reflog latest entry — PostgreSQL repository/ref-key/id index", names
    )
    self.assertIn(
        "Reflog latest entry — SQL Server legacy repository/id index", names
    )
    self.assertIn(
        "Reflog last 100 entries — SQL Server repository/ref-key/id index", names
    )
    self.assertTrue(all(entry["unit"] == "ms/op" for entry in converted))

  def test_incomplete_matrix_is_rejected(self) -> None:
    with self.assertRaisesRegex(ValueError, "Missing reflog benchmark series"):
      CONVERTER.convert(
          [
              self.result(
                  "lastEntry", "postgresql", "repository-ref-key-id", 1.0
              )
          ]
      )

  @staticmethod
  def result(method: str, backend: str, index_mode: str, score: float) -> dict:
    return {
        "benchmark": (
            "io.github.carstenartur.jgit.storage.hibernate.benchmark."
            "ReflogLookupBenchmark."
            + method
        ),
        "mode": "ss",
        "jdkVersion": "21",
        "params": {
            "backend": backend,
            "indexMode": index_mode,
            "rowCount": "10000",
            "refCount": "100",
        },
        "primaryMetric": {
            "score": score,
            "scoreError": 0.1,
            "scoreUnit": "ms/op",
        },
    }


if __name__ == "__main__":
  unittest.main()
