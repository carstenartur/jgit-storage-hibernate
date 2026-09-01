#!/usr/bin/env python3
"""Write a Shields endpoint JSON file from JMH JSON benchmark output."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from benchmark_units import normalize_measurement

BADGE_DIR = Path("docs/badges")


def color(score_ms_per_op: float) -> str:
    if score_ms_per_op <= 5:
        return "brightgreen"
    if score_ms_per_op <= 20:
        return "yellow"
    if score_ms_per_op <= 50:
        return "orange"
    return "red"


def load_primary_score(path: Path) -> tuple[str, float, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not data:
        raise SystemExit(f"No JMH benchmark results found in {path}")
    first = data[0]
    benchmark = first.get("benchmark", "JMH")
    primary = first.get("primaryMetric", {})
    score = float(primary.get("score"))
    unit = str(primary.get("scoreUnit", "ms/op"))
    return benchmark.rsplit(".", 1)[-1], score, unit


def build_payload(path: Path) -> dict[str, object]:
    benchmark_name, score, unit = load_primary_score(path)
    # Keep the displayed native JMH metric, but classify it using the same
    # canonical smaller-is-better ms/op semantics as the benchmark charts.
    score_ms_per_op, _ = normalize_measurement(score, 0.0, unit)
    return {
        "schemaVersion": 1,
        "label": "JMH",
        "message": f"{benchmark_name} {score:.2f} {unit}",
        "color": color(score_ms_per_op),
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: write-performance-badge.py <jmh-result.json>")
    BADGE_DIR.mkdir(parents=True, exist_ok=True)
    payload = build_payload(Path(sys.argv[1]))
    (BADGE_DIR / "performance.json").write_text(
        json.dumps(payload, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
