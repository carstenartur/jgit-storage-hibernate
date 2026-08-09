#!/usr/bin/env python3
"""Regression tests for benchmark unit normalization."""

from __future__ import annotations

import unittest

import benchmark_units


class BenchmarkUnitsTest(unittest.TestCase):

    def test_timing_and_throughput_are_normalized_to_milliseconds_per_operation(self) -> None:
        self.assertEqual((2.0, 0.5), benchmark_units.normalize_measurement(2.0, 0.5, "ms/op"))
        value, error = benchmark_units.normalize_measurement(500.0, 10.0, "ops/s")
        self.assertAlmostEqual(2.0, value)
        self.assertAlmostEqual(0.04, error)

    def test_smaller_is_better_non_timing_units_are_preserved(self) -> None:
        for unit, value in (
            ("bytes", 1024.0),
            ("segments", 3.0),
            ("miss %", 25.0),
            ("count/op", 4.0),
        ):
            normalized = benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": unit, "value": value, "range": 1.0}
            )
            self.assertEqual(unit, normalized["unit"])
            self.assertEqual(value, normalized["value"])
            self.assertEqual(1.0, normalized["range"])

    def test_time_like_custom_metrics_must_use_ms_per_operation(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported benchmark unit"):
            benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": "ms", "value": 12.5, "range": 1.0}
            )

    def test_unscoped_counts_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported benchmark unit"):
            benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": "count", "value": 4.0}
            )

    def test_passthrough_units_still_reject_non_finite_or_negative_ranges(self) -> None:
        with self.assertRaisesRegex(ValueError, "finite"):
            benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": "bytes", "value": float("inf")}
            )
        with self.assertRaisesRegex(ValueError, "negative"):
            benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": "count/op", "value": 0.0, "range": -1.0}
            )

    def test_unknown_non_timing_unit_is_not_silently_accepted(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsupported benchmark unit"):
            benchmark_units.normalize_benchmark(
                {"name": "metric", "unit": "widgets", "value": 1.0}
            )


if __name__ == "__main__":
    unittest.main()
