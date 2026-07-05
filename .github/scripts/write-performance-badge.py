#!/usr/bin/env python3
"""Write a Shields endpoint JSON file for the performance smoke workflow."""

from __future__ import annotations

import json
import sys
from pathlib import Path

BADGE_DIR = Path("docs/badges")


def color(seconds: float) -> str:
    if seconds <= 30:
        return "brightgreen"
    if seconds <= 60:
        return "yellow"
    if seconds <= 120:
        return "orange"
    return "red"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: write-performance-badge.py <seconds>")
    seconds = float(sys.argv[1])
    BADGE_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "label": "perf smoke",
        "message": f"{seconds:.1f}s",
        "color": color(seconds),
    }
    (BADGE_DIR / "performance.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
