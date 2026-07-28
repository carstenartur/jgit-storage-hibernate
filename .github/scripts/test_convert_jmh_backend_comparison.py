#!/usr/bin/env python3
"""Tests for convert-jmh-backend-comparison.py."""

from __future__ import annotations

import importlib.util
import math
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("convert-jmh-backend-comparison.py")
SPEC = importlib.util.spec_from_file_location("convert_jmh_backend_comparison", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ConvertJmhBackendComparisonTest(unittest.TestCase):
    def test_converts_all_supported_backends_with_readable_labels(self) -> None:
        raw = [self._result(backend, index + 1.0) for index, backend in enumerate(MODULE.BACKEND_LABELS)]

        converted = MODULE.convert(raw)

        self.assertEqual(len(MODULE.BACKEND_LABELS), len(converted))
        names = {entry["name"] for entry in converted}
        self.assertIn("operation — JGit + PostgreSQL + HikariCP", names)
        self.assertIn("operation — JGit + filesystem", names)
        for entry in converted:
          self.assertEqual("ms/op", entry["unit"])
          self.assertTrue(math.isfinite(entry["value"]))
          self.assertIn("JDK: 21", entry["extra"])

    def test_rejects_unknown_backends(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported JMH backend"):
            MODULE.convert([self._result("unknown", 1.0)])

    @staticmethod
    def _result(backend: str, score: float) -> dict:
        return {
            "benchmark": "example.Benchmark.operation",
            "params": {"backend": backend},
            "primaryMetric": {
                "score": score,
                "scoreError": score / 10,
                "scoreUnit": "ms/op",
            },
            "jdkVersion": "21",
            "mode": "avgt",
            "forks": 1,
        }


if __name__ == "__main__":
    unittest.main()
