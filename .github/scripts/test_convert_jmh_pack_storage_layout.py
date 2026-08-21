#!/usr/bin/env python3
"""Regression tests for pack-storage-layout evidence conversion."""

from __future__ import annotations

import importlib.util
import math
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("convert-jmh-pack-storage-layout.py")
SPEC = importlib.util.spec_from_file_location("convert_jmh_pack_storage_layout", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Could not load {SCRIPT}")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)


class PackStorageLayoutConverterTest(unittest.TestCase):

    def test_one_database_never_changes_the_production_layout(self) -> None:
        report = CONVERTER.convert(self.matrix(["postgresql"], sparse_candidate=9.7))
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )
        self.assertFalse(report["productionDefaultsChanged"])
        self.assertTrue(report["compatibility"]["legacyRowsRemainOneMiB"])

    def test_cross_database_net_gain_can_only_propose_a_versioned_candidate(self) -> None:
        report = CONVERTER.convert(
            self.matrix(["postgresql", "sqlserver"], sparse_candidate=10.3)
        )
        self.assertEqual(
            "candidate-layout-ready-for-versioned-format-design", report["decision"]
        )
        candidate = next(
            item
            for item in report["layoutCandidates"]
            if item["chunkKiB"] == 2048 and item["inlineKiB"] == 256
        )
        self.assertTrue(candidate["eligible"])
        self.assertFalse(report["productionDefaultsChanged"])

    def test_sparse_read_regression_rejects_a_write_optimized_candidate(self) -> None:
        report = CONVERTER.convert(
            self.matrix(["postgresql", "sqlserver"], sparse_candidate=12.0)
        )
        candidate = next(
            item
            for item in report["layoutCandidates"]
            if item["chunkKiB"] == 2048 and item["inlineKiB"] == 256
        )
        self.assertFalse(candidate["eligible"])
        self.assertEqual(
            "retain-current-layout-pending-postgresql-and-sqlserver-evidence",
            report["decision"],
        )

    def test_retained_budget_rounding_violation_is_rejected(self) -> None:
        result = self.result("postgresql", "write", 1024, 10.0)
        result["secondaryMetrics"][
            "LayoutCounters.actualRetainedChunkBytes"
        ]["score"] = 17 * 1024 * 1024
        with self.assertRaisesRegex(ValueError, "Retained chunk bytes"):
            CONVERTER.convert([result])

    def test_multi_iteration_event_scores_are_normalized_from_raw_data(self) -> None:
        results = self.matrix(["postgresql"], sparse_candidate=9.7)
        for result in results:
            for metric in result["secondaryMetrics"].values():
                per_iteration = float(metric["score"])
                metric["score"] = per_iteration * 2.0
                metric["rawData"] = [[per_iteration, per_iteration]]
        report = CONVERTER.convert(results)
        self.assertEqual(6, len(report["evidence"]))
        current_write = next(
            row
            for row in report["evidence"]
            if row["operation"] == "write" and row["chunkKiB"] == 1024
        )
        self.assertEqual(
            16 * 1024 * 1024, current_write["configuredRetainedBudgetBytes"]
        )
        self.assertEqual(
            16 * 1024 * 1024, current_write["actualRetainedChunkBytes"]
        )
        self.assertEqual(16, current_write["chunksPerBatch"])
        self.assertEqual(16, current_write["chunkRows"])
        self.assertEqual(
            16 * 1024 * 1024, current_write["databasePayloadBytes"]
        )

    def test_configured_metric_change_across_iterations_is_rejected(self) -> None:
        result = self.result("postgresql", "write", 1024, 10.0)
        metric = result["secondaryMetrics"]["LayoutCounters.configuredChunkBytes"]
        expected = float(metric["score"])
        metric["score"] = expected * 2.0
        metric["rawData"] = [[expected, expected * 2.0]]
        with self.assertRaisesRegex(ValueError, "changed across JMH iterations"):
            CONVERTER.convert([result])

    def test_present_but_invalid_raw_data_is_rejected(self) -> None:
        invalid_values = (None, {}, [], [[]], [[math.nan]], [["not-a-number"]])
        for raw_data in invalid_values:
            with self.subTest(raw_data=raw_data):
                result = self.result("postgresql", "write", 1024, 10.0)
                result["secondaryMetrics"][
                    "LayoutCounters.configuredChunkBytes"
                ]["rawData"] = raw_data
                with self.assertRaisesRegex(ValueError, "rawData"):
                    CONVERTER.convert([result])

    def matrix(self, backends: list[str], sparse_candidate: float) -> list[dict]:
        results = []
        for backend in backends:
            for operation, baseline, candidate in (
                ("write", 10.0, 8.0),
                ("sequential-read", 10.0, 8.5),
                ("random-read", 10.0, sparse_candidate),
            ):
                results.append(self.result(backend, operation, 1024, baseline))
                results.append(self.result(backend, operation, 2048, candidate))
        return results

    @staticmethod
    def result(backend: str, operation: str, chunk_kib: int, score: float) -> dict:
        payload_kib = 16384
        retained_mib = 16
        inline_kib = 256
        read_ahead_kib = 1024
        payload_bytes = payload_kib * 1024
        chunk_bytes = chunk_kib * 1024
        chunk_rows = (payload_bytes + chunk_bytes - 1) // chunk_bytes
        actual_retained = (retained_mib * 1024 * 1024 // chunk_bytes) * chunk_bytes
        counters = {
            "configuredChunkBytes": chunk_bytes,
            "configuredInlineThresholdBytes": inline_kib * 1024,
            "configuredRetainedBudgetBytes": retained_mib * 1024 * 1024,
            "actualRetainedChunkBytes": actual_retained,
            "configuredReadAheadBytes": read_ahead_kib * 1024,
            "readAheadChunks": (read_ahead_kib + chunk_kib - 1) // chunk_kib,
            "chunksPerBatch": actual_retained // chunk_bytes,
            "proposedLayoutVersion": 1 if chunk_kib == 1024 else 2,
            "logicalPayloadBytes": payload_bytes,
            "packRows": 1,
            "chunkRows": chunk_rows,
            "jdbcBatchExecutions": 1,
            "jdbcStatementExecutions": 2,
            "preparedStatements": 3,
            "hibernateQueries": 1,
            "flushes": 2,
            "databasePayloadBytes": payload_bytes,
            "logicalBytesConsumed": payload_bytes,
            "overfetchBytes": 0,
        }
        return {
            "benchmark": (
                "io.github.carstenartur.jgit.storage.hibernate.benchmark."
                "PackStorageLayoutBenchmark.execute"
            ),
            "mode": "ss",
            "jdkVersion": "21",
            "params": {
                "backend": backend,
                "deployment": backend + "-test",
                "operation": operation,
                "payloadKiB": str(payload_kib),
                "chunkKiB": str(chunk_kib),
                "inlineKiB": str(inline_kib),
                "retainedMiB": str(retained_mib),
                "readAheadKiB": str(read_ahead_kib),
            },
            "primaryMetric": {
                "score": score,
                "scoreError": 0.1,
                "scoreUnit": "ms/op",
                "scorePercentiles": {
                    "50.0": score,
                    "95.0": score * 1.1,
                    "99.0": score * 1.2,
                },
            },
            "secondaryMetrics": {
                f"LayoutCounters.{key}": {"score": value, "scoreUnit": "#"}
                for key, value in counters.items()
            },
        }


if __name__ == "__main__":
    unittest.main()
