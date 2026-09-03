#!/usr/bin/env python3
"""Tests for repository-aging provider-restart evidence validation."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parents[2]
TOOL_PATH = ROOT / ".github/scripts/repository_aging_restart_evidence.py"


def _load_tool() -> ModuleType:
    specification = importlib.util.spec_from_file_location(
        "repository_aging_restart_evidence", TOOL_PATH
    )
    if specification is None or specification.loader is None:
        raise RuntimeError(f"Cannot load {TOOL_PATH}")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


TOOL = _load_tool()


class RepositoryAgingRestartEvidenceTest(unittest.TestCase):

    def test_exact_restart_evidence_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory) / "evidence"
            _write_exact_evidence(
                root,
                backend="postgresql",
                cache_state="cold",
                evidence_repeat="2",
            )

            result = TOOL.validate_evidence(
                root,
                backend="postgresql",
                cache_state="cold",
                evidence_repeat="2",
                check_temporary_credentials=False,
            )

            self.assertEqual(9, result["jmhCoordinates"])
            self.assertEqual(36, result["phaseObservations"])
            self.assertEqual(
                set(TOOL.EXPECTED_PHASES), set(result["metricRanges"])
            )

    def test_missing_provider_restart_phase_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory) / "evidence"
            _write_exact_evidence(
                root,
                backend="sqlserver",
                cache_state="warm",
                evidence_repeat="1",
            )
            aggregate = json.loads(
                (root / TOOL.TELEMETRY_FILE).read_text(encoding="utf-8")
            )
            aggregate["observations"] = [
                item
                for item in aggregate["observations"]
                if not (
                    item["coordinate"]["phase"] == "provider-restart"
                    and item["coordinate"]["benchmarkMethod"]
                    == TOOL.EXPECTED_OPERATIONS[0]
                    and item["coordinate"]["maintenanceMode"]
                    == TOOL.EXPECTED_MAINTENANCE[0]
                )
            ]
            (root / TOOL.TELEMETRY_FILE).write_text(
                json.dumps(aggregate, indent=2) + "\n", encoding="utf-8"
            )

            with self.assertRaisesRegex(
                TOOL.EvidenceError, "Expected 36 phase observations"
            ):
                TOOL.validate_evidence(
                    root,
                    backend="sqlserver",
                    cache_state="warm",
                    evidence_repeat="1",
                    check_temporary_credentials=False,
                )

    def test_provider_restart_counter_must_be_exactly_one(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory) / "evidence"
            _write_exact_evidence(
                root,
                backend="postgresql",
                cache_state="cold",
                evidence_repeat="1",
            )
            jmh = json.loads((root / TOOL.JMH_FILE).read_text(encoding="utf-8"))
            jmh[0]["secondaryMetrics"]["providerRestarts"]["score"] = 0.0
            (root / TOOL.JMH_FILE).write_text(
                json.dumps(jmh, indent=2) + "\n", encoding="utf-8"
            )

            with self.assertRaisesRegex(
                TOOL.EvidenceError, "Expected one provider restart"
            ):
                TOOL.validate_evidence(
                    root,
                    backend="postgresql",
                    cache_state="cold",
                    evidence_repeat="1",
                    check_temporary_credentials=False,
                )

    def test_full_matrix_is_aggregated_into_repeat_dispersion(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_root = Path(temporary_directory) / "artifacts"
            output_root = Path(temporary_directory) / "summary"
            for backend in TOOL.METRIC_BY_BACKEND:
                for cache_state in ("cold", "warm"):
                    for evidence_repeat in ("1", "2", "3"):
                        artifact = (
                            evidence_root
                            / f"{backend}-{cache_state}-repeat-{evidence_repeat}"
                        )
                        _write_jmh(
                            artifact,
                            backend=backend,
                            cache_state=cache_state,
                            evidence_repeat=evidence_repeat,
                        )

            report = TOOL.aggregate_evidence(
                evidence_root,
                output_root,
                event_name="workflow_dispatch",
            )

            self.assertFalse(report["automaticMaintenanceChanged"])
            self.assertEqual(36, len(report["groups"]))
            self.assertTrue(
                all(item["repeatCount"] == 3 for item in report["groups"])
            )
            self.assertTrue((output_root / TOOL.SUMMARY_JSON).is_file())
            self.assertTrue((output_root / TOOL.SUMMARY_MARKDOWN).is_file())


def _jmh_results(
    *, backend: str, cache_state: str, evidence_repeat: str
) -> list[dict[str, object]]:
    deployment = (
        f"{backend}-restart-{cache_state}-repeat-{evidence_repeat}"
    )
    results: list[dict[str, object]] = []
    for operation_index, operation in enumerate(TOOL.EXPECTED_OPERATIONS):
        for maintenance_index, maintenance in enumerate(
            TOOL.EXPECTED_MAINTENANCE
        ):
            results.append(
                {
                    "benchmark": (
                        "io.github.example.RepositoryAgingBenchmark." + operation
                    ),
                    "params": {
                        "backend": backend,
                        "cacheState": cache_state,
                        "deployment": deployment,
                        "evidenceRepeat": evidence_repeat,
                        "maintenanceMode": maintenance,
                        "providerLifecycle": "restarted-provider",
                        "pushes": "10",
                    },
                    "primaryMetric": {
                        "score": (
                            1.0
                            + operation_index
                            + maintenance_index / 10.0
                            + int(evidence_repeat) / 100.0
                        ),
                        "scoreUnit": "ms/op",
                    },
                    "secondaryMetrics": {
                        "providerRestarts": {"score": 1.0}
                    },
                }
            )
    return results


def _write_jmh(
    root: Path, *, backend: str, cache_state: str, evidence_repeat: str
) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / TOOL.JMH_FILE).write_text(
        json.dumps(
            _jmh_results(
                backend=backend,
                cache_state=cache_state,
                evidence_repeat=evidence_repeat,
            ),
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def _write_exact_evidence(
    root: Path, *, backend: str, cache_state: str, evidence_repeat: str
) -> None:
    _write_jmh(
        root,
        backend=backend,
        cache_state=cache_state,
        evidence_repeat=evidence_repeat,
    )
    deployment = (
        f"{backend}-restart-{cache_state}-repeat-{evidence_repeat}"
    )
    required_metric = TOOL.METRIC_BY_BACKEND[backend]
    observations: list[dict[str, object]] = []
    for operation in TOOL.EXPECTED_OPERATIONS:
        for maintenance in TOOL.EXPECTED_MAINTENANCE:
            for phase in TOOL.EXPECTED_PHASES:
                value = 0
                if phase == "fixture-build":
                    value = 100
                elif phase == "maintenance" and maintenance != "none":
                    value = 50
                coordinate = dict(
                    sorted(
                        {
                            "backend": backend,
                            "benchmarkMethod": operation,
                            "cacheState": cache_state,
                            "databaseBackend": backend,
                            "deployment": deployment,
                            "evidenceRepeat": evidence_repeat,
                            "maintenanceMode": maintenance,
                            "measurementIteration": (
                                "1" if phase == "measurement" else "0"
                            ),
                            "phase": phase,
                            "providerLifecycle": "restarted-provider",
                            "pushes": "10",
                        }.items()
                    )
                )
                observations.append(
                    {
                        "backend": backend,
                        "completedAt": "2026-09-03T05:00:01Z",
                        "coordinate": coordinate,
                        "counters": {required_metric: value},
                        "enabled": True,
                        "gauges": {},
                        "metadata": {},
                        "serverVersion": "test",
                        "startedAt": "2026-09-03T05:00:00Z",
                        "unsupported": {},
                    }
                )
    aggregate = {"schemaVersion": 1, "observations": observations}
    (root / TOOL.TELEMETRY_FILE).write_text(
        json.dumps(aggregate, indent=2) + "\n", encoding="utf-8"
    )
    (root / TOOL.NDJSON_FILE).write_text(
        "\n".join(json.dumps(item) for item in observations) + "\n",
        encoding="utf-8",
    )
    (root / TOOL.OUTPUT_FILE).write_text("synthetic JMH output\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
