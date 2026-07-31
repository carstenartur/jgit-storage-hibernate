#!/usr/bin/env python3
"""Convert one or more JMH JSON files into one consistent smaller-is-better format."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Iterable

from benchmark_units import CANONICAL_UNIT, normalize_measurement

BACKEND_LABELS = {
    "filesystem": "JGit + filesystem",
    "hsqldb": "JGit + HSQLDB (in-memory)",
    "postgresql": "JGit + PostgreSQL",
    "postgresql-hikari": "JGit + PostgreSQL + HikariCP",
}

BATCHING_MODE_LABELS = {
    "disabled": "JGit + PostgreSQL (JDBC batching off)",
    "enabled": "JGit + PostgreSQL (JDBC batching on)",
    "enabled-rewrite": "JGit + PostgreSQL (JDBC batching + rewrite)",
}


def _series_label(result: dict[str, Any]) -> str:
    params = result.get("params", {})
    backend = params.get("backend")
    if backend is not None:
        if backend not in BACKEND_LABELS:
            raise ValueError(f"Unsupported JMH backend parameter: {backend!r}")
        return BACKEND_LABELS[backend]

    batching_mode = params.get("batchingMode")
    if batching_mode is not None:
        if batching_mode not in BATCHING_MODE_LABELS:
            raise ValueError(f"Unsupported JMH batching mode: {batching_mode!r}")
        return BATCHING_MODE_LABELS[batching_mode]

    raise ValueError("JMH result has neither a backend nor a batchingMode parameter")


def load_results(sources: Iterable[Path]) -> list[dict[str, Any]]:
    combined: list[dict[str, Any]] = []
    for source in sources:
        raw = json.loads(source.read_text(encoding="utf-8"))
        if not isinstance(raw, list):
            raise ValueError(f"JMH result must be a JSON array: {source}")
        combined.extend(raw)
    return combined


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    for result in results:
        series_label = _series_label(result)
        benchmark = str(result["benchmark"])
        operation = benchmark.rsplit(".", 1)[-1]
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
            f"Backend: {series_label}",
            f"JDK: {result.get('jdkVersion', 'unknown')}",
            f"Mode: {result.get('mode', 'unknown')}",
            f"Forks: {result.get('forks', 'unknown')}",
        ]
        if original_unit != CANONICAL_UNIT:
            extra_lines.append(f"Original metric: {original_score} {original_unit}")

        converted.append(
            {
                "name": f"{operation} — {series_label}",
                "unit": CANONICAL_UNIT,
                "value": score,
                "range": score_error,
                "extra": "\n".join(extra_lines),
            }
        )

    return sorted(converted, key=lambda item: item["name"])


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit(
            "usage: convert-jmh-backend-comparison.py "
            "<jmh-result.json> [<additional-result.json> ...] <comparison.json>"
        )

    sources = [Path(argument) for argument in sys.argv[1:-1]]
    target = Path(sys.argv[-1])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(convert(load_results(sources)), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
