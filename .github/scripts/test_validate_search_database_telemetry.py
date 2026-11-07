#!/usr/bin/env python3
"""Contracts for Search benchmark native telemetry evidence."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from validate_search_database_telemetry import scan_privacy, validate


def result(method: str) -> dict:
    return {
        "benchmark": "org.example.HibernateSearchPerformanceBenchmark." + method,
        "measurementIterations": 2,
        "params": {"commitCount": "100", "indexProfile": "metadata-v1", "queryLimit": "50"},
    }


def observation(method: str, iteration: int, wal: int) -> dict:
    return {
        "coordinate": {
            "benchmarkMethod": method,
            "commitCount": "100",
            "indexProfile": "metadata-v1",
            "measurementInvocation": str(iteration),
            "queryLimit": "50",
        },
        "backend": "postgresql",
        "enabled": True,
        "serverVersion": "17.10",
        "startedAt": "2026-11-01T12:00:00Z",
        "completedAt": "2026-11-01T12:00:01Z",
        "counters": {"postgresql.wal.insert_lsn_bytes": wal},
        "gauges": {},
        "metadata": {},
        "unsupported": {},
    }


class SearchDatabaseTelemetryTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.artifacts = Path(temporary.name)
        self.jmh = [result("incrementalIndexing"), result("fullTextSummaryHits")]
        self.observations = [
            observation(method, iteration, wal)
            for method, wal in (("incrementalIndexing", 100), ("fullTextSummaryHits", 0))
            for iteration in (1, 2)
        ]

    def verify(self) -> int:
        (self.artifacts / "jmh-result.json").write_text(json.dumps(self.jmh), encoding="utf-8")
        (self.artifacts / "search-database-telemetry.json").write_text(
            json.dumps({"schemaVersion": 1, "observations": self.observations}), encoding="utf-8"
        )
        (self.artifacts / "search-database-telemetry.ndjson").write_text(
            "".join(json.dumps(item) + "\n" for item in self.observations), encoding="utf-8"
        )
        return validate(self.artifacts)

    def test_one_observation_per_measurement_invocation(self) -> None:
        self.assertEqual(4, self.verify())

    def test_duplicate_cannot_replace_a_measurement_invocation(self) -> None:
        self.observations[-1] = self.observations[-2]
        with self.assertRaisesRegex(ValueError, "Duplicate telemetry coordinate"):
            self.verify()

    def test_unrelated_query_cannot_replace_an_expected_coordinate(self) -> None:
        self.observations[-1] = observation("pathLiteralSql", 2, 0)
        with self.assertRaisesRegex(ValueError, "Search telemetry coordinates differ"):
            self.verify()

    def test_write_requires_native_wal_evidence(self) -> None:
        self.observations[0]["counters"].clear()
        with self.assertRaisesRegex(ValueError, "Expected positive immediate WAL"):
            self.verify()

    def test_rejects_connection_details_in_retained_binary_reports(self) -> None:
        (self.artifacts / "report.bin").write_bytes(b"\xffjdbc:postgresql://fixture/db")
        with self.assertRaisesRegex(ValueError, "Connection detail in"):
            self.verify()

    def test_rejects_connection_overrides_in_failsafe_xml(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-search.xml"
            for field in ("url", "user", "password"):
                with self.subTest(field=field):
                    report.write_text(
                        "<testsuite><properties>"
                        f'<property name="jgit.storage.benchmark.postgresql.{field}" '
                        'value="synthetic-override"/>'
                        "</properties></testsuite>",
                        encoding="utf-8",
                    )
                    with self.assertRaisesRegex(ValueError, "Connection detail in"):
                        scan_privacy(self.artifacts, report.parent)


if __name__ == "__main__":
    unittest.main()
