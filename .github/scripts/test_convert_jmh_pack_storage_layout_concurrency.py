#!/usr/bin/env python3
"""Regression tests for the pack-layout concurrency converter."""

from __future__ import annotations

import importlib.util
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
