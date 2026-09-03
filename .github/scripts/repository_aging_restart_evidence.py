#!/usr/bin/env python3
"""Validate and aggregate repeated repository-aging provider-restart evidence."""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable

JMH_FILE = "jmh-result.json"
OUTPUT_FILE = "repository-aging-jmh-output.txt"
TELEMETRY_FILE = "repository-aging-database-telemetry.json"
NDJSON_FILE = "repository-aging-database-telemetry.ndjson"
SUMMARY_JSON = "repository-aging-restart-reproducibility.json"
SUMMARY_MARKDOWN = "repository-aging-restart-reproducibility.md"
EXPECTED_PHASES = (
    "fixture-build",
    "maintenance",
    "provider-restart",
    "measurement",
)
EXPECTED_OPERATIONS = (
    "cloneStyleTraversal",
    "lookupOldestObject",
    "reopenAndLookupOldest",
)
EXPECTED_MAINTENANCE = ("none", "compact-only", "read-optimized")
METRIC_BY_BACKEND = {
    "postgresql": "postgresql.wal.insert_lsn_bytes",
    "sqlserver": "sqlserver.io.log.bytes_written",
}
UNIT_TO_MILLIS = {
    "ns/op": 1e-6,
    "us/op": 1e-3,
    "µs/op": 1e-3,
    "ms/op": 1.0,
    "s/op": 1000.0,
}
FORBIDDEN_EVIDENCE_TOKENS = (
    "jgit.storage.benchmark.postgresql.url=",
    "jgit.storage.benchmark.postgresql.user=",
    "jgit.storage.benchmark.postgresql.password=",
    "jgit.storage.benchmark.sqlserver.url=",
    "jgit.storage.benchmark.sqlserver.user=",
    "jgit.storage.benchmark.sqlserver.password=",
    "jdbc:postgresql://",
    "jdbc:sqlserver://",
    "Database JDBC URL",
    "Default catalog/schema",
)


class EvidenceError(ValueError):
    """Evidence is missing, inconsistent, unsafe, or not comparable."""


def _reject_non_finite(value: str) -> None:
    raise EvidenceError(f"Non-finite JSON constant {value!r}")


def _load_json(path: Path) -> Any:
    if not path.is_file() or path.stat().st_size < 3:
        raise EvidenceError(f"Missing or empty evidence file {path}")
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_non_finite,
        )
    except (OSError, json.JSONDecodeError) as failure:
        raise EvidenceError(f"Cannot parse strict JSON evidence {path}") from failure


def _operation(item: dict[str, Any]) -> str:
    benchmark = str(item.get("benchmark", ""))
    operation = benchmark.rsplit(".", 1)[-1]
    if operation not in EXPECTED_OPERATIONS:
        raise EvidenceError(f"Unexpected restart benchmark operation {operation!r}")
    return operation


def _restart_metric(item: dict[str, Any]) -> float:
    metrics = item.get("secondaryMetrics", {})
    matches = [
        metric
        for name, metric in metrics.items()
        if name == "providerRestarts" or name.endswith(".providerRestarts")
    ]
    if len(matches) != 1:
        raise EvidenceError(
            f"Expected one providerRestarts metric for {item.get('benchmark')}"
        )
    score = float(matches[0]["score"])
    if not math.isclose(score, 1.0, rel_tol=1e-9, abs_tol=1e-9):
        raise EvidenceError(
            f"Expected one provider restart for {item.get('benchmark')}, found {score}"
        )
    return score


def _jmh_base(
    item: dict[str, Any],
    *,
    backend: str,
    cache_state: str,
    evidence_repeat: str,
) -> tuple[str, ...]:
    params = item.get("params", {})
    deployment = f"{backend}-restart-{cache_state}-repeat-{evidence_repeat}"
    expected = {
        "backend": backend,
        "cacheState": cache_state,
        "deployment": deployment,
        "evidenceRepeat": evidence_repeat,
        "providerLifecycle": "restarted-provider",
        "pushes": "10",
    }
    for key, value in expected.items():
        if params.get(key) != value:
            raise EvidenceError(
                f"Unexpected JMH {key}={params.get(key)!r}; expected {value!r}"
            )
    maintenance = str(params.get("maintenanceMode", ""))
    if maintenance not in EXPECTED_MAINTENANCE:
        raise EvidenceError(f"Unexpected maintenance mode {maintenance!r}")
    operation = _operation(item)
    _restart_metric(item)
    return (
        operation,
        backend,
        "10",
        maintenance,
        cache_state,
        deployment,
        "restarted-provider",
        evidence_repeat,
    )


def _observation_base(item: dict[str, Any]) -> tuple[str, ...]:
    coordinate = item.get("coordinate", {})
    required = (
        "benchmarkMethod",
        "backend",
        "pushes",
        "maintenanceMode",
        "cacheState",
        "deployment",
        "providerLifecycle",
        "evidenceRepeat",
    )
    try:
        return tuple(str(coordinate[key]) for key in required)
    except KeyError as failure:
        raise EvidenceError(
            f"Telemetry coordinate is missing {failure.args[0]!r}: {coordinate}"
        ) from failure


def _assert_sorted_mapping(item: dict[str, Any], field: str) -> None:
    value = item.get(field)
    if not isinstance(value, dict):
        raise EvidenceError(f"Telemetry {field} is not an object")
    keys = list(value)
    if keys != sorted(keys):
        raise EvidenceError(f"Telemetry {field} keys are not deterministic: {keys}")


def _parse_timestamp(value: Any, field: str) -> dt.datetime:
    if not isinstance(value, str):
        raise EvidenceError(f"Telemetry {field} is not a timestamp")
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as failure:
        raise EvidenceError(f"Invalid telemetry {field}: {value!r}") from failure


def _scan_forbidden_tokens(roots: Iterable[Path]) -> None:
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            content = path.read_bytes().decode("utf-8", errors="replace")
            for token in FORBIDDEN_EVIDENCE_TOKENS:
                if token in content:
                    raise EvidenceError(f"Sensitive token {token!r} found in {path}")


def validate_evidence(
    root: Path,
    *,
    backend: str,
    cache_state: str,
    evidence_repeat: str,
    failsafe_root: Path | None = None,
    check_temporary_credentials: bool = True,
) -> dict[str, Any]:
    """Validate one exact backend/cache/repeat artifact directory."""
    if backend not in METRIC_BY_BACKEND:
        raise EvidenceError(f"Unsupported restart evidence backend {backend!r}")
    if cache_state not in {"cold", "warm"}:
        raise EvidenceError(f"Unsupported cache state {cache_state!r}")
    if evidence_repeat not in {"1", "2", "3"}:
        raise EvidenceError(f"Unsupported evidence repeat {evidence_repeat!r}")

    jmh_path = root / JMH_FILE
    telemetry_path = root / TELEMETRY_FILE
    ndjson_path = root / NDJSON_FILE
    output_path = root / OUTPUT_FILE
    for path in (jmh_path, telemetry_path, ndjson_path, output_path):
        if not path.is_file() or path.stat().st_size < 3:
            raise EvidenceError(f"Missing retained restart evidence {path}")

    jmh = _load_json(jmh_path)
    telemetry = _load_json(telemetry_path)
    if not isinstance(jmh, list):
        raise EvidenceError("Restart JMH evidence must be a JSON array")
    observations = telemetry.get("observations") if isinstance(telemetry, dict) else None
    if not isinstance(observations, list):
        raise EvidenceError("Restart telemetry must contain an observations array")
    if len(jmh) != 9:
        raise EvidenceError(f"Expected 9 JMH results, found {len(jmh)}")
    if len(observations) != 36:
        raise EvidenceError(
            f"Expected 36 phase observations, found {len(observations)}"
        )

    jmh_bases = {
        _jmh_base(
            item,
            backend=backend,
            cache_state=cache_state,
            evidence_repeat=evidence_repeat,
        )
        for item in jmh
    }
    if len(jmh_bases) != 9:
        raise EvidenceError("Duplicate JMH restart coordinates")

    required_metric = METRIC_BY_BACKEND[backend]
    phase_counts: collections.Counter[tuple[tuple[str, ...], str]] = (
        collections.Counter()
    )
    metric_by_phase: dict[str, list[int]] = collections.defaultdict(list)
    for observation in observations:
        if not isinstance(observation, dict):
            raise EvidenceError("Telemetry observation is not an object")
        for field in ("coordinate", "counters", "gauges", "metadata", "unsupported"):
            _assert_sorted_mapping(observation, field)
        coordinate = observation["coordinate"]
        phase = str(coordinate.get("phase", ""))
        if phase not in EXPECTED_PHASES:
            raise EvidenceError(f"Unexpected telemetry phase {phase!r}")
        base = _observation_base(observation)
        phase_counts[(base, phase)] += 1
        if observation.get("backend") != backend:
            raise EvidenceError(
                f"Unexpected telemetry backend {observation.get('backend')!r}"
            )
        expected_coordinate = {
            "databaseBackend": backend,
            "cacheState": cache_state,
            "providerLifecycle": "restarted-provider",
            "evidenceRepeat": evidence_repeat,
            "pushes": "10",
            "deployment": (
                f"{backend}-restart-{cache_state}-repeat-{evidence_repeat}"
            ),
            "measurementIteration": "1" if phase == "measurement" else "0",
        }
        for key, value in expected_coordinate.items():
            if coordinate.get(key) != value:
                raise EvidenceError(
                    f"Unexpected telemetry {key}={coordinate.get(key)!r}; "
                    f"expected {value!r}"
                )
        counters = observation["counters"]
        value = counters.get(required_metric)
        if not isinstance(value, int) or value < 0:
            raise EvidenceError(
                f"Missing non-negative {required_metric} for {coordinate}: {value!r}"
            )
        metric_by_phase[phase].append(value)
        if phase == "fixture-build" and value <= 0:
            raise EvidenceError(f"Fixture build produced no log delta: {coordinate}")
        if (
            phase == "maintenance"
            and coordinate.get("maintenanceMode") != "none"
            and value <= 0
        ):
            raise EvidenceError(f"Maintenance produced no log delta: {coordinate}")
        started = _parse_timestamp(observation.get("startedAt"), "startedAt")
        completed = _parse_timestamp(observation.get("completedAt"), "completedAt")
        if completed < started:
            raise EvidenceError(f"Negative telemetry window for {coordinate}")

    telemetry_bases = {base for base, _ in phase_counts}
    if telemetry_bases != jmh_bases:
        raise EvidenceError(
            "JMH and restart telemetry coordinate sets differ: "
            f"{telemetry_bases ^ jmh_bases}"
        )
    for base in jmh_bases:
        counts = {phase: phase_counts[(base, phase)] for phase in EXPECTED_PHASES}
        if counts != {phase: 1 for phase in EXPECTED_PHASES}:
            raise EvidenceError(
                f"Incomplete or duplicate restart phases for {base}: {counts}"
            )

    ndjson_lines = [
        line for line in ndjson_path.read_text(encoding="utf-8").splitlines() if line
    ]
    if len(ndjson_lines) != len(observations):
        raise EvidenceError(
            "NDJSON and aggregate count mismatch: "
            f"{len(ndjson_lines)} != {len(observations)}"
        )
    for line in ndjson_lines:
        try:
            json.loads(line, parse_constant=_reject_non_finite)
        except json.JSONDecodeError as failure:
            raise EvidenceError("Malformed restart telemetry NDJSON") from failure

    scan_roots = [root]
    if failsafe_root is not None:
        scan_roots.append(failsafe_root)
    _scan_forbidden_tokens(scan_roots)
    if check_temporary_credentials:
        leftovers = list(
            Path("/tmp").glob("jgit-storage-benchmark-connection-*.properties")
        )
        if leftovers:
            raise EvidenceError(f"Temporary credential files remain: {leftovers}")

    return {
        "backend": backend,
        "cacheState": cache_state,
        "evidenceRepeat": evidence_repeat,
        "jmhCoordinates": len(jmh_bases),
        "phaseObservations": len(observations),
        "metricRanges": {
            phase: {"minimum": min(values), "maximum": max(values)}
            for phase, values in sorted(metric_by_phase.items())
        },
    }


def _score_millis(item: dict[str, Any]) -> float:
    metric = item.get("primaryMetric", {})
    unit = str(metric.get("scoreUnit", ""))
    if unit not in UNIT_TO_MILLIS:
        raise EvidenceError(f"Unsupported restart score unit {unit!r}")
    score = float(metric.get("score")) * UNIT_TO_MILLIS[unit]
    if not math.isfinite(score) or score < 0:
        raise EvidenceError(f"Invalid restart score {score}")
    return score


def aggregate_evidence(
    evidence_root: Path,
    output_root: Path,
    *,
    event_name: str,
) -> dict[str, Any]:
    """Aggregate exact result files into repeat dispersion evidence."""
    regular = event_name not in {"schedule", "workflow_dispatch"}
    expected_files = 2 if regular else 12
    expected_repeats = {"1"} if regular else {"1", "2", "3"}
    files = sorted(evidence_root.rglob(JMH_FILE))
    if len(files) != expected_files:
        raise EvidenceError(
            f"Expected {expected_files} restart result files, found {len(files)}"
        )

    observations: dict[tuple[str, ...], float] = {}
    groups: dict[tuple[str, ...], list[tuple[str, float]]] = (
        collections.defaultdict(list)
    )
    for path in files:
        jmh = _load_json(path)
        if not isinstance(jmh, list) or len(jmh) != 9:
            raise EvidenceError(f"Expected 9 JMH results in {path}")
        for item in jmh:
            params = item.get("params", {})
            backend = str(params.get("backend", ""))
            cache_state = str(params.get("cacheState", ""))
            repeat = str(params.get("evidenceRepeat", ""))
            if backend not in METRIC_BY_BACKEND:
                raise EvidenceError(f"Unexpected aggregate backend {backend!r}")
            if cache_state not in {"cold", "warm"}:
                raise EvidenceError(f"Unexpected aggregate cache state {cache_state!r}")
            if repeat not in expected_repeats:
                raise EvidenceError(f"Unexpected aggregate repeat {repeat!r}")
            if params.get("providerLifecycle") != "restarted-provider":
                raise EvidenceError("Aggregate contains a non-restarted provider result")
            if params.get("pushes") != "10":
                raise EvidenceError("Aggregate contains a non-10-push result")
            operation = _operation(item)
            maintenance = str(params.get("maintenanceMode", ""))
            if maintenance not in EXPECTED_MAINTENANCE:
                raise EvidenceError(
                    f"Unexpected aggregate maintenance mode {maintenance!r}"
                )
            coordinate = (
                backend,
                cache_state,
                repeat,
                operation,
                maintenance,
            )
            if coordinate in observations:
                raise EvidenceError(f"Duplicate restart coordinate {coordinate}")
            score = _score_millis(item)
            observations[coordinate] = score
            groups[(backend, cache_state, operation, maintenance)].append(
                (repeat, score)
            )

    expected_observations = expected_files * 9
    if len(observations) != expected_observations:
        raise EvidenceError(
            f"Expected {expected_observations} JMH observations, "
            f"found {len(observations)}"
        )

    summary: list[dict[str, Any]] = []
    for key, values in sorted(groups.items()):
        repeats = {repeat for repeat, _ in values}
        if repeats != expected_repeats:
            raise EvidenceError(f"Incomplete repeat set for {key}: {sorted(repeats)}")
        scores = [score for _, score in sorted(values, key=lambda item: int(item[0]))]
        mean = statistics.fmean(scores)
        standard_deviation = statistics.pstdev(scores) if len(scores) > 1 else 0.0
        summary.append(
            {
                "backend": key[0],
                "cacheState": key[1],
                "operation": key[2],
                "maintenanceMode": key[3],
                "repeatCount": len(scores),
                "repeats": sorted(repeats, key=int),
                "scoresMillis": scores,
                "meanMillis": mean,
                "minimumMillis": min(scores),
                "maximumMillis": max(scores),
                "populationStddevMillis": standard_deviation,
                "coefficientOfVariationPercent": (
                    0.0 if mean == 0.0 else standard_deviation * 100.0 / mean
                ),
            }
        )

    report = {
        "schemaVersion": 1,
        "event": event_name,
        "automaticMaintenanceChanged": False,
        "groups": summary,
    }
    output_root.mkdir(parents=True, exist_ok=True)
    (output_root / SUMMARY_JSON).write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    lines = [
        "# Repository-aging restart reproducibility",
        "",
        "Provider restart is measured before every retained read coordinate. "
        "This report is evidence only and does not change an automatic maintenance policy.",
        "",
        "| Backend | Cache | Operation | Maintenance | Repeats | Mean ms | CV % | Min–max ms |",
        "|---|---|---|---|---:|---:|---:|---:|",
    ]
    for item in summary:
        lines.append(
            "| {backend} | {cacheState} | {operation} | {maintenanceMode} | "
            "{repeatCount} | {meanMillis:.6f} | "
            "{coefficientOfVariationPercent:.2f} | "
            "{minimumMillis:.6f}–{maximumMillis:.6f} |".format(**item)
        )
    (output_root / SUMMARY_MARKDOWN).write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )
    return report


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--root", type=Path, required=True)
    validate.add_argument("--backend", choices=sorted(METRIC_BY_BACKEND), required=True)
    validate.add_argument("--cache-state", choices=("cold", "warm"), required=True)
    validate.add_argument("--evidence-repeat", choices=("1", "2", "3"), required=True)
    validate.add_argument("--failsafe-root", type=Path)

    aggregate = subparsers.add_parser("aggregate")
    aggregate.add_argument("--evidence-root", type=Path, required=True)
    aggregate.add_argument("--output-root", type=Path, required=True)
    aggregate.add_argument("--event-name", required=True)
    return parser


def main(argv: list[str] | None = None) -> None:
    arguments = _parser().parse_args(argv)
    if arguments.command == "validate":
        result = validate_evidence(
            arguments.root,
            backend=arguments.backend,
            cache_state=arguments.cache_state,
            evidence_repeat=arguments.evidence_repeat,
            failsafe_root=arguments.failsafe_root,
        )
        print(json.dumps(result, sort_keys=True))
        return
    report = aggregate_evidence(
        arguments.evidence_root,
        arguments.output_root,
        event_name=arguments.event_name,
    )
    print(
        f"Aggregated restart evidence into {len(report['groups'])} "
        "reproducibility groups"
    )


if __name__ == "__main__":
    main()
