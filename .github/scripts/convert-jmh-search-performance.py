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
    "pathLiteralSql": (
        "Hibernate Search path query",
        "SQL literal fragment",
    ),
    "pathTermsLucene": (
        "Hibernate Search path query",
        "Lucene analyzed terms",
    ),
}


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    seen: set[str] = set()
    for result in results:
        benchmark = str(result["benchmark"])
        method = benchmark.rsplit(".", 1)[-1]
        if method not in SERIES:
            raise ValueError(f"Unsupported Hibernate Search benchmark method: {method!r}")
        operation, implementation = SERIES[method]
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
        name = f"{operation} — {implementation}"
        if name in seen:
            raise ValueError(f"Duplicate converted Search benchmark series: {name}")
        seen.add(name)

        extra_lines = [
            f"Implementation: {implementation}",
            f"Commits: {params.get('commitCount', 'unknown')}",
            f"Query limit: {params.get('queryLimit', 'unknown')}",
            f"JDK: {result.get('jdkVersion', 'unknown')}",
            f"Mode: {result.get('mode', 'unknown')}",
            f"Forks: {result.get('forks', 'unknown')}",
        ]
        if original_unit != CANONICAL_UNIT:
            extra_lines.append(f"Original metric: {original_score} {original_unit}")

        converted.append(
            {
                "name": name,
                "unit": CANONICAL_UNIT,
                "value": score,
                "range": score_error,
                "extra": "\n".join(extra_lines),
            }
        )

    expected = {
        f"{operation} — {implementation}"
        for operation, implementation in SERIES.values()
    }
    missing = expected - seen
    if missing:
        raise ValueError("Missing Search benchmark series: " + ", ".join(sorted(missing)))
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
