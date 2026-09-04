#!/usr/bin/env python3
"""Convert repository-aging JMH JSON into chart and maintenance-policy evidence."""

from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

CANONICAL_UNIT = "ms/op"
MAINTENANCE_MODES = ("none", "compact-only", "read-optimized")
IMPORTANT_OPERATIONS = (
    "reopenAndLookupOldest",
    "lookupOldestObject",
    "cloneStyleTraversal",
    "incrementalFetchTraversal",
    "revisionWalk",
)
OPERATION_LABELS = {
    "lookupOldestObject": "Oldest-object lookup",
    "lookupNewestObject": "Newest-object lookup",
    "lookupMissingObject": "Missing-object lookup",
    "cloneStyleTraversal": "Clone-style traversal",
    "incrementalFetchTraversal": "Incremental-fetch traversal",
    "readAllRefs": "Read all refs",
    "reopenAndLookupOldest": "Reopen and oldest-object lookup",
    "revisionWalk": "Revision walk",
}


def _metric_number(name: str, value: Any) -> float:
    if isinstance(value, bool):
        raise ValueError(f"Repository-aging metric {name!r} contains a boolean")
    try:
        number = float(value)
    except (TypeError, ValueError) as failure:
        raise ValueError(
            f"Repository-aging metric {name!r} is not numeric: {value!r}"
        ) from failure
    if not math.isfinite(number):
        raise ValueError(
            f"Repository-aging metric {name!r} is not finite: {value!r}"
        )
    return number


def _metric_score(result: dict[str, Any], name: str, default: float = 0.0) -> float:
    """Return one representative per-iteration auxiliary-counter value.

    JMH reports ``AuxCounters.Type.EVENTS`` aggregate ``score`` as the sum of
    the retained measurement iterations. Repository-aging counters describe a
    trial condition or one maintenance action and are repeated unchanged in
    every iteration, so using that aggregate multiplies pack counts, bytes and
    maintenance cost by the number of iterations. Prefer the retained raw
    iteration values, then the secondary p50, while keeping score-only fixtures
    and older evidence readable as a final fallback.
    """

    metrics = result.get("secondaryMetrics", {})
    if not isinstance(metrics, dict):
        raise ValueError("Repository-aging secondaryMetrics must be an object")
    for key, metric in metrics.items():
        if key != name and not key.endswith("." + name):
            continue
        if not isinstance(metric, dict):
            raise ValueError(f"Repository-aging metric {name!r} must be an object")

        raw_data = metric.get("rawData")
        if raw_data is not None:
            if not isinstance(raw_data, list):
                raise ValueError(
                    f"Repository-aging metric {name!r} rawData must be an array"
                )
            values: list[float] = []
            for fork in raw_data:
                if not isinstance(fork, list):
                    raise ValueError(
                        f"Repository-aging metric {name!r} rawData fork must be an array"
                    )
                values.extend(_metric_number(name, value) for value in fork)
            if values:
                return math.fsum(values) / len(values)

        percentiles = metric.get("scorePercentiles")
        if isinstance(percentiles, dict) and "50.0" in percentiles:
            return _metric_number(name, percentiles["50.0"])
        return _metric_number(name, metric.get("score", default))
    return _metric_number(name, default)


def _milliseconds(score: float, unit: str) -> float:
    conversions = {
        "ns/op": 1e-6,
        "us/op": 1e-3,
        "µs/op": 1e-3,
        "ms/op": 1.0,
        "s/op": 1000.0,
    }
    try:
        return score * conversions[unit]
    except KeyError as failure:
        raise ValueError(f"Unsupported repository-aging score unit: {unit!r}") from failure


def _normalized_primary(result: dict[str, Any]) -> tuple[float, float]:
    metric = result["primaryMetric"]
    unit = str(metric["scoreUnit"])
    return (
        _milliseconds(float(metric["score"]), unit),
        _milliseconds(float(metric.get("scoreError", 0.0)), unit),
    )


def _normalized_percentile(result: dict[str, Any], percentile: str) -> float:
    metric = result["primaryMetric"]
    percentiles = metric.get("scorePercentiles")
    if not isinstance(percentiles, dict) or percentile not in percentiles:
        raise ValueError(
            f"Repository-aging result is missing the p{percentile} JMH percentile"
        )
    return _milliseconds(float(percentiles[percentile]), str(metric["scoreUnit"]))


def _row(result: dict[str, Any]) -> dict[str, Any]:
    benchmark = str(result["benchmark"])
    operation = benchmark.rsplit(".", 1)[-1]
    if operation not in OPERATION_LABELS:
        raise ValueError(f"Unsupported repository-aging operation: {operation!r}")
    params = result.get("params", {})
    mode = str(params.get("maintenanceMode", ""))
    if mode not in MAINTENANCE_MODES:
        raise ValueError(f"Unsupported maintenance mode: {mode!r}")
    pushes = int(params["pushes"])
    backend = str(params.get("backend", ""))
    if not backend:
        raise ValueError("Repository-aging result is missing backend")
    cache_state = str(params.get("cacheState", "cold"))
    if cache_state not in {"cold", "warm"}:
        raise ValueError(f"Unsupported cache state: {cache_state!r}")
    score, error = _normalized_primary(result)
    return {
        "operation": operation,
        "operationLabel": OPERATION_LABELS[operation],
        "backend": backend,
        "pushes": pushes,
        "cacheState": cache_state,
        "maintenanceMode": mode,
        "scoreMillis": score,
        "scoreErrorMillis": error,
        "p50Millis": _normalized_percentile(result, "50.0"),
        "p95Millis": _normalized_percentile(result, "95.0"),
        "p99Millis": _normalized_percentile(result, "99.0"),
        "activePacks": int(round(_metric_score(result, "activePacks"))),
        "packPayloadBytes": int(round(_metric_score(result, "packPayloadBytes"))),
        "packIndexBytes": int(round(_metric_score(result, "packIndexBytes"))),
        "smallPacks": int(round(_metric_score(result, "smallPacks"))),
        "smallPackRatioBasisPoints": int(
            round(_metric_score(result, "smallPackRatioBasisPoints"))
        ),
        "storedExtensionBytes": int(round(_metric_score(result, "storedExtensionBytes"))),
        "unreachableLogicalBytes": int(
            round(_metric_score(result, "unreachableLogicalBytes"))
        ),
        "maintenanceElapsedMillis": float(
            _metric_score(result, "maintenanceElapsedMillis")
        ),
        "maintenanceStoredByteDelta": int(
            round(_metric_score(result, "maintenanceStoredByteDelta"))
        ),
        "maintenancePackReduction": int(
            round(_metric_score(result, "maintenancePackReduction"))
        ),
        "jgitDfsMidxExtensionAvailable": bool(
            round(_metric_score(result, "jgitDfsMidxExtensionAvailable"))
        ),
        "jdkVersion": str(result.get("jdkVersion", "unknown")),
        "jmhMode": str(result.get("mode", "unknown")),
    }


def convert(results: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [_row(result) for result in results]
    if not rows:
        raise ValueError("Repository-aging JMH result is empty")

    by_condition: dict[tuple[str, int, str, str], dict[str, dict[str, Any]]] = defaultdict(dict)
    for row in rows:
        key = (row["backend"], row["pushes"], row["cacheState"], row["operation"])
        mode = row["maintenanceMode"]
        if mode in by_condition[key]:
            raise ValueError(f"Duplicate repository-aging result for {key!r} / {mode!r}")
        by_condition[key][mode] = row

    comparisons: list[dict[str, Any]] = []
    policy_evidence: list[dict[str, Any]] = []
    for condition, modes in sorted(by_condition.items()):
        backend, pushes, cache_state, operation = condition
        baseline = modes.get("none")
        if baseline is None:
            raise ValueError(f"Missing no-maintenance baseline for {condition!r}")
        for mode in MAINTENANCE_MODES:
            candidate = modes.get(mode)
            if candidate is None:
                raise ValueError(f"Missing {mode!r} result for {condition!r}")
            saving = baseline["scoreMillis"] - candidate["scoreMillis"]
            break_even = None
            if mode != "none" and saving > 0.0:
                break_even = math.ceil(candidate["maintenanceElapsedMillis"] / saving)
            evidence = {
                **candidate,
                "latencySavingMillis": saving,
                "latencySavingPercent": (
                    0.0
                    if baseline["scoreMillis"] == 0.0
                    else saving * 100.0 / baseline["scoreMillis"]
                ),
                "breakEvenReads": break_even,
                "beneficial": mode != "none"
                and candidate["maintenancePackReduction"] > 0
                and saving > 0.0,
            }
            policy_evidence.append(evidence)
            comparisons.append(
                {
                    "name": (
                        f"{candidate['operationLabel']} — {backend}, {pushes} pushes, "
                        f"{cache_state}, {mode}"
                    ),
                    "unit": CANONICAL_UNIT,
                    "value": candidate["scoreMillis"],
                    "range": candidate["scoreErrorMillis"],
                    "extra": "\n".join(
                        [
                            f"Backend: {backend}",
                            f"Pushes: {pushes}",
                            f"Cache: {cache_state}",
                            f"Maintenance: {mode}",
                            f"p50/p95/p99: {candidate['p50Millis']:.6f} / {candidate['p95Millis']:.6f} / {candidate['p99Millis']:.6f} ms",
                            f"Active packs: {candidate['activePacks']}",
                            f"Small-pack ratio: {candidate['smallPackRatioBasisPoints'] / 100:.2f}%",
                            f"Pack index bytes: {candidate['packIndexBytes']}",
                            f"Stored extension bytes: {candidate['storedExtensionBytes']}",
                            f"Maintenance cost: {candidate['maintenanceElapsedMillis']:.3f} ms",
                            (
                                "Break-even reads: not reached"
                                if break_even is None
                                else f"Break-even reads: {break_even}"
                            ),
                        ]
                    ),
                }
            )

    recommendations = _recommendations(policy_evidence)
    return {
        "schemaVersion": 1,
        "comparison": sorted(comparisons, key=lambda item: item["name"]),
        "policyEvidence": policy_evidence,
        "recommendations": recommendations,
        "midx": {
            "dfsPackExtensionAvailable": any(
                row["jgitDfsMidxExtensionAvailable"] for row in rows
            ),
            "decision": (
                "evaluate-midx"
                if any(row["jgitDfsMidxExtensionAvailable"] for row in rows)
                else "unsupported-by-selected-jgit-dfs"
            ),
        },
    }


def _recommendations(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, int, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row["operation"] in IMPORTANT_OPERATIONS and row["maintenanceMode"] != "none":
            grouped[(row["backend"], row["pushes"], row["cacheState"])].append(row)

    recommendations: list[dict[str, Any]] = []
    for (backend, pushes, cache_state), candidates in sorted(grouped.items()):
        beneficial = [row for row in candidates if row["beneficial"]]
        best = min(
            beneficial,
            key=lambda row: (
                row["breakEvenReads"] if row["breakEvenReads"] is not None else math.inf,
                -row["latencySavingMillis"],
                row["maintenanceMode"],
            ),
            default=None,
        )
        recommendations.append(
            {
                "backend": backend,
                "pushes": pushes,
                "cacheState": cache_state,
                "status": "candidate" if best is not None else "do-not-maintain",
                "maintenanceMode": None if best is None else best["maintenanceMode"],
                "operation": None if best is None else best["operation"],
                "breakEvenReads": None if best is None else best["breakEvenReads"],
                "reason": (
                    "No measured important operation repaid maintenance while reducing packs."
                    if best is None
                    else "Lowest measured break-even among important operations with pack reduction."
                ),
            }
        )
    return recommendations


def _write_markdown(report: dict[str, Any], target: Path) -> None:
    lines = [
        "# Repository-aging maintenance evidence",
        "",
        "This report is generated from the retained JMH JSON. It is evidence, not an automatic trigger.",
        "",
        "| Backend | Pushes | Cache | Recommendation | Mode | Operation | Break-even reads |",
        "|---|---:|---|---|---|---|---:|",
    ]
    for item in report["recommendations"]:
        lines.append(
            "| {backend} | {pushes} | {cacheState} | {status} | {mode} | {operation} | {break_even} |".format(
                backend=item["backend"],
                pushes=item["pushes"],
                cacheState=item["cacheState"],
                status=item["status"],
                mode=item["maintenanceMode"] or "–",
                operation=item["operation"] or "–",
                break_even=item["breakEvenReads"] if item["breakEvenReads"] is not None else "–",
            )
        )
    lines.extend(
        [
            "",
            "## MIDX",
            "",
            f"Decision: `{report['midx']['decision']}`.",
            "",
            "A production default must not be changed from this report alone. Compare multiple retained runs and require stable breakpoints across databases and cache states.",
        ]
    )
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-repository-aging.py <jmh-result.json> <output-directory>"
        )
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"JMH result must be a JSON array: {source}")
    report = convert(raw)
    output.mkdir(parents=True, exist_ok=True)
    (output / "repository-aging-comparison.json").write_text(
        json.dumps(report["comparison"], indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (output / "repository-aging-policy-evidence.json").write_text(
        json.dumps(
            {
                "schemaVersion": report["schemaVersion"],
                "policyEvidence": report["policyEvidence"],
                "recommendations": report["recommendations"],
                "midx": report["midx"],
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    _write_markdown(report, output / "repository-aging-policy-evidence.md")


if __name__ == "__main__":
    main()
