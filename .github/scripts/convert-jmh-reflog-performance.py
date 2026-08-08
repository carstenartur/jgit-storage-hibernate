#!/usr/bin/env python3
"""Convert the PostgreSQL/SQL Server reflog index matrix for the benchmark dashboard."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from benchmark_units import CANONICAL_UNIT, normalize_measurement

OPERATIONS = {
    "lastEntry": "Reflog latest entry",
    "lastHundred": "Reflog last 100 entries",
}
BACKENDS = {
    "postgresql": "PostgreSQL",
    "sqlserver": "SQL Server",
}
INDEXES = {
    "legacy-repository-id": "legacy repository/id index",
    "repository-ref-key-id": "repository/ref-key/id index",
}


def convert(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for result in results:
      benchmark = str(result["benchmark"])
      method = benchmark.rsplit(".", 1)[-1]
      params = result.get("params", {})
      backend = str(params.get("backend", ""))
      index_mode = str(params.get("indexMode", ""))
      if method not in OPERATIONS:
          raise ValueError(f"Unsupported reflog benchmark method: {method!r}")
      if backend not in BACKENDS:
          raise ValueError(f"Unsupported reflog backend: {backend!r}")
      if index_mode not in INDEXES:
          raise ValueError(f"Unsupported reflog index mode: {index_mode!r}")
      key = (method, backend, index_mode)
      if key in seen:
          raise ValueError(f"Duplicate reflog benchmark result: {key!r}")
      seen.add(key)

      metric = result["primaryMetric"]
      original_score = metric["score"]
      original_error = metric.get("scoreError", 0.0)
      original_unit = str(metric["scoreUnit"])
      score, score_error = normalize_measurement(
          original_score, original_error, original_unit
      )
      implementation = f"{BACKENDS[backend]} {INDEXES[index_mode]}"
      extra_lines = [
          f"Database: {BACKENDS[backend]}",
          f"Index: {INDEXES[index_mode]}",
          f"Rows: {params.get('rowCount', 'unknown')}",
          f"Refs: {params.get('refCount', 'unknown')}",
          f"JDK: {result.get('jdkVersion', 'unknown')}",
          f"Mode: {result.get('mode', 'unknown')}",
      ]
      if original_unit != CANONICAL_UNIT:
          extra_lines.append(f"Original metric: {original_score} {original_unit}")
      converted.append(
          {
              "name": f"{OPERATIONS[method]} — {implementation}",
              "unit": CANONICAL_UNIT,
              "value": score,
              "range": score_error,
              "extra": "\n".join(extra_lines),
          }
      )

    expected = {
        (method, backend, index_mode)
        for method in OPERATIONS
        for backend in BACKENDS
        for index_mode in INDEXES
    }
    missing = expected - seen
    if missing:
        raise ValueError(
            "Missing reflog benchmark series: "
            + ", ".join(str(item) for item in sorted(missing))
        )
    return sorted(converted, key=lambda item: item["name"])


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: convert-jmh-reflog-performance.py <jmh-result.json> <comparison.json>"
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
