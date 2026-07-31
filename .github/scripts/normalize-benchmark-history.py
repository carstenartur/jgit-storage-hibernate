#!/usr/bin/env python3
"""Normalize every stored benchmark history point to milliseconds per operation."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from benchmark_units import normalize_benchmark

SCRIPT_PREFIX = "window.BENCHMARK_DATA = "


def normalize_history(data: dict[str, Any]) -> dict[str, Any]:
    entries = data.get("entries")
    if not isinstance(entries, dict):
        raise ValueError("Benchmark history has no entries object")

    normalized = dict(data)
    normalized_entries: dict[str, Any] = {}
    for suite_name, history in entries.items():
        if not isinstance(history, list):
            raise ValueError(f"Benchmark suite {suite_name!r} is not a list")
        normalized_history: list[dict[str, Any]] = []
        for entry_index, entry in enumerate(history):
            if not isinstance(entry, dict):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} is not an object"
                )
            benches = entry.get("benches")
            if not isinstance(benches, list):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} has no benches array"
                )
            normalized_entry = dict(entry)
            normalized_entry["benches"] = [normalize_benchmark(bench) for bench in benches]
            normalized_history.append(normalized_entry)
        normalized_entries[suite_name] = normalized_history
    normalized["entries"] = normalized_entries
    return normalized


def normalize_file(path: Path) -> bool:
    if not path.exists():
        return False
    content = path.read_text(encoding="utf-8")
    if not content.startswith(SCRIPT_PREFIX):
        raise ValueError(f"Benchmark data file {path} does not start with the expected prefix")
    parsed = json.loads(content[len(SCRIPT_PREFIX) :])
    if not isinstance(parsed, dict):
        raise ValueError(f"Benchmark data file {path} does not contain an object")
    normalized_content = (
        SCRIPT_PREFIX
        + json.dumps(normalize_history(parsed), indent=2, ensure_ascii=False)
        + "\n"
    )
    if normalized_content == content:
        return False
    path.write_text(normalized_content, encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("data_file", type=Path)
    args = parser.parse_args()
    normalize_file(args.data_file)


if __name__ == "__main__":
    main()
