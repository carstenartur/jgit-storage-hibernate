#!/usr/bin/env python3
"""Convert pack-storage-layout JMH JSON into chart and compatibility evidence."""

from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

CANONICAL_UNIT = "ms/op"
CURRENT_CHUNK_KIB = 1024
CURRENT_INLINE_KIB = 256
CHUNK_KIB = {256, 1024, 2048, 4096}
INLINE_KIB = {64, 256, 1024}
RETAINED_MIB = {8, 16, 32}
READ_AHEAD_KIB = {256, 1024, 4096, 16384}
OPERATIONS = {"write", "sequential-read", "short-read", "random-read"}
SPARSE_OPERATIONS = {"short-read", "random-read"}


def _finite(value: Any, name: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {name}") from failure
    if not math.isfinite(number):
        raise ValueError(f"Non-finite {name}")
    return number


def _optional_finite(value: Any, name: str) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(f"Malformed {name}") from failure
    return number if math.isfinite(number) else None


def _milliseconds(score: float, unit: str) -> float:
    factors = {
        "ns/op": 1e-6,
        "us/op": 1e-3,
        "µs/op": 1e-3,
        "ms/op": 1.0,
        "s/op": 1000.0,
    }
    try:
        value = score * factors[unit]
    except KeyError as failure:
        raise ValueError(f"Unsupported pack-layout score unit: {unit!r}") from failure
    if not math.isfinite(value):
        raise ValueError("Non-finite pack-layout score")
    return value


def _secondary(
    result: dict[str, Any],
    name: str,
    default: float = 0.0,
    *,
    require_constant: bool = False,
) -> float:
    """Return one per-iteration AuxCounters value instead of JMH's iteration sum."""
    for key, metric in result.get("secondaryMetrics", {}).items():
        if key != name and not key.endswith("." + name):
            continue
        if not isinstance(metric, dict):
            raise ValueError(f"Malformed secondary metric {name!r}")

        if "rawData" in metric:
            raw_data = metric["rawData"]
            if not isinstance(raw_data, list) or not raw_data:
                raise ValueError(f"Malformed rawData for secondary metric {name!r}")
            samples: list[float] = []
            iteration_count: int | None = None
            for fork in raw_data:
                if not isinstance(fork, list) or not fork:
                    raise ValueError(
                        f"Malformed rawData for secondary metric {name!r}"
                    )
                if iteration_count is None:
                    iteration_count = len(fork)
                elif len(fork) != iteration_count:
                    raise ValueError(
                        f"Inconsistent rawData fork lengths for secondary metric {name!r}"
                    )
                for raw_value in fork:
                    value = _finite(
                        raw_value, f"rawData for secondary metric {name!r}"
                    )
                    samples.append(value)
            if require_constant and any(
                not math.isclose(value, samples[0], rel_tol=0.0, abs_tol=1e-6)
                for value in samples[1:]
            ):
                raise ValueError(
                    f"Secondary metric {name!r} changed across JMH iterations: "
                    f"{samples!r}"
                )
            return math.fsum(samples) / len(samples)

        value = _finite(metric.get("score", default), f"secondary metric {name!r}")
        return value
    return default


def _profiler(result: dict[str, Any], suffix: str) -> float | None:
    for key, metric in result.get("secondaryMetrics", {}).items():
        if not key.endswith(suffix):
            continue
        if not isinstance(metric, dict):
            raise ValueError(f"Malformed profiler metric {key!r}")
        return _optional_finite(metric.get("score"), f"profiler metric {key!r}")
    return None


def _percentile(metric: dict[str, Any], percentile: str, fallback: float) -> float:
    values = metric.get("scorePercentiles")
    if not isinstance(values, dict) or percentile not in values:
        return fallback
    score = _finite(values[percentile], f"primary percentile {percentile}")
    return _milliseconds(score, str(metric["scoreUnit"]))


def _row(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params", {})
    operation = str(params.get("operation", ""))
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported pack-layout operation: {operation!r}")
    backend = str(params.get("backend", ""))
    if not backend:
        raise ValueError("Pack-layout result is missing backend")
    payload_kib = int(params["payloadKiB"])
    chunk_kib = int(params["chunkKiB"])
    inline_kib = int(params["inlineKiB"])
    retained_mib = int(params["retainedMiB"])
    read_ahead_kib = int(params["readAheadKiB"])
    if chunk_kib not in CHUNK_KIB:
        raise ValueError(f"Unsupported chunkKiB: {chunk_kib}")
    if inline_kib not in INLINE_KIB:
        raise ValueError(f"Unsupported inlineKiB: {inline_kib}")
    if retained_mib not in RETAINED_MIB:
        raise ValueError(f"Unsupported retainedMiB: {retained_mib}")
    if read_ahead_kib not in READ_AHEAD_KIB:
        raise ValueError(f"Unsupported readAheadKiB: {read_ahead_kib}")

    metric = result["primaryMetric"]
    if not isinstance(metric, dict):
        raise ValueError("Malformed primary metric")
    unit = str(metric["scoreUnit"])
    score = _milliseconds(_finite(metric["score"], "primary score"), unit)
    raw_error = _optional_finite(metric.get("scoreError"), "primary score error")
    error = None if raw_error is None else _milliseconds(raw_error, unit)
    configured_budget = int(
        round(
            _secondary(
                result, "configuredRetainedBudgetBytes", require_constant=True
            )
        )
    )
    actual_retained = int(
        round(_secondary(result, "actualRetainedChunkBytes", require_constant=True))
    )
    configured_chunk = int(
        round(_secondary(result, "configuredChunkBytes", require_constant=True))
    )
    configured_inline = int(
        round(
            _secondary(
                result, "configuredInlineThresholdBytes", require_constant=True
            )
        )
    )
    configured_read_ahead = int(
        round(_secondary(result, "configuredReadAheadBytes", require_constant=True))
    )
    if configured_chunk != chunk_kib * 1024:
        raise ValueError("JMH parameter and configured chunk bytes disagree")
    if configured_inline != inline_kib * 1024:
        raise ValueError("JMH parameter and configured inline threshold disagree")
    if configured_budget != retained_mib * 1024 * 1024:
        raise ValueError("JMH parameter and configured retained-byte budget disagree")
    if configured_read_ahead != read_ahead_kib * 1024:
        raise ValueError("JMH parameter and configured read-ahead bytes disagree")
    if (
        actual_retained > configured_budget
        or configured_budget - actual_retained >= configured_chunk
    ):
        raise ValueError(
            "Retained chunk bytes must round the configured byte budget down by "
            "less than one chunk"
        )

    logical_payload = int(
        round(_secondary(result, "logicalPayloadBytes", require_constant=True))
    )
    if logical_payload != payload_kib * 1024:
        raise ValueError("JMH parameter and logical payload bytes disagree")
    chunk_rows = int(
        round(_secondary(result, "chunkRows", require_constant=True))
    )
    expected_chunk_rows = (
        0
        if logical_payload <= inline_kib * 1024
        else math.ceil(logical_payload / configured_chunk)
    )
    if chunk_rows != expected_chunk_rows:
        raise ValueError(
            f"Chunk row count mismatch: expected {expected_chunk_rows}, "
            f"got {chunk_rows}"
        )

    return {
        "backend": backend,
        "deployment": str(params.get("deployment", "unknown")),
        "operation": operation,
        "payloadKiB": payload_kib,
        "chunkKiB": chunk_kib,
        "inlineKiB": inline_kib,
        "retainedMiB": retained_mib,
        "readAheadKiB": read_ahead_kib,
        "scoreMillis": score,
        "scoreErrorMillis": error,
        "p50Millis": _percentile(metric, "50.0", score),
        "p95Millis": _percentile(metric, "95.0", score),
        "p99Millis": _percentile(metric, "99.0", score),
        "configuredRetainedBudgetBytes": configured_budget,
        "actualRetainedChunkBytes": actual_retained,
        "chunksPerBatch": int(
            round(_secondary(result, "chunksPerBatch", require_constant=True))
        ),
        "readAheadChunks": int(
            round(_secondary(result, "readAheadChunks", require_constant=True))
        ),
        "proposedLayoutVersion": int(
            round(_secondary(result, "proposedLayoutVersion", require_constant=True))
        ),
        "packRows": int(
            round(_secondary(result, "packRows", require_constant=True))
        ),
        "chunkRows": chunk_rows,
        "jdbcBatchExecutions": int(
            round(_secondary(result, "jdbcBatchExecutions"))
        ),
        "jdbcStatementExecutions": int(
            round(_secondary(result, "jdbcStatementExecutions"))
        ),
        "preparedStatements": int(
            round(_secondary(result, "preparedStatements"))
        ),
        "hibernateQueries": int(
            round(_secondary(result, "hibernateQueries"))
        ),
        "flushes": int(round(_secondary(result, "flushes"))),
        "databasePayloadBytes": int(
            round(_secondary(result, "databasePayloadBytes"))
        ),
        "logicalBytesConsumed": int(
            round(_secondary(result, "logicalBytesConsumed"))
        ),
        "overfetchBytes": int(round(_secondary(result, "overfetchBytes"))),
        "allocationBytesPerOperation": _profiler(result, "gc.alloc.rate.norm"),
        "gcCount": _profiler(result, "gc.count"),
        "gcTimeMillis": _profiler(result, "gc.time"),
        "jdkVersion": str(result.get("jdkVersion", "unknown")),
    }


def convert(results: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [_row(result) for result in results]
    if not rows:
        raise ValueError("Pack-layout JMH result is empty")

    keyed: dict[tuple[Any, ...], dict[str, Any]] = {}
    for row in rows:
        key = _condition_key(row) + (row["chunkKiB"], row["inlineKiB"])
        if key in keyed:
            raise ValueError(f"Duplicate pack-layout result: {key!r}")
        keyed[key] = row

    evidence: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    for row in rows:
        baseline = keyed.get(
            _condition_key(row) + (CURRENT_CHUNK_KIB, CURRENT_INLINE_KIB)
        )
        relative = None
        if baseline is not None and baseline["scoreMillis"] > 0.0:
            relative = (
                (baseline["scoreMillis"] - row["scoreMillis"])
                * 100.0
                / baseline["scoreMillis"]
            )
        enriched = {**row, "relativeToCurrentPercent": relative}
        evidence.append(enriched)
        comparisons.append(
            {
                "name": (
                    f"Pack layout {row['operation']} — {row['backend']}, "
                    f"{row['payloadKiB']} KiB, chunk {row['chunkKiB']} KiB, "
                    f"inline {row['inlineKiB']} KiB, retained "
                    f"{row['retainedMiB']} MiB, read-ahead "
                    f"{row['readAheadKiB']} KiB"
                ),
                "unit": CANONICAL_UNIT,
                "value": row["scoreMillis"],
                "range": row["scoreErrorMillis"],
                "extra": "\n".join(
                    [
                        (
                            "p50/p95/p99: "
                            f"{row['p50Millis']:.6f} / "
                            f"{row['p95Millis']:.6f} / "
                            f"{row['p99Millis']:.6f} ms"
                        ),
                        f"Rows: {row['packRows'] + row['chunkRows']}",
                        f"Chunk rows: {row['chunkRows']}",
                        f"Chunks per batch: {row['chunksPerBatch']}",
                        (
                            "Retained chunk bytes: "
                            f"{row['actualRetainedChunkBytes']}"
                        ),
                        f"Read-ahead chunks: {row['readAheadChunks']}",
                        (
                            "Fetched/consumed/overfetch: "
                            f"{row['databasePayloadBytes']} / "
                            f"{row['logicalBytesConsumed']} / "
                            f"{row['overfetchBytes']}"
                        ),
                        (
                            "Current-layout comparison: unavailable"
                            if relative is None
                            else (
                                "Current-layout improvement: "
                                f"{relative:.3f}%"
                            )
                        ),
                    ]
                ),
            }
        )

    candidates = _layout_candidates(evidence)
    backends = sorted({row["backend"] for row in evidence})
    cross_database = "postgresql" in backends and "sqlserver" in backends
    eligible = [candidate for candidate in candidates if candidate["eligible"]]
    decision = (
        "candidate-layout-ready-for-versioned-format-design"
        if cross_database and eligible
        else "retain-current-layout-pending-postgresql-and-sqlserver-evidence"
    )
    return {
        "schemaVersion": 1,
        "decision": decision,
        "productionDefaultsChanged": False,
        "currentLayout": {
            "chunkKiB": CURRENT_CHUNK_KIB,
            "inlineKiB": CURRENT_INLINE_KIB,
        },
        "backends": backends,
        "comparison": sorted(comparisons, key=lambda item: item["name"]),
        "evidence": evidence,
        "layoutCandidates": candidates,
        "compatibility": {
            "legacyRowsRemainOneMiB": True,
            "inlineStorageDetectedByPayloadColumn": True,
            "variableChunkRowsRequirePersistedChunkSizeOrLayoutVersion": True,
        },
    }


def _condition_key(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        row["backend"],
        row["deployment"],
        row["operation"],
        row["payloadKiB"],
        row["retainedMiB"],
        row["readAheadKiB"],
    )


def _layout_candidates(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[int, int], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row["relativeToCurrentPercent"] is not None:
            grouped[(row["chunkKiB"], row["inlineKiB"])].append(row)

    candidates: list[dict[str, Any]] = []
    for (chunk_kib, inline_kib), values in sorted(grouped.items()):
        by_backend: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for value in values:
            by_backend[value["backend"]].append(value)
        backend_summaries = []
        for backend, backend_values in sorted(by_backend.items()):
            write = [
                value["relativeToCurrentPercent"]
                for value in backend_values
                if value["operation"] == "write"
            ]
            sequential = [
                value["relativeToCurrentPercent"]
                for value in backend_values
                if value["operation"] == "sequential-read"
            ]
            sparse = [
                value["relativeToCurrentPercent"]
                for value in backend_values
                if value["operation"] in SPARSE_OPERATIONS
            ]
            backend_summaries.append(
                {
                    "backend": backend,
                    "writeMedianImprovementPercent": _median(write),
                    "sequentialMedianImprovementPercent": _median(sequential),
                    "worstSparseImprovementPercent": (
                        min(sparse) if sparse else None
                    ),
                    "comparisonCount": len(backend_values),
                }
            )
        eligible = (
            chunk_kib != CURRENT_CHUNK_KIB
            or inline_kib != CURRENT_INLINE_KIB
        ) and len(backend_summaries) >= 2 and all(
            summary["writeMedianImprovementPercent"] is not None
            and summary["writeMedianImprovementPercent"] > 0.0
            and summary["sequentialMedianImprovementPercent"] is not None
            and summary["sequentialMedianImprovementPercent"] > 0.0
            and summary["worstSparseImprovementPercent"] is not None
            and summary["worstSparseImprovementPercent"] >= -5.0
            for summary in backend_summaries
        )
        candidates.append(
            {
                "chunkKiB": chunk_kib,
                "inlineKiB": inline_kib,
                "eligible": eligible,
                "backendEvidence": backend_summaries,
                "reason": (
                    "Write and sequential-read gains with at most five-percent "
                    "sparse-read regression on both production databases."
                    if eligible
                    else (
                        "Insufficient cross-database net benefit; keep the "
                        "current production layout."
                    )
                ),
            }
        )
    return candidates


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def _write_markdown(report: dict[str, Any], target: Path) -> None:
    lines = [
        "# Pack storage layout evidence",
        "",
        f"Decision: `{report['decision']}`.",
        "",
        (
            "The converter never changes production settings. Candidate layouts "
            "require matching PostgreSQL and SQL Server evidence plus a versioned "
            "compatibility design."
        ),
        "",
        "| Chunk KiB | Inline KiB | Eligible | Reason |",
        "|---:|---:|---|---|",
    ]
    for candidate in report["layoutCandidates"]:
        lines.append(
            f"| {candidate['chunkKiB']} | {candidate['inlineKiB']} | "
            f"{'yes' if candidate['eligible'] else 'no'} | "
            f"{candidate['reason']} |"
        )
    lines.extend(
        [
            "",
            (
                "Legacy rows without explicit layout metadata remain one-MiB "
                "chunk rows. Inline rows remain distinguishable through the "
                "existing payload column. No existing repository is rewritten "
                "by this benchmark."
            ),
        ]
    )
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _strict_json(value: Any) -> str:
    return (
        json.dumps(
            value,
            indent=2,
            ensure_ascii=False,
            allow_nan=False,
        )
        + "\n"
    )


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-pack-storage-layout.py "
            "<jmh-result.json> <output-directory>"
        )
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"JMH result must be a JSON array: {source}")
    report = convert(raw)
    output.mkdir(parents=True, exist_ok=True)
    (output / "pack-storage-layout-comparison.json").write_text(
        _strict_json(report["comparison"]),
        encoding="utf-8",
    )
    (output / "pack-storage-layout-evidence.json").write_text(
        _strict_json(
            {key: value for key, value in report.items() if key != "comparison"}
        ),
        encoding="utf-8",
    )
    _write_markdown(report, output / "pack-storage-layout-evidence.md")


if __name__ == "__main__":
    main()
