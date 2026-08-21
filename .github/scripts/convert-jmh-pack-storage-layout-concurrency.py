#!/usr/bin/env python3
"""Convert 1/4/16-worker pack-layout JMH JSON into bounded evidence."""

from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

CURRENT_CHUNK_KIB = 1024
CURRENT_INLINE_KIB = 256
CONCURRENCY_LEVELS = {1, 4, 16}
OPERATIONS = {"write", "sequential-read", "short-read", "random-read"}
SPARSE_OPERATIONS = {"short-read", "random-read"}


def _milliseconds(value: float, unit: str) -> float:
    factors = {
        "ns/op": 1e-6,
        "us/op": 1e-3,
        "µs/op": 1e-3,
        "ms/op": 1.0,
        "s/op": 1000.0,
    }
    try:
        return value * factors[unit]
    except KeyError as failure:
        raise ValueError(f"Unsupported concurrency score unit: {unit!r}") from failure


def _secondary(
    result: dict[str, Any],
    name: str,
    *,
    require_constant: bool = False,
) -> float:
    """Return one per-iteration aggregate instead of JMH's iteration sum."""
    for key, metric in result.get("secondaryMetrics", {}).items():
        if key != name and not key.endswith("." + name):
            continue
        if not isinstance(metric, dict):
            raise ValueError(f"Malformed concurrency secondary metric {name!r}")

        if "rawData" in metric:
            raw_data = metric["rawData"]
            if not isinstance(raw_data, list) or not raw_data:
                raise ValueError(
                    f"Malformed rawData for concurrency secondary metric {name!r}"
                )
            samples: list[float] = []
            for fork in raw_data:
                if not isinstance(fork, list) or not fork:
                    raise ValueError(
                        f"Malformed rawData for concurrency secondary metric {name!r}"
                    )
                for raw_value in fork:
                    try:
                        value = float(raw_value)
                    except (TypeError, ValueError) as failure:
                        raise ValueError(
                            f"Malformed rawData for concurrency secondary metric {name!r}"
                        ) from failure
                    if not math.isfinite(value):
                        raise ValueError(
                            f"Non-finite rawData for concurrency secondary metric {name!r}"
                        )
                    samples.append(value)
            if require_constant and any(
                not math.isclose(value, samples[0], rel_tol=0.0, abs_tol=1e-6)
                for value in samples[1:]
            ):
                raise ValueError(
                    f"Concurrency secondary metric {name!r} changed across JMH iterations: {samples!r}"
                )
            return math.fsum(samples) / len(samples)

        try:
            value = float(metric.get("score", 0.0))
        except (TypeError, ValueError) as failure:
            raise ValueError(
                f"Malformed concurrency secondary metric {name!r}"
            ) from failure
        if not math.isfinite(value):
            raise ValueError(f"Non-finite concurrency secondary metric {name!r}")
        return value
    raise ValueError(f"Concurrency result is missing secondary metric {name!r}")


def _per_worker(
    result: dict[str, Any],
    name: str,
    threads: int,
    *,
    require_constant: bool = False,
) -> float:
    """Normalize JMH EVENTS counters per iteration and active worker thread."""
    return _secondary(result, name, require_constant=require_constant) / threads


def _per_worker_int(result: dict[str, Any], name: str, threads: int) -> int:
    value = _per_worker(result, name, threads, require_constant=True)
    rounded = round(value)
    if not math.isclose(value, rounded, rel_tol=0.0, abs_tol=1e-6):
        raise ValueError(
            f"Concurrency structural metric {name!r} is not integral per worker: {value}"
        )
    return int(rounded)


def _percentile(metric: dict[str, Any], name: str, fallback: float) -> float:
    values = metric.get("scorePercentiles")
    if not isinstance(values, dict) or name not in values:
        return fallback
    return _milliseconds(float(values[name]), str(metric["scoreUnit"]))


def _row(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params", {})
    operation = str(params.get("operation", ""))
    if operation not in OPERATIONS:
        raise ValueError(f"Unsupported concurrency operation: {operation!r}")
    backend = str(params.get("backend", ""))
    if not backend:
        raise ValueError("Concurrency result is missing backend")
    concurrency = int(params["concurrency"])
    if concurrency not in CONCURRENCY_LEVELS:
        raise ValueError(f"Unsupported concurrency level: {concurrency}")
    threads = int(result.get("threads", 0))
    if threads != concurrency:
        raise ValueError(
            f"JMH threads ({threads}) do not match concurrency parameter ({concurrency})"
        )

    chunk_kib = int(params["chunkKiB"])
    inline_kib = int(params["inlineKiB"])
    payload_kib = int(params["payloadKiB"])
    retained_mib = int(params["retainedMiB"])
    read_ahead_kib = int(params["readAheadKiB"])

    metric = result["primaryMetric"]
    score = _milliseconds(float(metric["score"]), str(metric["scoreUnit"]))
    if not math.isfinite(score) or score <= 0.0:
        raise ValueError("Concurrency latency must be finite and positive")

    configured_concurrency = _per_worker_int(
        result, "configuredConcurrency", threads
    )
    configured_chunk_bytes = _per_worker_int(
        result, "configuredChunkBytes", threads
    )
    configured_inline_bytes = _per_worker_int(
        result, "configuredInlineThresholdBytes", threads
    )
    configured_budget = _per_worker_int(
        result, "configuredRetainedBudgetBytes", threads
    )
    actual_retained = _per_worker_int(
        result, "actualRetainedChunkBytes", threads
    )
    configured_read_ahead = _per_worker_int(
        result, "configuredReadAheadBytes", threads
    )
    logical_payload = _per_worker_int(result, "logicalPayloadBytes", threads)

    if configured_concurrency != concurrency:
        raise ValueError("JMH concurrency parameter and normalized counter disagree")
    if configured_chunk_bytes != chunk_kib * 1024:
        raise ValueError("JMH chunk parameter and normalized counter disagree")
    if configured_inline_bytes != inline_kib * 1024:
        raise ValueError("JMH inline parameter and normalized counter disagree")
    if configured_budget != retained_mib * 1024 * 1024:
        raise ValueError("JMH retained-byte parameter and normalized counter disagree")
    if configured_read_ahead != read_ahead_kib * 1024:
        raise ValueError("JMH read-ahead parameter and normalized counter disagree")
    if logical_payload != payload_kib * 1024:
        raise ValueError("JMH payload parameter and normalized counter disagree")
    if actual_retained > configured_budget:
        raise ValueError("Concurrent writer retained more than its configured byte budget")

    return {
        "backend": backend,
        "deployment": str(params.get("deployment", "unknown")),
        "operation": operation,
        "payloadKiB": payload_kib,
        "chunkKiB": chunk_kib,
        "inlineKiB": inline_kib,
        "retainedMiB": retained_mib,
        "readAheadKiB": read_ahead_kib,
        "concurrency": concurrency,
        "threads": threads,
        "scoreMillis": score,
        "scoreErrorMillis": _milliseconds(
            float(metric.get("scoreError", 0.0)), str(metric["scoreUnit"])
        ),
        "p50Millis": _percentile(metric, "50.0", score),
        "p95Millis": _percentile(metric, "95.0", score),
        "p99Millis": _percentile(metric, "99.0", score),
        "estimatedCapacityOpsPerSecond": concurrency * 1000.0 / score,
        "configuredRetainedBudgetBytes": configured_budget,
        "actualRetainedChunkBytes": actual_retained,
        "readAheadChunks": _per_worker_int(result, "readAheadChunks", threads),
        "chunksPerBatch": _per_worker_int(result, "chunksPerBatch", threads),
        "logicalPayloadBytes": logical_payload,
        "chunkRows": _per_worker_int(result, "chunkRows", threads),
        "jdbcBatchExecutionsPerWorker": _per_worker(
            result, "jdbcBatchExecutions", threads
        ),
        "jdbcStatementExecutionsPerWorker": _per_worker(
            result, "jdbcStatementExecutions", threads
        ),
        "databasePayloadBytesPerWorker": _per_worker(
            result, "databasePayloadBytes", threads
        ),
        "logicalBytesConsumedPerWorker": _per_worker(
            result, "logicalBytesConsumed", threads
        ),
        "overfetchBytesPerWorker": _per_worker(result, "overfetchBytes", threads),
        "jdkVersion": str(result.get("jdkVersion", "unknown")),
    }


def _condition(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        row["backend"],
        row["operation"],
        row["payloadKiB"],
        row["retainedMiB"],
        row["readAheadKiB"],
        row["concurrency"],
    )


def _layout_condition(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        row["backend"],
        row["operation"],
        row["payloadKiB"],
        row["retainedMiB"],
        row["readAheadKiB"],
        row["chunkKiB"],
        row["inlineKiB"],
    )


def convert(results: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [_row(result) for result in results]
    if not rows:
        raise ValueError("Concurrency JMH result is empty")

    keyed: dict[tuple[Any, ...], dict[str, Any]] = {}
    for row in rows:
        key = _condition(row) + (row["chunkKiB"], row["inlineKiB"])
        if key in keyed:
            raise ValueError(f"Duplicate concurrency result: {key!r}")
        keyed[key] = row

    evidence: list[dict[str, Any]] = []
    for row in rows:
        baseline = keyed.get(
            _condition(row) + (CURRENT_CHUNK_KIB, CURRENT_INLINE_KIB)
        )
        if baseline is None:
            raise ValueError(
                "Missing current-layout concurrency baseline for "
                + repr(_condition(row))
            )
        relative = (
            baseline["scoreMillis"] - row["scoreMillis"]
        ) * 100.0 / baseline["scoreMillis"]
        evidence.append({**row, "relativeToCurrentPercent": relative})

    single_worker: dict[tuple[Any, ...], dict[str, Any]] = {}
    for row in evidence:
        if row["concurrency"] == 1:
            single_worker[_layout_condition(row)] = row
    for row in evidence:
        baseline = single_worker.get(_layout_condition(row))
        if baseline is None:
            raise ValueError(
                "Missing one-worker scaling baseline for "
                + repr(_layout_condition(row))
            )
        row["scalingEfficiencyPercent"] = (
            baseline["scoreMillis"] * 100.0 / row["scoreMillis"]
        )

    candidates = _candidates(evidence)
    comparison = [
        {
            "name": (
                f"Pack layout concurrency {row['operation']} — {row['backend']}, "
                f"{row['concurrency']} workers, chunk {row['chunkKiB']} KiB, "
                f"inline {row['inlineKiB']} KiB"
            ),
            "unit": "ms/op",
            "value": row["scoreMillis"],
            "range": row["scoreErrorMillis"],
            "extra": "\n".join(
                [
                    f"p50/p95/p99: {row['p50Millis']:.6f} / {row['p95Millis']:.6f} / {row['p99Millis']:.6f} ms",
                    f"Estimated concurrent capacity: {row['estimatedCapacityOpsPerSecond']:.3f} ops/s",
                    f"Scaling efficiency versus one worker: {row['scalingEfficiencyPercent']:.3f}%",
                    f"Current-layout improvement: {row['relativeToCurrentPercent']:.3f}%",
                    f"Chunk rows per worker: {row['chunkRows']}",
                    f"Retained chunk bytes per writer: {row['actualRetainedChunkBytes']}",
                    f"JDBC batch/statement executions per worker: {row['jdbcBatchExecutionsPerWorker']:.3f} / {row['jdbcStatementExecutionsPerWorker']:.3f}",
                    f"Fetched/consumed/overfetch per worker: {row['databasePayloadBytesPerWorker']:.0f} / {row['logicalBytesConsumedPerWorker']:.0f} / {row['overfetchBytesPerWorker']:.0f}",
                ]
            ),
        }
        for row in evidence
    ]
    return {
        "schemaVersion": 1,
        "decision": "retain-current-layout-pending-full-cross-database-evidence",
        "productionDefaultsChanged": False,
        "currentLayout": {
            "chunkKiB": CURRENT_CHUNK_KIB,
            "inlineKiB": CURRENT_INLINE_KIB,
        },
        "concurrencyLevels": sorted({row["concurrency"] for row in evidence}),
        "comparison": sorted(comparison, key=lambda item: item["name"]),
        "evidence": evidence,
        "concurrencyCandidates": candidates,
    }


def _candidates(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[int, int], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        grouped[(row["chunkKiB"], row["inlineKiB"])].append(row)

    candidates: list[dict[str, Any]] = []
    for (chunk_kib, inline_kib), values in sorted(grouped.items()):
        write_rows = [row for row in values if row["operation"] == "write"]
        sequential_rows = [
            row for row in values if row["operation"] == "sequential-read"
        ]
        sparse_rows = [
            row for row in values if row["operation"] in SPARSE_OPERATIONS
        ]
        writes = [row["relativeToCurrentPercent"] for row in write_rows]
        sequential = [
            row["relativeToCurrentPercent"] for row in sequential_rows
        ]
        sparse = [row["relativeToCurrentPercent"] for row in sparse_rows]
        scaling = [row["scalingEfficiencyPercent"] for row in values]
        complete_write_levels = {
            row["concurrency"] for row in write_rows
        } == CONCURRENCY_LEVELS
        complete_sequential_levels = {
            row["concurrency"] for row in sequential_rows
        } == CONCURRENCY_LEVELS
        complete_sparse_levels = {
            row["concurrency"] for row in sparse_rows
        } == CONCURRENCY_LEVELS
        complete_levels = (
            complete_write_levels
            and complete_sequential_levels
            and complete_sparse_levels
        )
        observational_candidate = (
            (chunk_kib != CURRENT_CHUNK_KIB or inline_kib != CURRENT_INLINE_KIB)
            and complete_levels
            and min(writes) > 0.0
            and min(sequential) > 0.0
            and min(sparse) >= -5.0
        )
        candidates.append(
            {
                "chunkKiB": chunk_kib,
                "inlineKiB": inline_kib,
                "completeConcurrencyLevels": complete_levels,
                "completeWriteLevels": complete_write_levels,
                "completeSequentialReadLevels": complete_sequential_levels,
                "completeSparseReadLevels": complete_sparse_levels,
                "observationalCandidate": observational_candidate,
                "worstWriteImprovementPercent": min(writes) if writes else None,
                "worstSequentialImprovementPercent": (
                    min(sequential) if sequential else None
                ),
                "worstSparseImprovementPercent": min(sparse) if sparse else None,
                "worstScalingEfficiencyPercent": min(scaling) if scaling else None,
                "reason": (
                    "Improves write and sequential reads at every measured worker level without a sparse-read regression beyond five percent."
                    if observational_candidate
                    else (
                        "Evidence is incomplete for write, sequential or sparse reads at one or more worker levels; production defaults stay unchanged."
                        if not complete_levels
                        else "Does not provide a uniform net gain across 1/4/16 workers; production defaults stay unchanged."
                    )
                ),
            }
        )
    return candidates


def _write_markdown(report: dict[str, Any], target: Path) -> None:
    lines = [
        "# Pack storage layout concurrency evidence",
        "",
        f"Decision: `{report['decision']}`.",
        "",
        "JMH EVENTS auxiliary counters are summed across active worker threads. This report normalizes them first per measurement iteration and then per worker before comparing layouts.",
        "",
        "The capacity figures are derived from measured per-operation latency and configured worker count. They are observational and never change production settings.",
        "",
        "| Chunk KiB | Inline KiB | Complete 1/4/16 | Candidate | Worst write | Worst sequential | Worst sparse | Worst scaling |",
        "|---:|---:|---|---|---:|---:|---:|---:|",
    ]
    for candidate in report["concurrencyCandidates"]:
        lines.append(
            "| {chunk} | {inline} | {complete} | {candidate} | {write} | {sequential} | {sparse} | {scaling} |".format(
                chunk=candidate["chunkKiB"],
                inline=candidate["inlineKiB"],
                complete="yes" if candidate["completeConcurrencyLevels"] else "no",
                candidate="yes" if candidate["observationalCandidate"] else "no",
                write=_format(candidate["worstWriteImprovementPercent"]),
                sequential=_format(candidate["worstSequentialImprovementPercent"]),
                sparse=_format(candidate["worstSparseImprovementPercent"]),
                scaling=_format(candidate["worstScalingEfficiencyPercent"]),
            )
        )
    lines.extend(
        [
            "",
            "A production layout requires retained full/capacity evidence and matching PostgreSQL/SQL Server net benefit in addition to this concurrency slice.",
        ]
    )
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _format(value: float | None) -> str:
    return "–" if value is None else f"{value:.3f}%"


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-pack-storage-layout-concurrency.py <jmh-result.json> <output-directory>"
        )
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"JMH result must be a JSON array: {source}")
    report = convert(raw)
    output.mkdir(parents=True, exist_ok=True)
    (output / "pack-storage-layout-concurrency-comparison.json").write_text(
        json.dumps(report["comparison"], indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (output / "pack-storage-layout-concurrency-evidence.json").write_text(
        json.dumps(
            {
                key: value
                for key, value in report.items()
                if key != "comparison"
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    _write_markdown(report, output / "pack-storage-layout-concurrency-evidence.md")


if __name__ == "__main__":
    main()
