#!/usr/bin/env python3
"""Regression tests for the pack-layout concurrency converter."""

from __future__ import annotations

import importlib.util
import json
import math
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("convert-jmh-pack-storage-layout-concurrency.py")
SPEC = importlib.util.spec_from_file_location(
    "convert_jmh_pack_storage_layout_concurrency", SCRIPT
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class PackStorageLayoutConcurrencyConverterTest(unittest.TestCase):

    def test_uniform_candidate_is_observational_only(self) -> None:
        results = []
        for concurrency in (1, 4, 16):
            for operation, baseline in (
                ("write", 10.0 + concurrency),
                ("sequential-read", 8.0 + concurrency),
                ("random-read", 2.0 + concurrency / 10.0),
            ):
                results.append(
                    self.result(operation, concurrency, 1024, baseline)
                )
                results.append(
                    self.result(operation, concurrency, 4096, baseline * 0.90)
                )

        report = CONVERTER.convert(results)
        candidate = next(
            item
            for item in report["concurrencyCandidates"]
            if item["chunkKiB"] == 4096
        )
        self.assertTrue(candidate["completeConcurrencyLevels"])
        self.assertTrue(candidate["completeWriteLevels"])
        self.assertTrue(candidate["completeSequentialReadLevels"])
        self.assertTrue(candidate["completeSparseReadLevels"])
        self.assertTrue(candidate["observationalCandidate"])
        self.assertFalse(report["productionDefaultsChanged"])
        self.assertEqual(
            "retain-current-layout-pending-full-cross-database-evidence",
            report["decision"],
        )
        sixteen_worker = next(
            row
            for row in report["evidence"]
            if row["concurrency"] == 16
            and row["operation"] == "write"
            and row["chunkKiB"] == 1024
        )
        self.assertEqual(16, sixteen_worker["concurrency"])
        self.assertEqual(
            16 * 1024 * 1024, sixteen_worker["actualRetainedChunkBytes"]
        )
        self.assertEqual(1.0, sixteen_worker["jdbcBatchExecutionsPerWorker"])

    def test_malformed_schema_is_reported_as_contextual_value_error(self) -> None:
        malformed_cases = []
        malformed_cases.append(("array", {}, "expected JSON array"))
        malformed_cases.append(("result", [None], "expected JSON object"))

        for field in ("params", "primaryMetric", "secondaryMetrics"):
            result = self.result("write", 1, 1024, 10.0)
            result[field] = None
            malformed_cases.append((field, [result], field))

        for field in (
            "backend",
            "operation",
            "payloadKiB",
            "chunkKiB",
            "inlineKiB",
            "retainedMiB",
            "readAheadKiB",
            "concurrency",
        ):
            result = self.result("write", 1, 1024, 10.0)
            del result["params"][field]
            malformed_cases.append((field, [result], field))

        result = self.result("write", 1, 1024, 10.0)
        del result["threads"]
        malformed_cases.append(("threads", [result], "threads"))

        for field in ("score", "scoreUnit"):
            result = self.result("write", 1, 1024, 10.0)
            del result["primaryMetric"][field]
            malformed_cases.append((field, [result], field))

        result = self.result("write", 1, 1024, 10.0)
        result["params"]["concurrency"] = "not-an-integer"
        malformed_cases.append(("integer", [result], "concurrency"))

        result = self.result("write", 1, 1024, 10.0)
        result["primaryMetric"]["scorePercentiles"] = []
        malformed_cases.append(("percentiles", [result], "scorePercentiles"))

        result = self.result("write", 1, 1024, 10.0)
        del result["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"]
        malformed_cases.append(("secondary score", [result], "configuredChunkBytes"))

        for name, value, expected in malformed_cases:
            with self.subTest(name=name):
                with self.assertRaises(ValueError) as raised:
                    CONVERTER.convert(value)
                message = str(raised.exception)
                self.assertIn(expected, message)
                if name not in {"array", "result", "backend", "operation", "params"}:
                    self.assertIn("operation='write'", message)

    def test_nan_score_error_becomes_json_null(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["primaryMetric"]["scoreError"] = math.nan

        report = CONVERTER.convert([current, candidate])
        current_row = next(
            row
            for row in report["evidence"]
            if row["chunkKiB"] == 1024
        )
        self.assertIsNone(current_row["scoreErrorMillis"])
        serialized = CONVERTER._strict_json(report)
        self.assertNotIn("NaN", serialized)
        self.assertIsNone(
            next(
                row
                for row in json.loads(serialized)["evidence"]
                if row["chunkKiB"] == 1024
            )["scoreErrorMillis"]
        )

    def test_missing_score_error_becomes_json_null(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        del current["primaryMetric"]["scoreError"]
        report = CONVERTER.convert([current, candidate])
        self.assertIsNone(
            next(
                row
                for row in report["evidence"]
                if row["chunkKiB"] == 1024
            )["scoreErrorMillis"]
        )

    def test_non_finite_primary_score_is_rejected(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, value)
                candidate = self.result("write", 1, 4096, 9.0)
                with self.assertRaisesRegex(ValueError, "primaryMetric score"):
                    CONVERTER.convert([current, candidate])

    def test_non_finite_primary_percentile_is_rejected(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                current["primaryMetric"]["scorePercentiles"]["95.0"] = value
                with self.assertRaisesRegex(
                    ValueError, "primaryMetric percentile"
                ):
                    CONVERTER.convert([current, candidate])

    def test_strict_json_rejects_unexpected_non_finite_values(self) -> None:
        with self.assertRaises(ValueError):
            CONVERTER._strict_json({"unexpected": math.nan})

    def test_boolean_primary_values_are_rejected(self) -> None:
        for field in ("score", "percentile"):
            with self.subTest(field=field):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                if field == "score":
                    current["primaryMetric"]["score"] = True
                else:
                    current["primaryMetric"]["scorePercentiles"]["95.0"] = True
                with self.assertRaisesRegex(ValueError, "operation='write'"):
                    CONVERTER.convert([current, candidate])

    def test_non_integral_and_infinite_integer_parameters_are_rejected(self) -> None:
        for value in (1.5, math.inf, -math.inf):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                current["params"]["concurrency"] = value
                with self.assertRaisesRegex(ValueError, "concurrency"):
                    CONVERTER.convert([current, candidate])

    def test_unsupported_score_unit_preserves_coordinate_context(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["primaryMetric"]["scoreUnit"] = "bogus"
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("Unsupported", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_non_positive_primary_score_preserves_coordinate_context(self) -> None:
        for value in (0.0, -1.0):
            with self.subTest(value=value):
                current = self.result("write", 1, 1024, value)
                candidate = self.result("write", 1, 4096, 9.0)
                with self.assertRaises(ValueError) as raised:
                    CONVERTER.convert([current, candidate])
                message = str(raised.exception)
                self.assertIn("score must be positive", message)
                self.assertIn("operation='write'", message)
                self.assertIn("concurrency='1'", message)

    def test_missing_secondary_score_preserves_field_detail(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        del current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"]
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("missing field 'score'", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_boolean_secondary_values_are_rejected(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]["score"] = True
        with self.assertRaisesRegex(ValueError, "configuredChunkBytes"):
            CONVERTER.convert([current, candidate])

    def test_missing_current_layout_baseline_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "Missing current-layout"):
            CONVERTER.convert([self.result("write", 1, 4096, 5.0)])

    def test_incomplete_concurrency_levels_do_not_create_candidate(self) -> None:
        results = []
        for concurrency in (1, 4):
            for operation in ("write", "sequential-read", "random-read"):
                results.append(self.result(operation, concurrency, 1024, 10.0))
                results.append(self.result(operation, concurrency, 4096, 9.0))
        report = CONVERTER.convert(results)
        candidate = next(
            item
            for item in report["concurrencyCandidates"]
            if item["chunkKiB"] == 4096
        )
        self.assertFalse(candidate["completeConcurrencyLevels"])
        self.assertFalse(candidate["observationalCandidate"])

    def test_each_operation_must_cover_all_worker_levels(self) -> None:
        results = []
        for concurrency in (1, 4, 16):
            for operation in ("write", "sequential-read", "random-read"):
                results.append(self.result(operation, concurrency, 1024, 10.0))
                if operation != "sequential-read" or concurrency != 16:
                    results.append(self.result(operation, concurrency, 4096, 9.0))
        report = CONVERTER.convert(results)
        candidate = next(
            item
            for item in report["concurrencyCandidates"]
            if item["chunkKiB"] == 4096
        )
        self.assertTrue(candidate["completeWriteLevels"])
        self.assertFalse(candidate["completeSequentialReadLevels"])
        self.assertTrue(candidate["completeSparseReadLevels"])
        self.assertFalse(candidate["completeConcurrencyLevels"])
        self.assertFalse(candidate["observationalCandidate"])
        self.assertIn("incomplete", candidate["reason"].lower())

    def test_jmh_thread_count_must_match_concurrency_parameter(self) -> None:
        current = self.result("write", 4, 1024, 10.0)
        candidate = self.result("write", 4, 4096, 9.0)
        current["threads"] = 1
        with self.assertRaisesRegex(ValueError, "JMH threads"):
            CONVERTER.convert([current, candidate])

    def test_multi_iteration_event_scores_are_normalized_from_raw_data(self) -> None:
        results = []
        for concurrency in (1, 4, 16):
            for operation, baseline in (
                ("write", 10.0 + concurrency),
                ("sequential-read", 8.0 + concurrency),
                ("random-read", 2.0 + concurrency / 10.0),
            ):
                results.append(self.result(operation, concurrency, 1024, baseline))
                results.append(
                    self.result(operation, concurrency, 4096, baseline * 0.90)
                )
        for result in results:
            for metric in result["secondaryMetrics"].values():
                per_iteration_aggregate = float(metric["score"])
                metric["score"] = per_iteration_aggregate * 2.0
                metric["rawData"] = [
                    [per_iteration_aggregate, per_iteration_aggregate]
                ]

        report = CONVERTER.convert(results)
        sixteen_worker = next(
            row
            for row in report["evidence"]
            if row["concurrency"] == 16
            and row["operation"] == "write"
            and row["chunkKiB"] == 1024
        )
        self.assertEqual(16, sixteen_worker["concurrency"])
        self.assertEqual(
            16 * 1024 * 1024, sixteen_worker["actualRetainedChunkBytes"]
        )
        self.assertEqual(1.0, sixteen_worker["jdbcBatchExecutionsPerWorker"])
        self.assertEqual(2.0, sixteen_worker["jdbcStatementExecutionsPerWorker"])

    def test_structural_metric_change_across_iterations_is_rejected(self) -> None:
        current = self.result("write", 4, 1024, 10.0)
        candidate = self.result("write", 4, 4096, 9.0)
        metric = current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]
        expected = float(metric["score"])
        metric["score"] = expected * 3.0
        metric["rawData"] = [[expected, expected * 2.0]]
        with self.assertRaisesRegex(ValueError, "changed across JMH iterations"):
            CONVERTER.convert([current, candidate])

    def test_inconsistent_raw_data_fork_lengths_are_rejected(self) -> None:
        current = self.result("write", 1, 1024, 10.0)
        candidate = self.result("write", 1, 4096, 9.0)
        metric = current["secondaryMetrics"][
            "ConcurrencyCounters.configuredChunkBytes"
        ]
        expected = float(metric["score"])
        metric["rawData"] = [[expected, expected], [expected]]
        with self.assertRaises(ValueError) as raised:
            CONVERTER.convert([current, candidate])
        message = str(raised.exception)
        self.assertIn("fork lengths", message)
        self.assertIn("operation='write'", message)
        self.assertIn("concurrency='1'", message)

    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
        invalid_values = (None, {}, [], [[]], [[math.nan]], [["not-a-number"]])
        for raw_data in invalid_values:
            with self.subTest(raw_data=raw_data):
                current = self.result("write", 1, 1024, 10.0)
                candidate = self.result("write", 1, 4096, 9.0)
                current["secondaryMetrics"][
                    "ConcurrencyCounters.configuredChunkBytes"
                ]["rawData"] = raw_data
                with self.assertRaisesRegex(ValueError, "rawData"):
                    CONVERTER.convert([current, candidate])

    @staticmethod
    def result(
        operation: str,
        concurrency: int,
        chunk_kib: int,
        score: float,
    ) -> dict:
        per_worker = {
            "configuredConcurrency": concurrency,
            "configuredChunkBytes": chunk_kib * 1024,
            "configuredInlineThresholdBytes": 256 * 1024,
            "configuredRetainedBudgetBytes": 16 * 1024 * 1024,
            "actualRetainedChunkBytes": 16 * 1024 * 1024,
            "configuredReadAheadBytes": 1024 * 1024,
            "readAheadChunks": max(1, (1024 + chunk_kib - 1) // chunk_kib),
            "chunksPerBatch": (16 * 1024) // chunk_kib,
            "logicalPayloadBytes": 1024 * 1024,
            "chunkRows": 1 if chunk_kib >= 1024 else 4,
            "jdbcBatchExecutions": 1,
            "jdbcStatementExecutions": 2,
            "databasePayloadBytes": 1024 * 1024,
            "logicalBytesConsumed": 1024 * 1024,
            "overfetchBytes": 0,
        }
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "PackStorageLayoutConcurrencyBenchmark.execute"
            ),
            "mode": "sample",
            "threads": concurrency,
            "forks": 1,
            "jdkVersion": "21",
            "params": {
                "backend": "postgresql",
                "deployment": f"postgresql-concurrency-{concurrency}",
                "operation": operation,
                "payloadKiB": "1024",
                "chunkKiB": str(chunk_kib),
                "inlineKiB": "256",
                "retainedMiB": "16",
                "readAheadKiB": "1024",
                "concurrency": str(concurrency),
            },
            "primaryMetric": {
                "score": score,
                "scoreError": 0.1,
                "scoreUnit": "ms/op",
                "scorePercentiles": {
                    "50.0": score * 0.95,
                    "95.0": score * 1.10,
                    "99.0": score * 1.20,
                },
            },
            "secondaryMetrics": {
                f"ConcurrencyCounters.{key}": {
                    "score": value * concurrency,
                    "scoreUnit": "#",
                }
                for key, value in per_worker.items()
            },
        }


if __name__ == "__main__":
    unittest.main()
