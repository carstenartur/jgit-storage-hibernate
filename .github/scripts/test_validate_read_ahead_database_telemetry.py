#!/usr/bin/env python3
"""Exercise read-ahead artifact validation without a database."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from validate_read_ahead_database_telemetry import validate


def evidence(profile: str) -> tuple[list[dict], list[dict]]:
    backends = ("postgresql",) if profile == "smoke" else (
        "postgresql", "postgresql-hikari", "hsqldb"
    )
    iterations = 1 if profile == "smoke" else 3
    results = []
    observations = []
    for backend in backends:
        for operation in ("sequentialRead", "randomReads", "shortRead"):
            for window in ("1", "4", "16"):
                results.append({
                    "benchmark": "org.example.ReadAheadPolicyBenchmark." + operation,
                    "params": {"backend": backend, "readAheadChunks": window},
                    "mode": "ss",
                    "threads": 1,
                    "forks": 1,
                    "measurementIterations": iterations,
                    "measurementBatchSize": 1,
                })
                if backend == "hsqldb":
                    continue
                for invocation in range(1, iterations + 1):
                    observations.append({
                        "coordinate": {
                            "benchmarkMethod": operation,
                            "backend": backend,
                            "readAheadChunks": window,
                            "measurementInvocation": str(invocation),
                        },
                        "backend": "postgresql",
                        "enabled": True,
                        "serverVersion": "17.10",
                        "startedAt": "2026-11-07T12:00:00Z",
                        "completedAt": "2026-11-07T12:00:01Z",
                        "counters": {"postgresql.database.blocks_read": 0,
                                     "postgresql.database.blocks_hit": 10},
                    })
    return results, observations


class ReadAheadTelemetryTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.artifacts = Path(directory.name) / "artifacts"
        self.reports = Path(directory.name) / "reports"
        self.artifacts.mkdir()
        self.reports.mkdir()
        self.jmh, self.observations = evidence("smoke")

    def check_artifacts(self, profile: str = "smoke", *, ndjson: list[dict] | None = None) -> int:
        (self.artifacts / "jmh-result.json").write_text(json.dumps(self.jmh), encoding="utf-8")
        (self.artifacts / "read-ahead-database-telemetry.json").write_text(
            json.dumps({"schemaVersion": 1, "observations": self.observations}),
            encoding="utf-8",
        )
        lines = self.observations if ndjson is None else ndjson
        (self.artifacts / "read-ahead-database-telemetry.ndjson").write_text(
            "".join(json.dumps(item) + "\n" for item in lines), encoding="utf-8"
        )
        return validate(self.artifacts, profile, self.reports)

    def test_accepts_complete_smoke_matrix(self) -> None:
        self.assertEqual(9, self.check_artifacts())

    def test_accepts_full_matrix_with_native_observations_only_for_postgresql(self) -> None:
        self.jmh, self.observations = evidence("full")
        self.assertEqual(54, self.check_artifacts("full"))

    def test_rejects_missing_measurement_invocation(self) -> None:
        self.observations.pop()
        with self.assertRaisesRegex(ValueError, "Unexpected read-ahead telemetry coordinates"):
            self.check_artifacts()

    def test_rejects_duplicate_measurement_invocation(self) -> None:
        self.observations[-1] = self.observations[0]
        with self.assertRaisesRegex(ValueError, "Duplicate read-ahead telemetry observation"):
            self.check_artifacts()

    def test_rejects_wrong_read_ahead_window(self) -> None:
        self.observations[-1]["coordinate"]["readAheadChunks"] = "2"
        with self.assertRaisesRegex(ValueError, "Unexpected read-ahead telemetry coordinates"):
            self.check_artifacts()

    def test_rejects_different_json_and_ndjson_observations(self) -> None:
        with self.assertRaisesRegex(ValueError, "JSON and NDJSON differ"):
            self.check_artifacts(ndjson=self.observations[:-1])

    def test_rejects_missing_or_negative_native_read_counter(self) -> None:
        counters = self.observations[0]["counters"]
        counters.pop("postgresql.database.blocks_read")
        with self.assertRaisesRegex(ValueError, "Missing PostgreSQL read evidence"):
            self.check_artifacts()
        counters["postgresql.database.blocks_read"] = -1
        with self.assertRaisesRegex(ValueError, "Missing PostgreSQL read evidence"):
            self.check_artifacts()

    def test_rejects_connection_details_in_artifact(self) -> None:
        (self.artifacts / "fork.log").write_text("jdbc:postgresql://fixture/db", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Connection detail in"):
            self.check_artifacts()

    def test_rejects_connection_override_in_failsafe_report(self) -> None:
        (self.reports / "TEST-read-ahead.xml").write_text(
            '<property name="jgit.storage.benchmark.postgresql.password" value="synthetic"/>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "Connection detail in"):
            self.check_artifacts()


if __name__ == "__main__":
    unittest.main()
