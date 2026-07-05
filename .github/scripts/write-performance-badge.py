#!/usr/bin/env python3
"""Write a Shields endpoint JSON file from JMH JSON benchmark output."""

from __future__ import annotations

import json
import sys
from pathlib import Path

BADGE_DIR = Path("docs/badges")


def color(score_ms: float) -> str:
    if score_ms <= 5:
        return "brightgreen"
    if score_ms <= 20:
        return "yellow"
    if score_ms <= 50:
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
    unit = primary.get("scoreUnit", "ms/op")
    return benchmark.rsplit(".", 1)[-1], score, unit


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: write-performance-badge.py <jmh-result.json>")
    benchmark_name, score, unit = load_primary_score(Path(sys.argv[1]))
    BADGE_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "label": "JMH",
        "message": f"{benchmark_name} {score:.2f} {unit}",
        "color": color(score),
    }
    (BADGE_DIR / "performance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
