#!/usr/bin/env python3
"""Tests for convert-jmh-backend-comparison.py."""

from __future__ import annotations

import importlib.util
import json
import math
import tempfile
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
        raw = [
            self._backend_result(backend, index + 1.0)
            for index, backend in enumerate(MODULE.BACKEND_LABELS)
        ]

        converted = MODULE.convert(raw)

        self.assertEqual(len(MODULE.BACKEND_LABELS), len(converted))
        names = {entry["name"] for entry in converted}
        self.assertIn("operation — JGit + PostgreSQL + HikariCP", names)
        self.assertIn("operation — JGit + filesystem", names)
        for entry in converted:
            self.assertEqual("ms/op", entry["unit"])
            self.assertTrue(math.isfinite(entry["value"]))
            self.assertIn("JDK: 21", entry["extra"])

    def test_normalizes_throughput_to_milliseconds_per_operation(self) -> None:
        result = self._backend_result("postgresql", 50.0)
        result["primaryMetric"] = {
            "score": 50.0,
            "scoreError": 5.0,
            "scoreUnit": "ops/s",
        }
        result["mode"] = "thrpt"

        converted = MODULE.convert([result])[0]

        self.assertEqual("ms/op", converted["unit"])
        self.assertAlmostEqual(20.0, converted["value"])
        self.assertAlmostEqual(2.0, converted["range"])
        self.assertIn("Original metric: 50.0 ops/s", converted["extra"])

    def test_normalizes_other_time_units(self) -> None:
        result = self._backend_result("filesystem", 2_500_000.0)
        result["primaryMetric"] = {
            "score": 2_500_000.0,
            "scoreError": 100_000.0,
            "scoreUnit": "ns/op",
        }

        converted = MODULE.convert([result])[0]

        self.assertEqual("ms/op", converted["unit"])
        self.assertAlmostEqual(2.5, converted["value"])
        self.assertAlmostEqual(0.1, converted["range"])

    def test_converts_large_pack_batching_modes(self) -> None:
        converted = MODULE.convert(
            [
                self._batching_result("disabled", 12.0),
                self._batching_result("enabled", 9.0),
                self._batching_result("enabled-rewrite", 8.0),
            ]
        )

        names = {entry["name"] for entry in converted}
        self.assertEqual(3, len(converted))
        self.assertIn("publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching off)", names)
        self.assertIn("publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching on)", names)
        self.assertIn(
            "publishTwelveMiBPack — JGit + PostgreSQL (JDBC batching + rewrite)", names
        )

    def test_loads_and_combines_multiple_result_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            standard = root / "standard.json"
            focused = root / "focused.json"
            standard.write_text(
                json.dumps([self._backend_result("postgresql", 12.0)]), encoding="utf-8"
            )
            focused.write_text(
                json.dumps([self._batching_result("enabled", 9.0)]), encoding="utf-8"
            )

            combined = MODULE.load_results([standard, focused])

        self.assertEqual(2, len(combined))
        self.assertEqual(2, len(MODULE.convert(combined)))

    def test_rejects_unknown_backends(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported JMH backend"):
            MODULE.convert([self._backend_result("unknown", 1.0)])

    def test_rejects_results_without_series_parameter(self) -> None:
        result = self._backend_result("postgresql", 1.0)
        result["params"] = {}
        with self.assertRaisesRegex(ValueError, "neither a backend nor a batchingMode"):
            MODULE.convert([result])

    def test_rejects_zero_throughput(self) -> None:
        result = self._backend_result("postgresql", 1.0)
        result["primaryMetric"] = {
            "score": 0.0,
            "scoreError": 0.0,
            "scoreUnit": "ops/s",
        }
        with self.assertRaisesRegex(ValueError, "throughput must be positive"):
            MODULE.convert([result])

    @staticmethod
    def _backend_result(backend: str, score: float) -> dict:
        result = ConvertJmhBackendComparisonTest._result(score)
        result["params"] = {"backend": backend}
        return result

    @staticmethod
    def _batching_result(batching_mode: str, score: float) -> dict:
        result = ConvertJmhBackendComparisonTest._result(score)
        result["benchmark"] = "example.LargePackJdbcBatchBenchmark.publishTwelveMiBPack"
        result["params"] = {"batchingMode": batching_mode}
        return result

    @staticmethod
    def _result(score: float) -> dict:
        return {
            "benchmark": "example.Benchmark.operation",
            "params": {},
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
