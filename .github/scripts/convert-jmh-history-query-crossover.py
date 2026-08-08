#!/usr/bin/env python3
"""Convert JGit/FileRepository versus indexed-history JMH results into dashboard series."""

from __future__ import annotations

import json
import math
import statistics
import sys
from pathlib import Path
from typing import Any

from benchmark_units import CANONICAL_UNIT, normalize_measurement

ENGINES = {
    "filesystem-jgit": "FileRepository / JGit on demand",
    "hibernate-jgit": "HibernateRepository / JGit on demand",
    "indexed-projection": "Indexed history projection",
}

QUERY_TITLES = {
    "author-time": "Git history author + time query",
    "path-time": "Git history path + time query",
    "message-text": "Git history commit-message query",
    "path-content": "Git history path + changed-content query",
    "compound": "Git history compound audit query",
}

INDEXED_IMPLEMENTATION = {
    "author-time": "PostgreSQL compact projection",
    "path-time": "Hibernate Search exact-path projection",
    "message-text": "Hibernate Search full-text projection",
    "path-content": "Hibernate Search path + content projection",
    "compound": "Hibernate Search compound projection",
}

REQUIRED_ENGINES = frozenset(ENGINES)
REQUIRED_QUERIES = frozenset(QUERY_TITLES)


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


def _counter_mean(result: dict[str, Any], field: str) -> float:
    metric = _secondary_metric(result, field)
    raw = metric.get("rawData")
    if not isinstance(raw, list) or not raw:
        raise ValueError(f"JMH secondary metric {field!r} is missing rawData")
    values: list[float] = []
    for fork in raw:
        if not isinstance(fork, list):
            raise ValueError(f"JMH secondary metric {field!r} rawData must contain fork arrays")
        for value in fork:
            number = float(value)
            if not math.isfinite(number):
                raise ValueError(f"JMH secondary metric {field!r} contains {value!r}")
            values.append(number)
    if not values:
        raise ValueError(f"JMH secondary metric {field!r} rawData contains no values")
    return statistics.fmean(values)


def _positive_int(params: dict[str, Any], name: str) -> int:
    try:
        value = int(params[name])
    except (KeyError, TypeError, ValueError) as exception:
        raise ValueError(f"Missing or invalid positive JMH parameter {name!r}") from exception
    if value <= 0:
        raise ValueError(f"JMH parameter {name!r} must be positive, got {value}")
    return value


def _timing(result: dict[str, Any]) -> tuple[float, float, str]:
    metric = result["primaryMetric"]
    unit = str(metric["scoreUnit"])
    score, error = normalize_measurement(
        metric["score"], metric.get("scoreError", 0.0), unit
    )
    return score, error, unit


def _operation(result: dict[str, Any]) -> str:
    return str(result["benchmark"]).rsplit(".", 1)[-1]


def _work_lines(result: dict[str, Any]) -> list[str]:
    return [
        f"Results: {_counter_mean(result, 'resultCount'):g}",
        f"Commits visited on demand: {_counter_mean(result, 'commitsVisited'):g}",
        f"Exact-path tree inspections: {_counter_mean(result, 'treeInspections'):g}",
        f"Changed blobs read: {_counter_mean(result, 'blobsRead'):g}",
        f"Changed blob bytes read: {_counter_mean(result, 'blobBytes'):g}",
        f"Hibernate prepared statements: {_counter_mean(result, 'preparedStatements'):g}",
        f"Hibernate transactions: {_counter_mean(result, 'transactions'):g}",
    ]


def _break_even(build_ms: float, baseline_ms: float, indexed_ms: float) -> str:
    saving = baseline_ms - indexed_ms
    if saving <= 0.0:
        return "not reached (indexed query was not faster in this run)"
    return f"{build_ms / saving:.2f} queries"


def _query_entry(
    result: dict[str, Any], build_ms: float, peers: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    params = result.get("params", {})
    count = _positive_int(params, "commitCount")
    query_kind = str(params.get("queryKind", ""))
    engine = str(params.get("engine", ""))
    if query_kind not in QUERY_TITLES:
        raise ValueError(f"Unsupported history query kind: {query_kind!r}")
    if engine not in ENGINES:
        raise ValueError(f"Unsupported history query engine: {engine!r}")

    score, error, original_unit = _timing(result)
    implementation = (
        INDEXED_IMPLEMENTATION[query_kind]
        if engine == "indexed-projection"
        else ENGINES[engine]
    )
    extra = [
        f"Query: {query_kind}",
        f"Implementation: {implementation}",
        f"Commits in authoritative history: {count}",
        f"Query limit: {params.get('queryLimit', 'unknown')}",
        "Host/database caches are warm; every on-demand query creates a fresh ObjectReader/RevWalk.",
        *_work_lines(result),
    ]

    if engine == "indexed-projection":
        filesystem_ms, _, _ = _timing(peers["filesystem-jgit"])
        hibernate_ms, _, _ = _timing(peers["hibernate-jgit"])
        extra.extend(
            [
                f"Measured content-v1 projection build: {build_ms:.3f} ms",
                f"Speedup vs FileRepository/JGit: {filesystem_ms / score:.2f}x",
                f"Speedup vs HibernateRepository/JGit: {hibernate_ms / score:.2f}x",
                "Break-even vs FileRepository/JGit including one projection build: "
                + _break_even(build_ms, filesystem_ms, score),
                "Break-even vs HibernateRepository/JGit including one projection build: "
                + _break_even(build_ms, hibernate_ms, score),
                "Projection build timing starts from an existing authoritative Git history; "
                "repository creation is excluded for every implementation.",
            ]
        )

    if original_unit != CANONICAL_UNIT:
        extra.append(f"Original timing unit: {original_unit}")

    return {
        "name": f"{QUERY_TITLES[query_kind]} — {count:,} commits / {implementation}",
        "unit": CANONICAL_UNIT,
        "value": score,
        "range": error,
        "extra": "\n".join(extra),
    }


def _build_entry(result: dict[str, Any]) -> dict[str, Any]:
    params = result.get("params", {})
    count = _positive_int(params, "commitCount")
    score, error, original_unit = _timing(result)
    extra = [
        "Projection: content-v1",
        f"Commits in authoritative history: {count}",
        f"Indexed commits: {_counter_mean(result, 'resultCount'):g}",
        f"Hibernate prepared statements: {_counter_mean(result, 'preparedStatements'):g}",
        f"Hibernate transactions: {_counter_mean(result, 'transactions'):g}",
        "Measured operation is purge/rebuild from an already-created authoritative Git history.",
    ]
    if original_unit != CANONICAL_UNIT:
        extra.append(f"Original timing unit: {original_unit}")
    return {
        "name": f"Git history indexed projection build — {count:,} commits / content-v1",
        "unit": CANONICAL_UNIT,
        "value": score,
        "range": error,
        "extra": "\n".join(extra),
    }


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    builds: dict[int, dict[str, Any]] = {}
    queries: dict[tuple[int, str], dict[str, dict[str, Any]]] = {}

    for result in results:
        operation = _operation(result)
        params = result.get("params", {})
        count = _positive_int(params, "commitCount")
        if operation == "projectionBuild":
            if count in builds:
                raise ValueError(f"Duplicate projection-build result for {count} commits")
            builds[count] = result
        elif operation == "query":
            query_kind = str(params.get("queryKind", ""))
            engine = str(params.get("engine", ""))
            if query_kind not in REQUIRED_QUERIES:
                raise ValueError(f"Unsupported history query kind: {query_kind!r}")
            if engine not in REQUIRED_ENGINES:
                raise ValueError(f"Unsupported history query engine: {engine!r}")
            peers = queries.setdefault((count, query_kind), {})
            if engine in peers:
                raise ValueError(
                    f"Duplicate history-query result for {count}/{query_kind}/{engine}"
                )
            peers[engine] = result
        else:
            raise ValueError(f"Unsupported history-query benchmark operation: {operation!r}")

    if not builds or not queries:
        raise ValueError("History-query benchmark must contain build and query results")

    commit_counts = {count for count, _ in queries}
    if set(builds) != commit_counts:
        raise ValueError(
            "Projection-build commit counts do not match query commit counts: "
            f"builds={sorted(builds)}, queries={sorted(commit_counts)}"
        )

    for count in commit_counts:
        kinds = {kind for query_count, kind in queries if query_count == count}
        missing_queries = REQUIRED_QUERIES - kinds
        if missing_queries:
            raise ValueError(
                f"Missing query kinds for {count} commits: {', '.join(sorted(missing_queries))}"
            )
        for query_kind in REQUIRED_QUERIES:
            engines = set(queries[(count, query_kind)])
            missing_engines = REQUIRED_ENGINES - engines
            if missing_engines:
                raise ValueError(
                    f"Missing engines for {count}/{query_kind}: "
                    + ", ".join(sorted(missing_engines))
                )

    converted: list[dict[str, Any]] = []
    for count in sorted(builds):
        build_result = builds[count]
        build_ms, _, _ = _timing(build_result)
        converted.append(_build_entry(build_result))
        for query_kind in QUERY_TITLES:
            peers = queries[(count, query_kind)]
            for engine in ENGINES:
                converted.append(_query_entry(peers[engine], build_ms, peers))

    names = [entry["name"] for entry in converted]
    if len(names) != len(set(names)):
        raise ValueError("History-query conversion produced duplicate dashboard series names")
    return sorted(converted, key=lambda entry: entry["name"])


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-history-query-crossover.py <jmh-result.json> <comparison.json>"
        )
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
