#!/usr/bin/env python3
"""Regression tests for the pack-layout concurrency converter."""

from __future__ import annotations

import importlib.util
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
        self.assertTrue(candidate["observationalCandidate"])
        self.assertFalse(report["productionDefaultsChanged"])
        self.assertEqual(
            "retain-current-layout-pending-full-cross-database-evidence",
            report["decision"],
        )

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

    @staticmethod
    def result(
        operation: str,
        concurrency: int,
        chunk_kib: int,
        score: float,
    ) -> dict:
        secondary = {
            "configuredConcurrency": concurrency,
            "configuredChunkBytes": chunk_kib * 1024,
            "configuredInlineThresholdBytes": 256 * 1024,
            "configuredRetainedBudgetBytes": 16 * 1024 * 1024,
            "actualRetainedChunkBytes": 16 * 1024 * 1024,
            "configuredReadAheadBytes": 1024 * 1024,
            "readAheadChunks": max(1, 1024 // chunk_kib),
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
                f"ConcurrencyCounters.{key}": {"score": value, "scoreUnit": "#"}
                for key, value in secondary.items()
            },
        }


if __name__ == "__main__":
    unittest.main()
