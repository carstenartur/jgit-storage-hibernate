#!/usr/bin/env python3
"""Convert JMH JSON into the dashboard's custom smaller-is-better format."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any

BACKEND_LABELS = {
    "filesystem": "JGit + filesystem",
    "hsqldb": "JGit + HSQLDB (in-memory)",
    "postgresql": "JGit + PostgreSQL",
    "postgresql-hikari": "JGit + PostgreSQL + HikariCP",
}


def _finite_number(value: Any, field: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{field} must be finite, got {value!r}")
    return number


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    for result in results:
        backend = result.get("params", {}).get("backend")
        if backend not in BACKEND_LABELS:
            raise ValueError(f"Missing or unsupported JMH backend parameter: {backend!r}")

        benchmark = str(result["benchmark"])
        operation = benchmark.rsplit(".", 1)[-1]
        metric = result["primaryMetric"]
        score = _finite_number(metric["score"], "score")
        score_error = _finite_number(metric.get("scoreError", 0.0), "scoreError")
        unit = str(metric["scoreUnit"])

        converted.append(
            {
                "name": f"{operation} — {BACKEND_LABELS[backend]}",
                "unit": unit,
                "value": score,
                "range": score_error,
                "extra": "\n".join(
                    (
                        f"Backend: {BACKEND_LABELS[backend]}",
                        f"JDK: {result.get('jdkVersion', 'unknown')}",
                        f"Mode: {result.get('mode', 'unknown')}",
                        f"Forks: {result.get('forks', 'unknown')}",
                    )
                ),
            }
        )

    return sorted(converted, key=lambda item: item["name"])


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-backend-comparison.py <jmh-result.json> <comparison.json>"
        )

    source = Path(sys.argv[1])
    target = Path(sys.argv[2])
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("JMH result must be a JSON array")

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(convert(raw), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
