#!/usr/bin/env python3
"""Convert Hibernate Search JMH JSON into stable grouped dashboard series."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from benchmark_units import CANONICAL_UNIT, normalize_measurement

SERIES = {
    "incrementalIndexing": (
        "Hibernate Search indexing",
        "Batched incremental indexing",
    ),
    "projectionRebuild": (
        "Hibernate Search rebuild",
        "Bounded purge + batched rebuild",
    ),
    "fullTextEntityHits": (
        "Hibernate Search full-text query",
        "Entity hydration",
    ),
    "fullTextSummaryHits": (
        "Hibernate Search full-text query",
        "Lucene projection",
    ),
    "contentOnlySummaryHits": (
        "Hibernate Search content query",
        "Lucene projection",
    ),
    "pathLiteralSql": (
        "Hibernate Search path query",
        "SQL literal fragment",
    ),
    "pathTermsLucene": (
        "Hibernate Search path query",
        "Lucene analyzed terms",
    ),
}

DEFAULT_PROFILE = "content-v1"
FOOTPRINT_SOURCE = "fullTextSummaryHits"


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


def _metric_value(result: dict[str, Any], field: str) -> tuple[float, float]:
    metric = _secondary_metric(result, field)
    return float(metric["score"]), float(metric.get("scoreError", 0.0))


def _positive_int(params: dict[str, Any], name: str) -> int:
    try:
        value = int(params[name])
    except (KeyError, TypeError, ValueError) as exception:
        raise ValueError(f"Missing or invalid positive JMH parameter {name!r}") from exception
    if value <= 0:
        raise ValueError(f"JMH parameter {name!r} must be positive, got {value}")
    return value


def _miss_rate(actual: float, expected: int) -> float:
    if expected <= 0:
        raise ValueError(f"Expected relevant result count must be positive, got {expected}")
    bounded_actual = min(max(actual, 0.0), float(expected))
    return 100.0 * (float(expected) - bounded_actual) / float(expected)


def _timing_entry(
    result: dict[str, Any],
    operation: str,
    implementation: str,
    profile: str,
) -> dict[str, Any]:
    params = result.get("params", {})
    metric = result["primaryMetric"]
    original_score = metric["score"]
    original_error = metric.get("scoreError", 0.0)
    original_unit = str(metric["scoreUnit"])
    score, score_error = normalize_measurement(
        original_score,
        original_error,
        original_unit,
    )
    extra_lines = [
        f"Profile: {profile}",
        f"Implementation: {implementation}",
        f"Commits: {params.get('commitCount', 'unknown')}",
        f"Query limit: {params.get('queryLimit', 'unknown')}",
        f"JDK: {result.get('jdkVersion', 'unknown')}",
        f"Mode: {result.get('mode', 'unknown')}",
        f"Forks: {result.get('forks', 'unknown')}",
    ]
    if original_unit != CANONICAL_UNIT:
        extra_lines.append(f"Original metric: {original_score} {original_unit}")
    return {
        "name": f"{operation} — {profile} — {implementation}",
        "unit": CANONICAL_UNIT,
        "value": score,
        "range": score_error,
        "extra": "\n".join(extra_lines),
    }


def _footprint_entries(result: dict[str, Any], profile: str) -> list[dict[str, Any]]:
    params = result.get("params", {})
    common_extra = (
        f"Profile: {profile}\n"
        f"Commits: {params.get('commitCount', 'unknown')}\n"
        "Measured after a complete PostgreSQL + local-filesystem Lucene projection rebuild"
    )
    lucene_bytes, lucene_error = _metric_value(result, "luceneBytes")
    sql_bytes, sql_error = _metric_value(result, "sqlProjectionBytes")
    segments, segments_error = _metric_value(result, "segmentCount")
    return [
        {
            "name": f"Hibernate Search index footprint — {profile} — Lucene",
            "unit": "bytes",
            "value": lucene_bytes,
            "range": lucene_error,
            "extra": common_extra,
        },
        {
            "name": f"Hibernate Search SQL projection footprint — {profile} — PostgreSQL",
            "unit": "bytes",
            "value": sql_bytes,
            "range": sql_error,
            "extra": common_extra,
        },
        {
            "name": f"Hibernate Search segment count — {profile} — Lucene",
            "unit": "segments",
            "value": segments,
            "range": segments_error,
            "extra": common_extra,
        },
    ]


def _quality_entry(
    result: dict[str, Any], profile: str, quality_name: str, expected: int
) -> dict[str, Any]:
    actual, _ = _metric_value(result, "resultCount")
    miss_rate = _miss_rate(actual, expected)
    return {
        "name": f"{quality_name} — {profile} — miss rate",
        "unit": "miss %",
        "value": miss_rate,
        "range": 0.0,
        "extra": (
            f"Profile: {profile}\n"
            f"Expected relevant hits within query limit: {expected}\n"
            f"Observed relevant hits: {actual:g}\n"
            "0% miss is best; reduced-content profiles may intentionally trade recall for cost"
        ),
    }


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    seen: set[str] = set()
    methods_by_profile: dict[str, set[str]] = {}
    results_by_profile_method: dict[tuple[str, str], dict[str, Any]] = {}

    for result in results:
        benchmark = str(result["benchmark"])
        method = benchmark.rsplit(".", 1)[-1]
        if method not in SERIES:
            raise ValueError(f"Unsupported Hibernate Search benchmark method: {method!r}")
        params = result.get("params", {})
        profile = str(params.get("indexProfile", DEFAULT_PROFILE))
        operation, implementation = SERIES[method]
        entry = _timing_entry(result, operation, implementation, profile)
        if entry["name"] in seen:
            raise ValueError(f"Duplicate converted Search benchmark series: {entry['name']}")
        seen.add(entry["name"])
        converted.append(entry)
        methods_by_profile.setdefault(profile, set()).add(method)
        key = (profile, method)
        if key in results_by_profile_method:
            raise ValueError(f"Duplicate JMH Search result for profile/method {key!r}")
        results_by_profile_method[key] = result

    expected_methods = set(SERIES)
    for profile, methods in methods_by_profile.items():
        missing = expected_methods - methods
        if missing:
            raise ValueError(
                f"Missing Search benchmark series for profile {profile}: "
                + ", ".join(sorted(missing))
            )

        footprint_source = results_by_profile_method[(profile, FOOTPRINT_SOURCE)]
        for entry in _footprint_entries(footprint_source, profile):
            if entry["name"] in seen:
                raise ValueError(f"Duplicate converted Search benchmark series: {entry['name']}")
            seen.add(entry["name"])
            converted.append(entry)

        content_result = results_by_profile_method[(profile, "contentOnlySummaryHits")]
        content_params = content_result.get("params", {})
        commit_count = _positive_int(content_params, "commitCount")
        query_limit = _positive_int(content_params, "queryLimit")
        expected_content_hits = min((commit_count + 4) // 5, query_limit)
        content_quality = _quality_entry(
            content_result,
            profile,
            "Hibernate Search content quality",
            expected_content_hits,
        )
        if content_quality["name"] in seen:
            raise ValueError(
                f"Duplicate converted Search benchmark series: {content_quality['name']}"
            )
        seen.add(content_quality["name"])
        converted.append(content_quality)

        path_result = results_by_profile_method[(profile, "pathTermsLucene")]
        path_params = path_result.get("params", {})
        expected_path_hits = min(
            _positive_int(path_params, "commitCount"),
            _positive_int(path_params, "queryLimit"),
        )
        path_quality = _quality_entry(
            path_result,
            profile,
            "Hibernate Search path quality",
            expected_path_hits,
        )
        if path_quality["name"] in seen:
            raise ValueError(
                f"Duplicate converted Search benchmark series: {path_quality['name']}"
            )
        seen.add(path_quality["name"])
        converted.append(path_quality)

    if not methods_by_profile:
        raise ValueError("No Hibernate Search benchmark results were provided")
    return sorted(converted, key=lambda item: item["name"])


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-search-performance.py <jmh-result.json> <comparison.json>"
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
