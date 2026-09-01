#!/usr/bin/env python3
"""Regression tests for JMH performance badge generation."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("write-performance-badge.py")
SPEC = importlib.util.spec_from_file_location("write_performance_badge", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
WRITER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WRITER)


class PerformanceBadgeWriterTest(unittest.TestCase):

    def test_throughput_is_normalized_before_color_classification(self) -> None:
        payload = WRITER.build_payload(
            self.result_file(303.3955877080345, "ops/s")
        )
        self.assertEqual(
            "publishToDifferentRepositories 303.40 ops/s",
            payload["message"],
        )
        self.assertEqual("brightgreen", payload["color"])

    def test_latency_classification_remains_smaller_is_better(self) -> None:
        payload = WRITER.build_payload(self.result_file(60.0, "ms/op"))
        self.assertEqual("red", payload["color"])

    def test_unsupported_units_are_rejected_instead_of_misclassified(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported benchmark unit"):
            WRITER.build_payload(self.result_file(303.4, "requests/s"))

    def result_file(self, score: float, unit: str) -> Path:
        directory = Path(self.add_cleanup_directory())
        path = directory / "jmh-result.json"
        path.write_text(
            json.dumps(
                [
                    {
                        "benchmark": (
                            "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                            "RepositoryBackendBenchmark.publishToDifferentRepositories"
                        ),
                        "primaryMetric": {
                            "score": score,
                            "scoreUnit": unit,
                        },
                    }
                ]
            ),
            encoding="utf-8",
        )
        return path

    def add_cleanup_directory(self) -> str:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return temporary.name


if __name__ == "__main__":
    unittest.main()
