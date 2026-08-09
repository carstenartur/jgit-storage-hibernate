#!/usr/bin/env python3
"""Convert Hibernate Search runtime tuning JMH JSON into stable dashboard series."""

from __future__ import annotations

import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any

from benchmark_units import normalize_measurement

OPERATIONS = {
    "incrementalBurstSubmission": "Hibernate Search runtime burst submission",
    "incrementalBurstReady": "Hibernate Search runtime burst ready",
    "steadyQueriesDuringBurst": "Hibernate Search runtime concurrent burst window",
    "projectionRebuildReady": "Hibernate Search runtime rebuild ready",
}

PERCENTILES = {"p50": 0.50, "p95": 0.95, "p99": 0.99}


def _flatten_raw(metric: dict[str, Any], name: str) -> list[float]:
    raw = metric.get("rawData")
    if not isinstance(raw, list) or not raw:
        raise ValueError(f"JMH metric {name!r} is missing rawData")
    values: list[float] = []
    for fork in raw:
        if not isinstance(fork, list):
            raise ValueError(f"JMH metric {name!r} rawData must contain fork arrays")
        for value in fork:
            number = float(value)
            if not math.isfinite(number):
                raise ValueError(f"JMH metric {name!r} contains non-finite value {value!r}")
            values.append(number)
    if not values:
        raise ValueError(f"JMH metric {name!r} rawData contains no measurements")
    return values


def _secondary_metric(result: dict[str, Any], field: str) -> dict[str, Any]:
    metrics = result.get("secondaryMetrics", {})
    if not isinstance(metrics, dict):
        raise ValueError("JMH secondaryMetrics must be an object")
    direct = metrics.get(field)
    if isinstance(direct, dict):
        return direct
    matches = [
        metric
        for name, metric in metrics.items()
        if str(name).rsplit(".", 1)[-1] == field and isinstance(metric, dict)
    ]
    if len(matches) != 1:
        raise ValueError(
            f"Expected exactly one JMH secondary metric for {field!r}, found {len(matches)}"
        )
    return matches[0]


def _percentile(values: list[float], fraction: float) -> float:
    if not values:
        raise ValueError("Cannot compute percentile of empty values")
    ordered = sorted(values)
    rank = math.ceil(fraction * len(ordered)) - 1
    return ordered[max(0, min(rank, len(ordered) - 1))]


def _mean_and_stdev(values: list[float]) -> tuple[float, float]:
    return statistics.fmean(values), statistics.stdev(values) if len(values) > 1 else 0.0


def _scenario_details(scenario: str) -> str:
    if scenario == "reference":
        return "sync=write-sync; refresh=0ms; writerRAM=Lucene-default; backendThreads=CPU-default; batch=50"
    if scenario.startswith("sync-"):
        separator = scenario.rfind("-r")
        if separator <= len("sync-"):
            raise ValueError(f"Invalid synchronization scenario {scenario!r}")
        strategy = scenario[len("sync-") : separator]
        refresh = scenario[separator + 2 :]
        return (
            f"sync={strategy}; refresh={refresh}ms; writerRAM=Lucene-default; "
            "backendThreads=CPU-default; batch=50"
        )
    if scenario.startswith("writer-ram"):
        parts = scenario.split("-")
        if len(parts) != 3 or not parts[1].startswith("ram") or not parts[2].startswith("t"):
            raise ValueError(f"Invalid writer scenario {scenario!r}")
        return (
            f"sync=write-sync; refresh=0ms; writerRAM={parts[1][3:]}MiB; "
            f"backendThreads={parts[2][1:]}; batch=50"
        )
    if scenario.startswith("batch-"):
        return (
            "sync=write-sync; refresh=0ms; writerRAM=Lucene-default; "
            f"backendThreads=CPU-default; batch={scenario[len('batch-'):]}"
        )
    raise ValueError(f"Unknown runtime scenario {scenario!r}")


def _extra(result: dict[str, Any], scenario: str) -> str:
    params = result.get("params", {})
    return "\n".join(
        [
            f"Scenario: {scenario}",
            _scenario_details(scenario),
            f"Commits: {params.get('commitCount', 'unknown')}",
            f"Burst commits: {params.get('burstCount', 'unknown')}",
            f"JDK: {result.get('jdkVersion', 'unknown')}",
            f"Mode: {result.get('mode', 'unknown')}",
            f"Forks: {result.get('forks', 'unknown')}",
            "Chart direction: lower is better; higher is slower or more expensive.",
        ]
    )


def _primary_samples_ms(result: dict[str, Any]) -> list[float]:
    metric = result.get("primaryMetric")
    if not isinstance(metric, dict):
        raise ValueError("JMH primaryMetric must be an object")
    unit = str(metric.get("scoreUnit", ""))
    return [normalize_measurement(value, 0.0, unit)[0] for value in _flatten_raw(metric, "primary")]


def _counter_samples(result: dict[str, Any], field: str) -> list[float]:
    return _flatten_raw(_secondary_metric(result, field), field)


def _timing_entries(result: dict[str, Any], operation: str, scenario: str) -> list[dict[str, Any]]:
    samples = _primary_samples_ms(result)
    extra = _extra(result, scenario)
    return [
        {
            "name": f"{operation} {label} — {scenario}",
            "unit": "ms/op",
            "value": _percentile(samples, fraction),
            "range": 0.0,
            "extra": extra,
        }
        for label, fraction in PERCENTILES.items()
    ]


def _mean_counter_entry(
    result: dict[str, Any], field: str, operation: str, scenario: str, unit: str, factor: float = 1.0
) -> dict[str, Any]:
    mean, spread = _mean_and_stdev(_counter_samples(result, field))
    return {
        "name": f"{operation} — {scenario}",
        "unit": unit,
        "value": mean * factor,
        "range": spread * factor,
        "extra": _extra(result, scenario) + f"\nAuxCounter: {field}; per-invocation mean from rawData",
    }


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    methods_by_scenario: dict[str, set[str]] = {}
    by_scenario_method: dict[tuple[str, str], dict[str, Any]] = {}

    for result in results:
        method = str(result.get("benchmark", "")).rsplit(".", 1)[-1]
        operation = OPERATIONS.get(method)
        if operation is None:
            raise ValueError(f"Unsupported Search runtime benchmark method: {method!r}")
        scenario = str(result.get("params", {}).get("runtimeScenario", ""))
        _scenario_details(scenario)
        key = (scenario, method)
        if key in by_scenario_method:
            raise ValueError(f"Duplicate Search runtime result for {key!r}")
        by_scenario_method[key] = result
        methods_by_scenario.setdefault(scenario, set()).add(method)
        for entry in _timing_entries(result, operation, scenario):
            if entry["name"] in seen_names:
                raise ValueError(f"Duplicate converted runtime series: {entry['name']}")
            seen_names.add(entry["name"])
            converted.append(entry)

    expected_methods = set(OPERATIONS)
    if not methods_by_scenario:
        raise ValueError("No Hibernate Search runtime benchmark results were provided")

    for scenario, methods in methods_by_scenario.items():
        missing = expected_methods - methods
        if missing:
            raise ValueError(
                f"Missing Search runtime series for scenario {scenario}: "
                + ", ".join(sorted(missing))
            )

        ready = by_scenario_method[(scenario, "incrementalBurstReady")]
        rebuild = by_scenario_method[(scenario, "projectionRebuildReady")]
        concurrent = by_scenario_method[(scenario, "steadyQueriesDuringBurst")]

        extra_entries = [
            _mean_counter_entry(
                ready,
                "visibilityWaitMicros",
                "Hibernate Search runtime burst visibility observation wait",
                scenario,
                "ms/op",
                0.001,
            ),
            _mean_counter_entry(
                ready,
                "visibilityPolls",
                "Hibernate Search runtime burst visibility polls",
                scenario,
                "count/op",
            ),
            _mean_counter_entry(
                rebuild,
                "visibilityWaitMicros",
                "Hibernate Search runtime rebuild visibility observation wait",
                scenario,
                "ms/op",
                0.001,
            ),
            _mean_counter_entry(
                rebuild,
                "visibilityPolls",
                "Hibernate Search runtime rebuild visibility polls",
                scenario,
                "count/op",
            ),
            _mean_counter_entry(
                ready,
                "preparedStatements",
                "Hibernate Search runtime burst prepared statements",
                scenario,
                "count/op",
            ),
            _mean_counter_entry(
                ready,
                "transactions",
                "Hibernate Search runtime burst transactions",
                scenario,
                "count/op",
            ),
        ]
        for field, label in (
            ("queryP50Micros", "p50"),
            ("queryP95Micros", "p95"),
            ("queryP99Micros", "p99"),
        ):
            extra_entries.append(
                _mean_counter_entry(
                    concurrent,
                    field,
                    f"Hibernate Search concurrent query {label}",
                    scenario,
                    "ms/op",
                    0.001,
                )
            )

        for entry in extra_entries:
            if entry["name"] in seen_names:
                raise ValueError(f"Duplicate converted runtime series: {entry['name']}")
            seen_names.add(entry["name"])
            converted.append(entry)

    return sorted(converted, key=lambda item: item["name"])


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: convert-jmh-search-runtime.py <jmh-result.json> <comparison.json>")
    source = Path(sys.argv[1])
    target = Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"JMH result must be a JSON array: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(convert(raw), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
