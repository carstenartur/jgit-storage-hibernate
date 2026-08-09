#!/usr/bin/env python3
"""Update the GitHub Pages benchmark history without changing Git branches in CI."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path
from typing import Any

from benchmark_consumer_relevance import apply_to_benches, resolve as resolve_consumer_relevance
from benchmark_units import CANONICAL_UNIT, normalize_benchmark

SCRIPT_PREFIX = "window.BENCHMARK_DATA = "
NAME_SEPARATOR = " — "


def _git(repository: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout.strip()


def _chart_operation(name: str) -> str:
    separator_index = name.rfind(NAME_SEPARATOR)
    return name if separator_index < 0 else name[:separator_index]


def _validate_chart_units(benches: list[dict[str, Any]], context: str) -> None:
    """Require one physical unit per rendered chart and smaller-is-better semantics."""

    units_by_operation: dict[str, str] = {}
    for benchmark in benches:
        operation = _chart_operation(str(benchmark["name"]))
        unit = str(benchmark["unit"])
        previous = units_by_operation.setdefault(operation, unit)
        if previous != unit:
            raise ValueError(
                f"Mixed units in chart {operation!r} ({context}): {previous!r} versus {unit!r}. "
                "Split the operation or normalize the values before publishing."
            )


def _load_data(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"lastUpdate": 0, "repoUrl": "", "entries": {}}

    content = path.read_text(encoding="utf-8")
    if not content.startswith(SCRIPT_PREFIX):
        raise ValueError(f"Benchmark data file {path} does not start with the expected prefix")
    parsed = json.loads(content[len(SCRIPT_PREFIX) :])
    if not isinstance(parsed, dict) or not isinstance(parsed.get("entries"), dict):
        raise ValueError(f"Benchmark data file {path} has an invalid structure")
    return parsed


def _load_benches(path: Path) -> list[dict[str, Any]]:
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, list) or not parsed:
        raise ValueError(f"Benchmark result {path} must be a non-empty JSON array")

    benches: list[dict[str, Any]] = []
    for index, benchmark in enumerate(parsed):
        if not isinstance(benchmark, dict):
            raise ValueError(f"Benchmark entry {index} is not an object")
        for field in ("name", "unit", "value"):
            if field not in benchmark:
                raise ValueError(f"Benchmark entry {index} is missing {field!r}")
        if not isinstance(benchmark["name"], str) or not benchmark["name"]:
            raise ValueError(f"Benchmark entry {index} has an invalid name")
        if not isinstance(benchmark["unit"], str) or not benchmark["unit"]:
            raise ValueError(f"Benchmark entry {index} has an invalid unit")
        if not isinstance(benchmark["value"], (int, float)):
            raise ValueError(f"Benchmark entry {index} has a non-numeric value")
        benches.append(normalize_benchmark(benchmark))
    _validate_chart_units(benches, str(path))
    return benches


def _normalize_history(history: list[dict[str, Any]]) -> None:
    """Migrate legacy mixed throughput/time points to one smaller-is-better unit."""

    series_units: dict[str, str] = {}
    for entry_index, entry in enumerate(history):
        if not isinstance(entry, dict):
            raise ValueError(f"Benchmark history entry {entry_index} is not an object")
        benches = entry.get("benches", [])
        if not isinstance(benches, list):
            raise ValueError(f"Benchmark history entry {entry_index} has invalid benches")
        normalized: list[dict[str, Any]] = []
        for bench_index, benchmark in enumerate(benches):
            if not isinstance(benchmark, dict):
                raise ValueError(
                    f"Benchmark history entry {entry_index}, bench {bench_index} is not an object"
                )
            normalized_benchmark = normalize_benchmark(benchmark)
            name = str(normalized_benchmark["name"])
            unit = str(normalized_benchmark["unit"])
            previous_unit = series_units.setdefault(name, unit)
            if previous_unit != unit:
                raise ValueError(
                    f"Benchmark series {name!r} changed unit in retained history: "
                    f"{previous_unit!r} versus {unit!r}"
                )
            normalized.append(normalized_benchmark)
        _validate_chart_units(normalized, f"history entry {entry_index}")
        entry["benches"] = normalized
        entry["tool"] = "customSmallerIsBetter"


def _validate_current_series_units(
    history: list[dict[str, Any]], benches: list[dict[str, Any]]
) -> None:
    retained_units: dict[str, str] = {}
    for entry in history:
        for benchmark in entry.get("benches", []):
            retained_units.setdefault(str(benchmark["name"]), str(benchmark["unit"]))
    for benchmark in benches:
        name = str(benchmark["name"])
        old_unit = retained_units.get(name)
        if old_unit is not None and old_unit != benchmark["unit"]:
            raise ValueError(
                f"Benchmark series {name!r} changed unit: {old_unit!r} versus {benchmark['unit']!r}. "
                "Keep the unit stable or publish a new series name."
            )


def _relevance_for_suite(repository_dir: Path, suite_name: str) -> dict[str, object] | None:
    consumer_descriptor = repository_dir / ".github" / "consumer-compatibility.json"
    relevance_map = repository_dir / ".github" / "benchmark-consumer-relevance.json"
    if not consumer_descriptor.exists() or not relevance_map.exists():
        return None
    return resolve_consumer_relevance(consumer_descriptor, relevance_map, suite_name)


def _commit_metadata(repository: Path, commit: str, repository_url: str, actor: str) -> dict[str, Any]:
    author_name = _git(repository, "show", "-s", "--format=%an", commit)
    author_email = _git(repository, "show", "-s", "--format=%ae", commit)
    committer_name = _git(repository, "show", "-s", "--format=%cn", commit)
    committer_email = _git(repository, "show", "-s", "--format=%ce", commit)
    timestamp = _git(repository, "show", "-s", "--format=%cI", commit)
    tree_id = _git(repository, "show", "-s", "--format=%T", commit)
    message = _git(repository, "show", "-s", "--format=%B", commit)

    return {
        "author": {"email": author_email, "name": author_name, "username": actor},
        "committer": {"email": committer_email, "name": committer_name, "username": actor},
        "distinct": True,
        "id": commit,
        "message": message,
        "timestamp": timestamp,
        "tree_id": tree_id,
        "url": f"{repository_url.rstrip('/')}/commit/{commit}",
    }


def _ratio(previous: dict[str, Any], current: dict[str, Any], smaller_is_better: bool) -> float:
    previous_value = float(previous["value"])
    current_value = float(current["value"])
    if previous_value == 0.0 and current_value == 0.0:
        return 1.0
    if smaller_is_better:
        return float("inf") if previous_value == 0.0 else current_value / previous_value
    return float("inf") if current_value == 0.0 else previous_value / current_value


def _write_summary(
    path: Path,
    suite_name: str,
    previous: dict[str, Any] | None,
    current: dict[str, Any],
    alert_threshold: float,
) -> None:
    lines = [f"### {suite_name}", ""]
    if previous is None:
        lines.append("Stored the first comparable benchmark result.")
    else:
        previous_by_name = {bench["name"]: bench for bench in previous.get("benches", [])}
        lines.extend(
            [
                f"Current `{current['commit']['id'][:7]}` compared with `{previous['commit']['id'][:7]}`.",
                "",
                "| Benchmark | Current | Previous | Ratio |",
                "|---|---:|---:|---:|",
            ]
        )
        alerts: list[str] = []
        for benchmark in current["benches"]:
            old = previous_by_name.get(benchmark["name"])
            if old is None:
                lines.append(
                    f"| `{benchmark['name']}` | {benchmark['value']} {benchmark['unit']} | — | — |"
                )
                continue
            if benchmark["unit"] != old["unit"]:
                raise ValueError(
                    f"Cannot compare {benchmark['name']!r}: "
                    f"{old['unit']!r} versus {benchmark['unit']!r}"
                )
            ratio = _ratio(old, benchmark, smaller_is_better=True)
            ratio_text = "∞" if ratio == float("inf") else f"{ratio:.2f}×"
            lines.append(
                f"| `{benchmark['name']}` | {benchmark['value']} {benchmark['unit']} | "
                f"{old['value']} {old['unit']} | {ratio_text} |"
            )
            if ratio > alert_threshold:
                alerts.append(benchmark["name"])
        if alerts:
            lines.extend(
                [
                    "",
                    f"⚠️ {len(alerts)} result(s) exceeded the {alert_threshold:.2f}× alert threshold: "
                    + ", ".join(f"`{name}`" for name in alerts),
                ]
            )
        else:
            lines.extend(["", "No benchmark exceeded the configured regression alert threshold."])

    with path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines) + "\n")


def update_history(
    *,
    data_file: Path,
    benchmark_file: Path,
    repository_dir: Path,
    repository_url: str,
    commit: str,
    actor: str,
    suite_name: str,
    tool: str,
    max_items: int,
    timestamp_ms: int,
    summary_file: Path | None,
    alert_threshold: float,
) -> None:
    if max_items < 1:
        raise ValueError("max-items must be at least one")
    if alert_threshold <= 0:
        raise ValueError("alert-threshold must be positive")
    if tool != "customSmallerIsBetter":
        raise ValueError(
            f"Canonical {CANONICAL_UNIT} history requires customSmallerIsBetter, got {tool!r}"
        )

    data = _load_data(data_file)
    benches = _load_benches(benchmark_file)
    benches = apply_to_benches(benches, _relevance_for_suite(repository_dir, suite_name))
    suites = data.setdefault("entries", {})
    history = suites.setdefault(suite_name, [])
    if not isinstance(history, list):
        raise ValueError(f"Benchmark suite {suite_name!r} is not a list")
    _normalize_history(history)
    _validate_current_series_units(history, benches)

    previous = next((entry for entry in reversed(history) if entry.get("commit", {}).get("id") != commit), None)
    current = {
        "commit": _commit_metadata(repository_dir, commit, repository_url, actor),
        "date": timestamp_ms,
        "tool": tool,
        "benches": benches,
    }

    history[:] = [entry for entry in history if entry.get("commit", {}).get("id") != commit]
    history.append(current)
    if len(history) > max_items:
        del history[:-max_items]

    data["lastUpdate"] = timestamp_ms
    data["repoUrl"] = repository_url.rstrip("/")
    data_file.parent.mkdir(parents=True, exist_ok=True)
    data_file.write_text(SCRIPT_PREFIX + json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    if summary_file is not None:
        _write_summary(summary_file, suite_name, previous, current, alert_threshold)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-file", required=True, type=Path)
    parser.add_argument("--benchmark-file", required=True, type=Path)
    parser.add_argument("--repository-dir", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--actor", default="github-actions[bot]")
    parser.add_argument("--suite-name", default="Repository backend comparison")
    parser.add_argument("--tool", default="customSmallerIsBetter")
    parser.add_argument("--max-items", type=int, default=100)
    parser.add_argument("--timestamp-ms", type=int, default=None)
    parser.add_argument("--summary-file", type=Path)
    parser.add_argument("--alert-threshold", type=float, default=1.5)
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    update_history(
        data_file=args.data_file,
        benchmark_file=args.benchmark_file,
        repository_dir=args.repository_dir,
        repository_url=args.repository_url,
        commit=args.commit,
        actor=args.actor,
        suite_name=args.suite_name,
        tool=args.tool,
        max_items=args.max_items,
        timestamp_ms=args.timestamp_ms if args.timestamp_ms is not None else int(time.time() * 1000),
        summary_file=args.summary_file,
        alert_threshold=args.alert_threshold,
    )


if __name__ == "__main__":
    main()
