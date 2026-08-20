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


def _secondary(result: dict[str, Any], name: str) -> float:
    for key, metric in result.get("secondaryMetrics", {}).items():
        if key == name or key.endswith("." + name):
            return float(metric.get("score", 0.0))
    raise ValueError(f"Concurrency result is missing secondary metric {name!r}")


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
    chunk_kib = int(params["chunkKiB"])
    inline_kib = int(params["inlineKiB"])
    payload_kib = int(params["payloadKiB"])
    retained_mib = int(params["retainedMiB"])
    read_ahead_kib = int(params["readAheadKiB"])

    metric = result["primaryMetric"]
    score = _milliseconds(float(metric["score"]), str(metric["scoreUnit"]))
    if not math.isfinite(score) or score <= 0.0:
        raise ValueError("Concurrency latency must be finite and positive")
    configured_concurrency = int(round(_secondary(result, "configuredConcurrency")))
    configured_chunk_bytes = int(round(_secondary(result, "configuredChunkBytes")))
    configured_budget = int(round(_secondary(result, "configuredRetainedBudgetBytes")))
    actual_retained = int(round(_secondary(result, "actualRetainedChunkBytes")))
    if configured_concurrency != concurrency:
        raise ValueError("JMH concurrency parameter and structural counter disagree")
    if configured_chunk_bytes != chunk_kib * 1024:
        raise ValueError("JMH chunk parameter and structural counter disagree")
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
        "readAheadChunks": int(round(_secondary(result, "readAheadChunks"))),
        "chunksPerBatch": int(round(_secondary(result, "chunksPerBatch"))),
        "logicalPayloadBytes": int(round(_secondary(result, "logicalPayloadBytes"))),
        "chunkRows": int(round(_secondary(result, "chunkRows"))),
        "jdbcBatchExecutions": float(_secondary(result, "jdbcBatchExecutions")),
        "jdbcStatementExecutions": float(_secondary(result, "jdbcStatementExecutions")),
        "databasePayloadBytes": float(_secondary(result, "databasePayloadBytes")),
        "logicalBytesConsumed": float(_secondary(result, "logicalBytesConsumed")),
        "overfetchBytes": float(_secondary(result, "overfetchBytes")),
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
                    f"Chunk rows: {row['chunkRows']}",
                    f"Retained chunk bytes per writer: {row['actualRetainedChunkBytes']}",
                    f"Fetched/consumed/overfetch: {row['databasePayloadBytes']:.0f} / {row['logicalBytesConsumed']:.0f} / {row['overfetchBytes']:.0f}",
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
        writes = [
            row["relativeToCurrentPercent"]
            for row in values
            if row["operation"] == "write"
        ]
        sequential = [
            row["relativeToCurrentPercent"]
            for row in values
            if row["operation"] == "sequential-read"
        ]
        sparse = [
            row["relativeToCurrentPercent"]
            for row in values
            if row["operation"] in SPARSE_OPERATIONS
        ]
        scaling = [row["scalingEfficiencyPercent"] for row in values]
        complete_levels = {
            row["concurrency"] for row in values
        } == CONCURRENCY_LEVELS
        observational_candidate = (
            (chunk_kib != CURRENT_CHUNK_KIB or inline_kib != CURRENT_INLINE_KIB)
            and complete_levels
            and writes
            and sequential
            and min(writes) > 0.0
            and min(sequential) > 0.0
            and (not sparse or min(sparse) >= -5.0)
        )
        candidates.append(
            {
                "chunkKiB": chunk_kib,
                "inlineKiB": inline_kib,
                "completeConcurrencyLevels": complete_levels,
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
                    else "Does not provide a uniform net gain across 1/4/16 workers; production defaults stay unchanged."
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
